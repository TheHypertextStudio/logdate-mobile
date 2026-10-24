package app.logdate.client.sync.diagnostics

import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import app.logdate.shared.model.diagnostics.SyncDiagnosticReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

internal enum class DiagnosticDelivery { ACCEPTED, RETRY, RATE_LIMITED, REJECTED }

internal data class DiagnosticReportingStatus(
    val enabled: Boolean = false,
    val pendingCount: Int = 0,
)

/** Consent and delivery bookkeeping is private operational state, never part of a report/export. */
internal class DiagnosticReporting(
    private val storage: DiagnosticStorage,
    private val currentScope: () -> UploadScope?,
    private val supported: (UploadScope) -> Boolean,
    private val nowMillis: () -> Long,
    private val currentEpoch: () -> String? = { "00000000-0000-4000-8000-000000000000" },
    private val send: suspend (UploadScope, SyncDiagnosticReport) -> DiagnosticDelivery,
) {
    @Serializable
    private data class Grant(
        val scope: UploadScope,
        val generation: String,
        val epoch: String? = null,
    )

    @Serializable
    private data class Pending(
        val report: SyncDiagnosticReport,
        val createdAt: Long,
        val attempts: Int = 0,
        val nextAttemptAt: Long = 0,
    )

    @Serializable
    private data class Stored(
        val grant: Grant? = null,
        val pending: List<Pending> = emptyList(),
    )

    private val mutex = Mutex()
    private val admittedGrant = MutableStateFlow<Grant?>(null)
    private var stored: Stored? = null
    private var inFlight: Job? = null
    private val recent = ArrayDeque<SyncDiagnosticEvent>()

    suspend fun enable(expectedScope: UploadScope) =
        mutex.withLock {
            val current = reconcile()
            check(currentScope() == expectedScope && supported(expectedScope)) { "DIAGNOSTIC_REPORTING_UNAVAILABLE" }
            if (current.grant?.scope == expectedScope) return@withLock
            val epoch = requireNotNull(currentEpoch()) { "DIAGNOSTIC_REPORTING_UNAVAILABLE" }
            val next = Stored(Grant(expectedScope, Uuid.random().toString(), epoch))
            // Persist before accepting any events under the new grant.
            persist(next)
            recent.clear()
        }

    suspend fun disable() =
        mutex.withLock {
            admittedGrant.value = null
            inFlight?.cancel()
            recent.clear()
            stored = Stored()
            persist(Stored())
        }

    suspend fun refreshScope() =
        mutex.withLock {
            reconcile()
            Unit
        }

    /** Unscoped events are local-only and cannot inherit current consent. */
    suspend fun capture(
        event: SyncDiagnosticEvent,
        sourceScope: UploadScope?,
    ) = mutex.withLock {
        captureLocked(event, sourceScope, reconcile().grant?.generation)
    }

    private suspend fun captureLocked(
        event: SyncDiagnosticEvent,
        sourceScope: UploadScope?,
        admission: String?,
    ) {
        val state = reconcile()
        val grant = state.grant ?: return
        if (admission == null || admission != grant.generation) return
        if (sourceScope != grant.scope || !supported(grant.scope)) return
        DiagnosticReportCodec.validateEvent(event)
        recent.addLast(event.copy(elapsedMs = nowMillis()))
        while (recent.size > 64) recent.removeFirst()
        if (event.outcome !in setOf(DiagnosticOutcome.FAILED, DiagnosticOutcome.INTERRUPTED, DiagnosticOutcome.CONFLICT)) return
        // Keep one unsent copy of a repeated failure. A retry always reuses its original report bytes.
        if (state.pending.any { sameFailure(it.report.events.lastOrNull(), event) }) return
        if (state.pending.size >= MAX_PENDING) return
        val aliases = mutableMapOf<String, String>()
        val start = recent.firstOrNull()?.elapsedMs ?: 0L
        val report =
            SyncDiagnosticReport(
                Uuid.random().toString(),
                events =
                    recent.map { entry ->
                        entry.copy(
                            elapsedMs = (entry.elapsedMs - start).coerceAtLeast(0),
                            recordAlias = entry.recordAlias?.let { aliases.getOrPut(it) { Uuid.random().toString() } },
                        )
                    },
            )
        DiagnosticReportCodec.encode(report)
        persist(state.copy(pending = state.pending + Pending(report, nowMillis())))
    }

    fun admission(sourceScope: UploadScope?): String? =
        admittedGrant.value
            ?.takeIf {
                it.scope == sourceScope &&
                    it.scope == currentScope() &&
                    it.epoch != null &&
                    it.epoch == currentEpoch() &&
                    supported(it.scope)
            }?.generation

    suspend fun captureAdmitted(
        event: SyncDiagnosticEvent,
        sourceScope: UploadScope?,
        admission: String?,
    ) = mutex.withLock {
        captureLocked(event, sourceScope, admission)
    }

    suspend fun deliverOnce() =
        coroutineScope {
            val (grant, pending) = selectDelivery() ?: return@coroutineScope
            val delivery = async(start = CoroutineStart.LAZY) { attemptDelivery(grant, pending) }
            if (!claimDelivery(grant, delivery)) {
                delivery.cancel()
                return@coroutineScope
            }
            try {
                recordDelivery(grant, pending, delivery.await())
            } catch (cancelled: CancellationException) {
                // Explicit revocation cancels the child request, not the caller's unrelated work.
                currentCoroutineContext().ensureActive()
                val revoked = mutex.withLock { stored?.grant != grant }
                if (!revoked) throw cancelled
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { if (inFlight === delivery) inFlight = null }
                }
            }
        }

    private suspend fun selectDelivery(): Pair<Grant, Pending>? =
        mutex.withLock {
            val current = reconcile()
            val grant = current.grant ?: return@withLock null
            if (!supported(grant.scope) || inFlight != null) return@withLock null
            current.pending.firstOrNull { it.nextAttemptAt <= nowMillis() }?.let { grant to it }
        }

    private suspend fun attemptDelivery(
        grant: Grant,
        pending: Pending,
    ): DiagnosticDelivery {
        val allowed = mutex.withLock { reconcile().grant == grant && supported(grant.scope) }
        if (!allowed) return DiagnosticDelivery.REJECTED
        return try {
            send(grant.scope, pending.report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Delivery failures never feed diagnostic recording recursively.
            DiagnosticDelivery.RETRY
        }
    }

    private suspend fun claimDelivery(
        grant: Grant,
        delivery: Job,
    ): Boolean =
        mutex.withLock {
            if (reconcile().grant != grant || inFlight != null) return@withLock false
            inFlight = delivery
            true
        }

    private suspend fun recordDelivery(
        grant: Grant,
        pending: Pending,
        result: DiagnosticDelivery,
    ) {
        mutex.withLock {
            val current = reconcile()
            if (current.grant != grant) return@withLock
            val next =
                current.pending.mapNotNull { queued ->
                    if (queued.report.reportId != pending.report.reportId) return@mapNotNull queued
                    rescheduled(queued, result)
                }
            persist(current.copy(pending = next))
        }
    }

    private fun rescheduled(
        queued: Pending,
        result: DiagnosticDelivery,
    ): Pending? =
        when (result) {
            DiagnosticDelivery.ACCEPTED, DiagnosticDelivery.REJECTED -> {
                null
            }

            DiagnosticDelivery.RETRY, DiagnosticDelivery.RATE_LIMITED -> {
                val attempts = (queued.attempts + 1).coerceAtMost(30)
                val delay =
                    if (result == DiagnosticDelivery.RATE_LIMITED) {
                        DAY_MS
                    } else {
                        (30_000L shl attempts.coerceAtMost(10)).coerceAtMost(6 * 60 * 60 * 1000L)
                    }
                queued.copy(attempts = attempts, nextAttemptAt = nowMillis() + delay)
            }
        }

    suspend fun status(): DiagnosticReportingStatus =
        mutex.withLock {
            val state = reconcile()
            DiagnosticReportingStatus(state.grant != null, state.pending.size)
        }

    private suspend fun reconcile(): Stored {
        val loaded =
            stored ?: try {
                val raw = storage.read()
                require(raw == null || raw.encodeToByteArray().size <= MAX_STORE_BYTES)
                (raw?.let { Json.decodeFromString<Stored>(it) } ?: Stored()).also { state ->
                    require(state.pending.size <= MAX_PENDING)
                    state.grant?.let { require(DiagnosticReportCodec.isCorrelationId(it.generation)) }
                    state.pending.forEach {
                        require(it.attempts in 0..30 && it.createdAt >= 0 && it.nextAttemptAt >= 0)
                        DiagnosticReportCodec.encode(it.report)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Stored()
            }
        stored = loaded
        admittedGrant.value = loaded.grant
        if (loaded.grant != null &&
            (loaded.grant.scope != currentScope() || loaded.grant.epoch == null || loaded.grant.epoch != currentEpoch())
        ) {
            admittedGrant.value = null
            inFlight?.cancel()
            recent.clear()
            stored = Stored()
            persist(Stored())
            return requireNotNull(stored)
        }
        val retained = loaded.pending.filter { it.createdAt >= nowMillis() - 7 * DAY_MS }
        if (retained.size != loaded.pending.size) persist(loaded.copy(pending = retained))
        return requireNotNull(stored)
    }

    private suspend fun persist(value: Stored) {
        val encoded = Json.encodeToString(value)
        check(encoded.encodeToByteArray().size <= MAX_STORE_BYTES) { "DIAGNOSTIC_QUEUE_FULL" }
        try {
            storage.write(encoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("DIAGNOSTIC_QUEUE_STORAGE_FAILED")
        }
        stored = value
        admittedGrant.value = value.grant
    }

    private fun sameFailure(
        first: SyncDiagnosticEvent?,
        second: SyncDiagnosticEvent,
    ): Boolean =
        first != null &&
            first.phase == second.phase &&
            first.outcome == second.outcome &&
            first.reason == second.reason &&
            first.operationId == second.operationId

    private companion object {
        const val MAX_PENDING = 20
        const val MAX_STORE_BYTES = 6 * 1024 * 1024
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
