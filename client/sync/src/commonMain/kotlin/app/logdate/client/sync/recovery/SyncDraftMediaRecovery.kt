package app.logdate.client.sync.recovery

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableDraftRepository
import app.logdate.client.sync.DraftAssetMapping
import app.logdate.client.sync.DraftMediaHydrationResult
import app.logdate.client.sync.SyncMediaTransfer
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.throwIfDeviceBlocked
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticReason
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Repairs draft attachments after the draft's text and block metadata are already visible. */
internal class SyncDraftMediaRecovery(
    private val inbox: DownloadInbox,
    private val drafts: JournalRepository,
    private val transfer: SyncMediaTransfer,
    private val refs: MediaSyncRefStore,
    private val transactions: SyncTransactionManager,
) {
    suspend fun recover(accessToken: String) {
        val scope = inbox.currentScope()
        for (row in inbox.pending("MEDIA_DRAFT", scope)) {
            if (scope != inbox.currentScope()) return
            if (!recoverRow(accessToken, scope, row)) return
        }
    }

    /** Returns false when the download scope changed and the remaining rows must not be processed. */
    private suspend fun recoverRow(
        accessToken: String,
        scope: DownloadScope,
        row: DownloadInboxEntity,
    ): Boolean {
        val source = inbox.captureDiagnosticSource(scope)
        val attemptId = Uuid.random().toString()
        var started = false
        try {
            val id = Uuid.parse(row.entityId)
            if (!inbox.isCurrentVersion("DRAFT", row.entityId, row.version, scope)) {
                inbox.applied("MEDIA_DRAFT", row.entityId, row.version, scope)
                return true
            }
            val current = drafts.getDraft(id)
            if (current == null) {
                inbox.applied("MEDIA_DRAFT", row.entityId, row.version, scope)
                return true
            }
            started = true
            inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.STARTED, source)
            val result = transfer.hydrateDraftMedia(accessToken, current)
            return applyHydration(scope, row, id, current, result, attemptId, source)
        } catch (cancelled: CancellationException) {
            if (started) inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.INTERRUPTED, source)
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            inbox.failed("MEDIA_DRAFT", row.entityId, DiagnosticReason.LOCAL_STORAGE.name, row.version, scope, attemptId, source)
            return true
        }
    }

    /** Returns false when the download scope changed and the remaining rows must not be processed. */
    private suspend fun applyHydration(
        scope: DownloadScope,
        row: DownloadInboxEntity,
        id: Uuid,
        current: EditorDraft,
        result: DraftMediaHydrationResult,
        attemptId: String,
        source: DiagnosticSource?,
    ): Boolean {
        val (draft, mappings) =
            when (result) {
                is DraftMediaHydrationResult.Pending -> result.draft to result.mappings
                is DraftMediaHydrationResult.Available -> result.draft to result.mappings
            }
        if (!persistProgress(scope, row.entityId, row.version, id, current, draft, mappings)) {
            inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.INTERRUPTED, source)
            return true
        }
        if (scope != inbox.currentScope()) {
            inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.INTERRUPTED, source)
            return false
        }
        when (result) {
            is DraftMediaHydrationResult.Pending -> {
                inbox.failed("MEDIA_DRAFT", row.entityId, result.reason.name, row.version, scope, attemptId, source)
            }

            is DraftMediaHydrationResult.Available -> {
                inbox.applied("MEDIA_DRAFT", row.entityId, row.version, scope)
                inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.SUCCEEDED, source)
            }
        }
        return true
    }

    private suspend fun persistProgress(
        scope: DownloadScope,
        entityId: String,
        version: Long,
        draftId: Uuid,
        original: EditorDraft,
        updated: EditorDraft,
        mappings: List<DraftAssetMapping>,
    ): Boolean {
        var persisted = false
        transactions.withTransaction {
            if (scope != inbox.currentScope() ||
                !inbox.isCurrentVersion("DRAFT", entityId, version, scope) ||
                drafts.getDraft(draftId) != original
            ) {
                return@withTransaction
            }
            mappings.forEach { mapping ->
                if (scope != inbox.currentScope()) return@withTransaction
                refs.upsertDraftAsset(
                    mapping.draftId,
                    mapping.blockId,
                    mapping.kind,
                    mapping.ref.copy(ownerId = scope.owner, serverOrigin = scope.origin),
                )
            }
            if (updated != original) {
                val syncable =
                    drafts as? SyncableDraftRepository
                        ?: error("Sync draft repository unavailable")
                syncable.saveDraftFromSync(updated)
            }
            persisted = scope == inbox.currentScope()
        }
        return persisted
    }
}
