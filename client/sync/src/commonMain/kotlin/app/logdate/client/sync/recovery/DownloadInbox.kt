package app.logdate.client.sync.recovery

import app.logdate.client.database.dao.sync.DownloadInboxDao
import app.logdate.client.database.entities.sync.DownloadCheckpointEntity
import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.shared.model.diagnostics.DiagnosticAction
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import app.logdate.shared.model.sync.ContentChange
import app.logdate.shared.model.sync.DraftChange
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** These identifiers are operational state and must never be added to diagnostic reports. */
data class DownloadScope(
    val owner: String,
    val origin: String,
)

data class WireDownload(
    val id: String,
    val version: Long,
    val deleted: Boolean,
    val payload: String,
)

class DownloadInbox(
    private val dao: DownloadInboxDao,
    private val transactions: SyncTransactionManager,
    private val scope: () -> DownloadScope,
    private val now: () -> Long,
    private val diagnosticSource: () -> DiagnosticSource? = { null },
    private val diagnostics: (SyncDiagnosticEvent, DiagnosticSource?) -> Unit = { _, _ -> },
) {
    fun currentScope(): DownloadScope = scope()

    suspend fun hasRecord(
        type: String,
        id: String,
        selected: DownloadScope,
    ): Boolean {
        check(selected == scope()) { "Download scope changed" }
        return dao.get(selected.owner, selected.origin, type, id) != null
    }

    suspend fun rewindForLegacyAudit(selected: DownloadScope = scope()) {
        check(selected == scope()) { "Download scope changed" }
        transactions.withTransaction {
            for (type in listOf("NOTE", "JOURNAL")) {
                dao.checkpoint(DownloadCheckpointEntity(selected.owner, selected.origin, type, 0))
            }
            dao.release(selected.owner, selected.origin, DiagnosticReason.entries.map { it.name })
        }
    }

    internal fun captureDiagnosticSource(selected: DownloadScope): DiagnosticSource? =
        try {
            diagnosticSource().takeIf { source ->
                source != null && source.scope.ownerId == selected.owner && source.scope.serverOrigin == selected.origin
            }
        } catch (_: Exception) {
            null
        }

    internal fun recordDiagnostic(
        event: SyncDiagnosticEvent,
        source: DiagnosticSource?,
    ) {
        try {
            diagnostics(event, source)
        } catch (_: Exception) {
            // Observability must not change the outcome of a durable operation.
        }
    }

    internal fun recordMediaAttempt(
        row: DownloadInboxEntity,
        attemptId: String,
        outcome: DiagnosticOutcome,
        source: DiagnosticSource?,
    ) = recordDiagnostic(
        SyncDiagnosticEvent(
            DiagnosticPhase.MEDIA,
            outcome,
            operationId = row.operationId,
            attemptId = attemptId,
            attemptCount = row.attempts + 1,
        ),
        source,
    )

    suspend fun stage(
        type: String,
        cursor: Long,
        records: List<WireDownload>,
        selected: DownloadScope = scope(),
    ) {
        check(selected == scope()) { "Download scope changed" }
        require(selected.owner.isNotBlank() && selected.origin.isNotBlank())
        val source = captureDiagnosticSource(selected)
        val staged = mutableListOf<DownloadInboxEntity>()
        var cursorAdvanced = false
        try {
            transactions.withTransaction {
                for (record in records) {
                    val existing = dao.get(selected.owner, selected.origin, type, record.id)
                    val reconsiderLegacy =
                        existing?.state == DownloadInboxState.APPLIED.name &&
                            existing.version == record.version &&
                            !record.deleted &&
                            (existing.payload.isEmpty() || existing.payload == record.payload) &&
                            (type == "NOTE" || type == "JOURNAL") &&
                            record.payload.contains("LDSE1:")
                    if (existing != null && existing.version >= record.version && !reconsiderLegacy) continue
                    val row = waitingRow(selected, type, record)
                    dao.put(row)
                    staged += row
                }
                val previous = dao.checkpoint(selected.owner, selected.origin, type)?.cursor ?: 0
                cursorAdvanced = cursor > previous
                dao.checkpoint(DownloadCheckpointEntity(selected.owner, selected.origin, type, maxOf(cursor, previous)))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            recordDiagnostic(SyncDiagnosticEvent(DiagnosticPhase.PERSIST, DiagnosticOutcome.INTERRUPTED), source)
            throw cancelled
        } catch (error: Exception) {
            recordDiagnostic(
                SyncDiagnosticEvent(
                    DiagnosticPhase.PERSIST,
                    DiagnosticOutcome.FAILED,
                    reason = DiagnosticReason.PERSISTENCE_FAILED,
                    action = DiagnosticAction.RETRY,
                    retryable = true,
                ),
                source,
            )
            throw error
        }
        // The transaction call has returned; no success is published while its block is open.
        recordStaged(staged, cursorAdvanced, source)
    }

    private fun recordStaged(
        staged: List<DownloadInboxEntity>,
        cursorAdvanced: Boolean,
        source: DiagnosticSource?,
    ) {
        for (row in staged) {
            recordDiagnostic(
                SyncDiagnosticEvent(
                    DiagnosticPhase.PERSIST,
                    DiagnosticOutcome.SUCCEEDED,
                    operationId = row.operationId,
                    cursorAdvanced = cursorAdvanced,
                ),
                source,
            )
        }
    }

    private fun waitingRow(
        selected: DownloadScope,
        type: String,
        record: WireDownload,
    ): DownloadInboxEntity =
        DownloadInboxEntity(
            ownerId = selected.owner,
            serverOrigin = selected.origin,
            entityType = type,
            entityId = record.id,
            version = record.version,
            deleted = record.deleted,
            payload = record.payload,
            operationId = Uuid.random().toString(),
            state = DownloadInboxState.WAITING.name,
            attempts = 0,
            nextAttemptAt = 0,
            reason = DiagnosticReason.NONE.name,
        )

    suspend fun fetchFailure(
        type: String,
        selected: DownloadScope = scope(),
    ): DiagnosticReason =
        dao.checkpoint(selected.owner, selected.origin, type)?.fetchFailure?.let { code ->
            DiagnosticReason.entries.firstOrNull { it.name == code }
        } ?: DiagnosticReason.NONE

    suspend fun fetchFailed(
        type: String,
        reason: DiagnosticReason,
        selected: DownloadScope = scope(),
    ) {
        check(selected == scope()) { "Download scope changed" }
        transactions.withTransaction {
            val previous =
                dao.checkpoint(selected.owner, selected.origin, type)
                    ?: DownloadCheckpointEntity(selected.owner, selected.origin, type, 0)
            dao.checkpoint(previous.copy(fetchFailure = reason.name))
        }
    }

    suspend fun cursor(
        type: String,
        selected: DownloadScope = scope(),
    ): Long =
        selected.let {
            dao.checkpoint(it.owner, it.origin, type)?.cursor
                ?: 0
        }

    suspend fun pending(
        type: String,
        selected: DownloadScope = scope(),
    ): List<DownloadInboxEntity> =
        selected.let {
            dao.pending(it.owner, it.origin, type, now())
        }

    suspend fun serverVersion(
        type: String,
        id: String,
        selected: DownloadScope = scope(),
    ): Long? = dao.get(selected.owner, selected.origin, type, id)?.takeUnless { it.deleted }?.version

    suspend fun isCurrentVersion(
        type: String,
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
    ): Boolean {
        if (selected != scope()) return false
        val row = dao.get(selected.owner, selected.origin, type, id) ?: return false
        return row.version == version && !row.deleted
    }

    suspend fun isAppliedVersion(
        type: String,
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
    ): Boolean {
        if (selected != scope()) return false
        val row = dao.get(selected.owner, selected.origin, type, id) ?: return false
        return row.version == version && row.state == DownloadInboxState.APPLIED.name && !row.deleted
    }

    suspend fun skipped(
        type: String,
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
    ) = applied(type, id, version, selected, enqueueMedia = false)

    suspend fun applied(
        type: String,
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
        enqueueMedia: Boolean = true,
    ) {
        transactions.withTransaction {
            val row = dao.get(selected.owner, selected.origin, type, id) ?: return@withTransaction
            if (row.version != version) return@withTransaction
            if (enqueueMedia && type == "NOTE" && !row.deleted && row.payload.startsWith("{")) {
                val mediaRef = Json.decodeFromString<ContentChange>(row.payload).mediaUri
                if (mediaRef != null && (mediaRef.startsWith("https://") || mediaRef.startsWith("http://"))) {
                    val prior = dao.get(selected.owner, selected.origin, "MEDIA_NOTE", id)
                    if (prior == null || prior.version < row.version) {
                        dao.put(
                            row.copy(
                                entityType = "MEDIA_NOTE",
                                operationId = Uuid.random().toString(),
                                state = DownloadInboxState.WAITING.name,
                                attempts = 0,
                                nextAttemptAt = 0,
                                reason = DiagnosticReason.NONE.name,
                            ),
                        )
                    }
                }
            }
            if (enqueueMedia && type == "DRAFT" && !row.deleted && row.payload.startsWith("{")) {
                val wire = Json.decodeFromString<DraftChange>(row.payload)
                if (wire.encryptedBlocks != null) {
                    val prior = dao.get(selected.owner, selected.origin, "MEDIA_DRAFT", id)
                    if (prior == null || prior.version < row.version) {
                        dao.put(
                            row.copy(
                                entityType = "MEDIA_DRAFT",
                                operationId = Uuid.random().toString(),
                                state = DownloadInboxState.WAITING.name,
                                attempts = 0,
                                nextAttemptAt = 0,
                                reason = DiagnosticReason.NONE.name,
                            ),
                        )
                    }
                }
            }
            val retainedPayload =
                if (type == "DRAFT" && !row.deleted && row.payload.startsWith("{")) {
                    val wire = Json.decodeFromString<DraftChange>(row.payload)
                    Json.encodeToString(wire.copy(content = "", blockTypes = emptyList(), journalIds = emptyList()))
                } else if (type == "DRAFT" && !row.deleted) {
                    row.payload
                } else {
                    ""
                }
            dao.put(
                row.copy(
                    payload = retainedPayload,
                    state = DownloadInboxState.APPLIED.name,
                    reason = DiagnosticReason.NONE.name,
                    nextAttemptAt = 0,
                ),
            )
        }
    }

    suspend fun failed(
        type: String,
        id: String,
        reason: String,
        version: Long? = null,
        selected: DownloadScope = scope(),
        attemptId: String? = null,
        source: DiagnosticSource? = captureDiagnosticSource(selected),
    ) {
        var retried: DownloadInboxEntity? = null
        transactions.withTransaction {
            val row = dao.get(selected.owner, selected.origin, type, id) ?: return@withTransaction
            if (row.state == DownloadInboxState.APPLIED.name || (version != null && row.version != version)) return@withTransaction
            val attempt = (row.attempts + 1).coerceAtMost(30)
            val classified = DiagnosticReason.entries.firstOrNull { it.name == reason } ?: DiagnosticReason.UNKNOWN
            val parks = attempt >= MAX_ATTEMPTS
            val delay = (1000L shl attempt.coerceAtMost(12)).coerceAtMost(60 * 60 * 1000L)
            val updated =
                row.copy(
                    state = (if (parks) DownloadInboxState.PARKED else DownloadInboxState.FAILED).name,
                    attempts = attempt,
                    nextAttemptAt = now() + delay,
                    reason = classified.name,
                )
            dao.put(updated)
            retried = updated
        }
        retried?.let { row ->
            val parked = row.state == DownloadInboxState.PARKED.name
            val classified = DiagnosticReason.entries.firstOrNull { it.name == row.reason } ?: DiagnosticReason.UNKNOWN
            recordDiagnostic(
                SyncDiagnosticEvent(
                    phase = if (type.startsWith("MEDIA_")) DiagnosticPhase.MEDIA else DiagnosticPhase.APPLY,
                    outcome = if (parked) DiagnosticOutcome.FAILED else DiagnosticOutcome.RETRY_SCHEDULED,
                    reason = classified,
                    action = actionFor(classified),
                    operationId = row.operationId,
                    attemptId = attemptId ?: Uuid.random().toString(),
                    attemptCount = row.attempts,
                    retryable = !parked,
                ),
                source,
            )
        }
    }

    private fun actionFor(reason: DiagnosticReason): DiagnosticAction =
        when (reason) {
            DiagnosticReason.OFFLINE -> DiagnosticAction.CONNECT
            DiagnosticReason.SIGN_IN_REQUIRED -> DiagnosticAction.SIGN_IN
            DiagnosticReason.LOCAL_STORAGE -> DiagnosticAction.RETRY
            DiagnosticReason.KEY_RECOVERY_REQUIRED -> DiagnosticAction.RECOVER_KEY
            DiagnosticReason.INCOMPATIBLE_SERVER -> DiagnosticAction.UPDATE_SERVER
            DiagnosticReason.CONFLICT -> DiagnosticAction.REVIEW_CONFLICT
            else -> DiagnosticAction.RETRY
        }

    suspend fun release() {
        scope().let { dao.release(it.owner, it.origin, RELEASABLE_REASONS.map { reason -> reason.name }) }
    }

    suspend fun count(): Int = scope().let { dao.count(it.owner, it.origin) }

    /** Rows that have failed at least once and are not applied: retrying, or parked for a user decision. */
    suspend fun needingAttentionCount(): Int = scope().let { dao.countNeedingAttention(it.owner, it.origin) }

    /** Rows a pass can retry now. Backed-off and parked rows are excluded. */
    suspend fun dueCount(): Int = scope().let { dao.countDue(it.owner, it.origin, now()) }

    companion object {
        const val MAX_ATTEMPTS = 9

        /** Failures a manual backup may retry. Device-wide and permanent failures wait for their own fix. */
        private val RELEASABLE_REASONS =
            setOf(
                DiagnosticReason.NONE,
                DiagnosticReason.OFFLINE,
                DiagnosticReason.SIGN_IN_REQUIRED,
                DiagnosticReason.SERVER_UNAVAILABLE,
                DiagnosticReason.RATE_LIMITED,
                DiagnosticReason.QUOTA_EXCEEDED,
                DiagnosticReason.LOCAL_STORAGE,
                DiagnosticReason.PERSISTENCE_FAILED,
                DiagnosticReason.UNKNOWN,
            )
    }
}
