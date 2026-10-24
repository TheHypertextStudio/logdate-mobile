package app.logdate.feature.core.restore

import app.logdate.shared.model.diagnostics.DiagnosticReason
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun <C, T> observeArchiveRecoveryReadiness(
    origins: Flow<C>,
    sessions: Flow<T>,
    inventory: Flow<Long> = flowOf(0L),
    identityChanges: Flow<Unit> = flowOf(Unit),
    networkChanges: Flow<Any> = flowOf(Unit),
    schedule: suspend (change: ArchiveRecoveryReadinessChange) -> Unit,
) {
    var identityRevision = 0L
    var previous: ArchiveRecoveryReadinessSnapshot<C, T>? = null
    var pendingChange = ArchiveRecoveryReadinessChange()
    combine(
        origins,
        sessions,
        inventory,
        identityChanges.map { ++identityRevision },
        networkChanges,
    ) { origin, session, media, keys, network ->
        ArchiveRecoveryReadinessSnapshot(origin, session, media, keys, network)
    }.distinctUntilChanged()
        .collectLatest { current ->
            val change = previous?.changesTo(current) ?: ArchiveRecoveryReadinessChange()
            previous = current
            pendingChange = pendingChange.merge(change)
            var interval = 30_000L
            while (true) {
                try {
                    schedule(pendingChange)
                    pendingChange = ArchiveRecoveryReadinessChange()
                    return@collectLatest
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Napier.w("Archive recovery scheduling remains pending and will retry")
                }
                delay(interval)
                interval = (interval * 2).coerceAtMost(900_000L)
            }
        }
}

internal data class ArchiveRecoveryReadinessChange(
    val restartRunningWork: Boolean = false,
    val mediaInventoryChanged: Boolean = false,
) {
    val hasChanged: Boolean get() = restartRunningWork || mediaInventoryChanged

    fun merge(next: ArchiveRecoveryReadinessChange) =
        ArchiveRecoveryReadinessChange(
            restartRunningWork = restartRunningWork || next.restartRunningWork,
            mediaInventoryChanged = mediaInventoryChanged || next.mediaInventoryChanged,
        )
}

private data class ArchiveRecoveryReadinessSnapshot<C, T>(
    val origin: C,
    val session: T,
    val inventoryRevision: Long,
    val identityRevision: Long,
    val networkPolicy: Any,
) {
    fun changesTo(next: ArchiveRecoveryReadinessSnapshot<C, T>) =
        ArchiveRecoveryReadinessChange(
            restartRunningWork =
                origin != next.origin ||
                    session != next.session ||
                    identityRevision != next.identityRevision ||
                    networkPolicy != next.networkPolicy,
            mediaInventoryChanged = inventoryRevision != next.inventoryRevision,
        )
}

internal fun RestoreSummary.shouldRetryMediaRecovery(): Boolean = recoveringMedia && (unavailableOriginals > 0 || unavailablePreviews > 0)

internal fun DiagnosticReason.shouldRetryArchiveMedia(hasSession: Boolean = true): Boolean =
    when (this) {
        DiagnosticReason.OFFLINE, DiagnosticReason.SERVER_UNAVAILABLE, DiagnosticReason.RATE_LIMITED,
        DiagnosticReason.QUOTA_EXCEEDED, DiagnosticReason.UNKNOWN, DiagnosticReason.NETWORK_POLICY,
        -> hasSession

        DiagnosticReason.LOCAL_STORAGE, DiagnosticReason.PERSISTENCE_FAILED -> true

        else -> false
    }

internal class ArchiveRecoveryScheduleGate {
    private val mutex = Mutex()

    suspend fun enqueue(
        change: ArchiveRecoveryReadinessChange,
        hasQueuedWork: suspend () -> Boolean,
        hasRunningWork: suspend () -> Boolean,
        isDelayedRetry: suspend () -> Boolean = { false },
        replaceRunningWork: suspend () -> Unit = {},
        replaceDelayedRetry: suspend () -> Unit = {},
        enqueueSuccessor: suspend () -> Unit,
    ) {
        mutex.withLock {
            when {
                hasRunningWork() && change.restartRunningWork -> replaceRunningWork()

                isDelayedRetry() && change.hasChanged -> replaceDelayedRetry()

                hasQueuedWork() -> Unit

                // Inventory changes can make a previously skipped asset available, but must let
                // a useful in-flight download reach its durable checkpoint before the follow-up.
                hasRunningWork() && change.mediaInventoryChanged -> enqueueSuccessor()

                // Startup reconciliation trusts active work; only a readiness event that can
                // unblock a stale attempt should replace its retry chain.
                hasRunningWork() -> Unit

                else -> enqueueSuccessor()
            }
        }
    }
}

internal enum class ArchiveRecoveryWorkPhase {
    RUNNING,
    ENQUEUED,
    BLOCKED,
    FINISHED,
}

internal data class ArchiveRecoveryWorkState(
    val phase: ArchiveRecoveryWorkPhase,
    val runAttemptCount: Int,
    val explicitDelay: Boolean = false,
)

internal fun List<ArchiveRecoveryWorkState>.hasQueuedArchiveRecoveryWork(): Boolean =
    any { it.phase == ArchiveRecoveryWorkPhase.ENQUEUED || it.phase == ArchiveRecoveryWorkPhase.BLOCKED }

internal fun List<ArchiveRecoveryWorkState>.hasRunningArchiveRecoveryWork(): Boolean = any { it.phase == ArchiveRecoveryWorkPhase.RUNNING }

internal fun List<ArchiveRecoveryWorkState>.hasDelayedArchiveRecoveryRetry(): Boolean =
    none { it.phase == ArchiveRecoveryWorkPhase.RUNNING } &&
        any { it.phase == ArchiveRecoveryWorkPhase.ENQUEUED && (it.runAttemptCount > 0 || it.explicitDelay) }
