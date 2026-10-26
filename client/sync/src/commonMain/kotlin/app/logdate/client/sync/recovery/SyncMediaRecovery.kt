package app.logdate.client.sync.recovery

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.MediaHydrationResult
import app.logdate.client.sync.SyncMediaTransfer
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.throwIfDeviceBlocked
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.sync.ContentChange
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** Attachment recovery is independent of text/metadata application. */
internal class SyncMediaRecovery(
    private val inbox: DownloadInbox,
    private val notes: JournalNotesRepository,
    private val transfer: SyncMediaTransfer,
    private val refs: MediaSyncRefStore,
    private val transactions: SyncTransactionManager,
) {
    suspend fun recover(accessToken: String) {
        val scope = inbox.currentScope()
        for (row in inbox.pending("MEDIA_NOTE", scope)) {
            if (scope != inbox.currentScope()) return
            recoverRow(accessToken, scope, row)
        }
    }

    private suspend fun recoverRow(
        accessToken: String,
        scope: DownloadScope,
        row: DownloadInboxEntity,
    ) {
        val source = inbox.captureDiagnosticSource(scope)
        val attemptId = Uuid.random().toString()
        var started = false
        try {
            val wire = Json.decodeFromString<ContentChange>(row.payload)
            val id = Uuid.parse(row.entityId)
            val owner = notes.getNoteById(id)
            if (!inbox.isCurrentVersion("NOTE", row.entityId, row.version, scope) ||
                owner == null ||
                owner.mediaRefOrNull() != wire.mediaUri ||
                owner.syncVersion != row.version
            ) {
                inbox.applied("MEDIA_NOTE", row.entityId, row.version, scope)
                return
            }
            started = true
            inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.STARTED, source)
            when (val result = transfer.hydrate(accessToken, owner)) {
                is MediaHydrationResult.Pending -> {
                    inbox.failed("MEDIA_NOTE", row.entityId, result.reason.name, row.version, scope, attemptId, source)
                }

                is MediaHydrationResult.Available -> {
                    val attached = attach(scope, row, id, owner, result)
                    inbox.recordMediaAttempt(
                        row,
                        attemptId,
                        if (attached) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.INTERRUPTED,
                        source,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            if (started) inbox.recordMediaAttempt(row, attemptId, DiagnosticOutcome.INTERRUPTED, source)
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            inbox.failed("MEDIA_NOTE", row.entityId, DiagnosticReason.LOCAL_STORAGE.name, row.version, scope, attemptId, source)
        }
    }

    /** Returns whether the hydrated media reference was attached to the unchanged note. */
    private suspend fun attach(
        scope: DownloadScope,
        row: DownloadInboxEntity,
        id: Uuid,
        owner: JournalNote,
        result: MediaHydrationResult.Available,
    ): Boolean {
        var attached = false
        transactions.withTransaction {
            if (scope != inbox.currentScope()) throw CancellationException("Download scope changed")
            val current = notes.getNoteById(id)
            if (current == owner && inbox.isCurrentVersion("NOTE", row.entityId, row.version, scope)) {
                val syncable =
                    notes as? SyncableJournalNotesRepository
                        ?: error("Sync repository unavailable")
                syncable.updateMediaRef(id, requireNotNull(result.note.mediaRefOrNull()))
                if (scope != inbox.currentScope()) throw CancellationException("Download scope changed")
                result.mapping?.let { refs.upsert(it.copy(ownerId = scope.owner, serverOrigin = scope.origin)) }
                attached = true
            }
            inbox.applied("MEDIA_NOTE", row.entityId, row.version, scope)
            if (scope != inbox.currentScope()) throw CancellationException("Download scope changed")
        }
        return attached
    }
}
