package app.logdate.client.sync.recovery

import app.logdate.client.database.dao.sync.DownloadInboxDao
import app.logdate.client.database.entities.sync.DownloadCheckpointEntity
import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.space.SpaceInboxPayload
import app.logdate.shared.model.diagnostics.DiagnosticAction
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import app.logdate.shared.model.sync.ContentChange
import app.logdate.shared.model.sync.DraftChange
import io.github.aakira.napier.Napier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** These identifiers are operational state and must never be added to diagnostic reports. */
data class DownloadScope(
    val owner: String,
    val origin: String,
)

/** Starts a media-recovery row for a note applied from the personal space; the file's reference follows. */
internal const val SPACE_MEDIA_PREFIX = "SPACE1-MEDIA:"

/** Marks a draft media row as one for a draft applied from the personal space, whose files it refers to. */
internal const val SPACE_DRAFT_MEDIA_PREFIX = "SPACE1-DRAFT-MEDIA"

/** Marks a journal media row as one for a journal applied from the personal space, whose cover it refers to. */
internal const val SPACE_JOURNAL_MEDIA_PREFIX = "SPACE1-JOURNAL-MEDIA"

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
    private val nativeQueue = NativeMediaInboxQueue(dao, transactions, scope)
    private val failures = DownloadInboxFailureRecorder(dao, transactions, now, ::recordDiagnostic)

    fun currentScope(): DownloadScope = scope()

    /** Missing native originals are local work, independent of the retained server-version watermark. */
    internal suspend fun enqueueNativeMedia(
        type: String,
        id: String,
        version: Long,
        reference: String,
        selected: DownloadScope,
    ) = nativeQueue.enqueue(type, id, version, reference, selected)

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
        } catch (error: Exception) {
            Napier.w("Download diagnostics have no source: ${error::class.simpleName}")
            null
        }

    internal fun recordDiagnostic(
        event: SyncDiagnosticEvent,
        source: DiagnosticSource?,
    ) {
        try {
            diagnostics(event, source)
        } catch (error: Exception) {
            // Observability must not change the outcome of a durable operation.
            Napier.w("A download diagnostic could not be recorded: ${error::class.simpleName}")
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
    ) = stageAll(type, cursor, mapOf(type to records), selected)

    /**
     * Stages records of several kinds, keyed by entity type, and advances one [checkpoint] to
     * [cursor], all in one transaction. The personal space's change feed carries every kind of data
     * under one cursor, so a page is staged whole or not at all.
     */
    suspend fun stageAll(
        checkpoint: String,
        cursor: Long,
        records: Map<String, List<WireDownload>>,
        selected: DownloadScope = scope(),
    ) {
        check(selected == scope()) { "Download scope changed" }
        require(selected.owner.isNotBlank() && selected.origin.isNotBlank())
        val source = captureDiagnosticSource(selected)
        val staged = mutableListOf<DownloadInboxEntity>()
        var cursorAdvanced = false
        try {
            transactions.withTransaction {
                for ((type, rows) in records) {
                    for (record in rows) {
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
                }
                val previous = dao.checkpoint(selected.owner, selected.origin, checkpoint)?.cursor ?: 0
                cursorAdvanced = cursor > previous
                dao.checkpoint(DownloadCheckpointEntity(selected.owner, selected.origin, checkpoint, maxOf(cursor, previous)))
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

    /**
     * Moves [checkpoint] back to zero, so the next download lists everything again. Staged rows and
     * their applied versions stay, so records already applied aren't applied twice.
     */
    suspend fun resetCheckpoint(
        checkpoint: String,
        selected: DownloadScope = scope(),
    ) {
        check(selected == scope()) { "Download scope changed" }
        dao.checkpoint(DownloadCheckpointEntity(selected.owner, selected.origin, checkpoint, 0))
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

    /** All unapplied records, including those held by retry backoff or an unsupported format. */
    suspend fun retained(
        type: String,
        selected: DownloadScope = scope(),
    ): List<DownloadInboxEntity> = dao.pending(selected.owner, selected.origin, type, Long.MAX_VALUE)

    /** The row for [id] of [type], in any state. */
    internal suspend fun row(
        type: String,
        id: String,
        selected: DownloadScope = scope(),
    ): DownloadInboxEntity? = dao.get(selected.owner, selected.origin, type, id)

    /**
     * Queues the file a note applied from the personal space refers to, as [reference], for media
     * recovery. The note holds no inbox row of its own, so the row names the file itself.
     */
    internal suspend fun enqueueSpaceMedia(
        id: String,
        version: Long,
        reference: String,
        selected: DownloadScope = scope(),
    ) {
        enqueueSpaceMediaRow("MEDIA_NOTE", id, version, SPACE_MEDIA_PREFIX + reference, selected)
    }

    /**
     * Queues the files of a draft applied from the personal space for media recovery. The draft
     * holds no inbox row of its own, so the row only says which draft, and recovery reads its blocks.
     */
    internal suspend fun enqueueSpaceDraftMedia(
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
    ) {
        enqueueSpaceMediaRow("MEDIA_DRAFT", id, version, SPACE_DRAFT_MEDIA_PREFIX, selected)
    }

    /**
     * Queues the cover of a journal applied from the personal space for media recovery. The journal
     * holds no inbox row of its own, so the row only says which journal.
     */
    internal suspend fun enqueueSpaceJournalMedia(
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
    ) {
        enqueueSpaceMediaRow("MEDIA_JOURNAL", id, version, SPACE_JOURNAL_MEDIA_PREFIX, selected)
    }

    /** Queues [id]'s media at [version] unless the inbox already holds that version or a newer one. */
    private suspend fun enqueueSpaceMediaRow(
        type: String,
        id: String,
        version: Long,
        payload: String,
        selected: DownloadScope,
    ) {
        transactions.withTransaction {
            check(selected == scope()) { "Download scope changed" }
            val prior = dao.get(selected.owner, selected.origin, type, id)
            if (prior != null && prior.version >= version) return@withTransaction
            dao.put(waitingRow(selected, type, WireDownload(id, version, false, payload)))
        }
    }

    /** Replaces the payload of [id]'s row while it still holds [version] and isn't applied. */
    internal suspend fun replacePayload(
        type: String,
        id: String,
        version: Long,
        payload: String,
        selected: DownloadScope = scope(),
    ) {
        transactions.withTransaction {
            val row = dao.get(selected.owner, selected.origin, type, id) ?: return@withTransaction
            if (row.version != version || row.state == DownloadInboxState.APPLIED.name) return@withTransaction
            dao.put(row.copy(payload = payload))
        }
    }

    /** Every row of [type], applied or not. */
    internal suspend fun all(
        type: String,
        selected: DownloadScope = scope(),
    ): List<DownloadInboxEntity> = dao.all(selected.owner, selected.origin, type)

    suspend fun serverVersion(
        type: String,
        id: String,
        selected: DownloadScope = scope(),
    ): Long? = dao.get(selected.owner, selected.origin, type, id)?.takeUnless { it.deleted }?.version

    /** Only a tombstone already applied to local storage can authorize settling missing-body work. */
    suspend fun appliedDeletionVersion(
        type: String,
        id: String,
        selected: DownloadScope = scope(),
    ): Long? {
        if (selected != scope()) return null
        return dao
            .get(selected.owner, selected.origin, type, id)
            ?.takeIf { it.deleted && it.state == DownloadInboxState.APPLIED.name && it.version > 0 }
            ?.version
    }

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

    suspend fun isCurrentDeletionVersion(
        type: String,
        id: String,
        version: Long,
        selected: DownloadScope = scope(),
    ): Boolean {
        if (selected != scope()) return false
        val row = dao.get(selected.owner, selected.origin, type, id) ?: return false
        return row.version == version && row.deleted
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
            if (enqueueMedia) enqueueAppliedMedia(type, id, row, selected)
            val retainedPayload =
                if (type == "DRAFT" && !row.deleted && row.payload.startsWith("{")) {
                    val wire = Json.decodeFromString<DraftChange>(row.payload)
                    Json.encodeToString(wire.copy(content = "", blockTypes = emptyList(), journalIds = emptyList()))
                } else if (type == "DRAFT" && !row.deleted) {
                    row.payload
                } else if (row.payload.startsWith(SpaceInboxPayload.PREFIX)) {
                    // Which record it was, without its content, so a later deletion of it is still found.
                    SpaceInboxPayload
                        .decode(row.payload)
                        ?.withoutContent()
                        ?.encode()
                        .orEmpty()
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

    private suspend fun enqueueAppliedMedia(
        type: String,
        id: String,
        row: DownloadInboxEntity,
        selected: DownloadScope,
    ) {
        if (type == "NOTE" && !row.deleted && row.payload.startsWith("{")) {
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
        if (type == "DRAFT" && !row.deleted && row.payload.startsWith("{")) {
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
    }

    suspend fun failed(
        type: String,
        id: String,
        reason: String,
        version: Long? = null,
        selected: DownloadScope = scope(),
        attemptId: String? = null,
        source: DiagnosticSource? = captureDiagnosticSource(selected),
    ) = failures.record(type, id, reason, version, selected, attemptId, source)

    suspend fun release(afterRecovery: Boolean = false) {
        val reasons = if (afterRecovery) DiagnosticReason.entries else RELEASABLE_REASONS
        scope().let { dao.release(it.owner, it.origin, reasons.map { reason -> reason.name }, afterRecovery) }
    }

    suspend fun count(): Int = scope().let { dao.count(it.owner, it.origin) }

    internal suspend fun recoveryCounts(includePolicyDeferred: Boolean = false) =
        scope().let { dao.recoveryCounts(it.owner, it.origin, now(), includePolicyDeferred) }

    /** Rows that have failed at least once and are not applied: retrying, or parked for a user decision. */
    suspend fun needingAttentionCount(): Int = scope().let { dao.countNeedingAttention(it.owner, it.origin) }

    /**
     * Rows a pass can retry now. Backed-off and parked rows are excluded, and rows deferred by the
     * network policy count only when [includePolicyDeferred] says the current connection allows them.
     */
    suspend fun dueCount(includePolicyDeferred: Boolean = false): Int =
        scope().let { dao.countDue(it.owner, it.origin, now(), includePolicyDeferred) }

    companion object {
        const val MAX_ATTEMPTS = 9

        /** Failures a manual backup may retry. Device-wide and permanent failures wait for their own fix. */
        private val RELEASABLE_REASONS =
            setOf(
                DiagnosticReason.NONE,
                DiagnosticReason.NETWORK_POLICY,
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
