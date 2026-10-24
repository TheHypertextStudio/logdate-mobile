package app.logdate.client.sync.recovery

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.EntryDraftRecoveryRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.MediaHydrationResult
import app.logdate.client.sync.SyncMediaTransfer
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.shared.model.diagnostics.DiagnosticReason
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Durable original-media repair for current-editor drafts, independent of legacy archive drafts. */
internal class NativeEntryDraftMediaRecovery(
    private val drafts: EntryDraftRecoveryRepository,
    private val inbox: DownloadInbox,
    private val transfer: SyncMediaTransfer,
    private val refs: MediaSyncRefStore,
    private val mediaReferences: StoredMediaReferences,
) {
    suspend fun recover(accessToken: String) {
        val scope = inbox.currentScope()
        for (draft in drafts.getDraftSnapshotsForRecovery()) {
            requireCurrent(scope)
            if (draft.recoveryNotes().any { missing(it) }) {
                inbox.enqueueNativeMedia(TYPE, draft.id.toString(), draft.updatedAt.toEpochMilliseconds(), "entry-draft", scope)
            }
        }
        for (row in inbox.pending(TYPE, scope)) {
            requireCurrent(scope)
            try {
                recoverRow(accessToken, row, scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Draft media recovery failed: ${error::class.simpleName}")
                inbox.failed(TYPE, row.entityId, DiagnosticReason.LOCAL_STORAGE.name, row.version, scope)
            }
        }
    }

    private suspend fun recoverRow(
        accessToken: String,
        row: DownloadInboxEntity,
        scope: DownloadScope,
    ) {
        val id = Uuid.parse(row.entityId)
        val original = drafts.getDraftSnapshotsForRecovery().firstOrNull { it.id == id }
        var reason: DiagnosticReason? = null
        for (note in original?.recoveryNotes().orEmpty()) {
            requireCurrent(scope)
            if (!missing(note)) continue
            when (val result = transfer.hydrate(accessToken, note) { scope == inbox.currentScope() }) {
                is MediaHydrationResult.Pending -> {
                    if (reason == null) reason = result.reason
                }

                is MediaHydrationResult.Available -> {
                    requireCurrent(scope)
                    result.mapping?.let { refs.upsert(it.copy(ownerId = scope.owner, serverOrigin = scope.origin)) }
                    val reference = mediaReferences.storedReference(requireNotNull(result.note.mediaRefOrNull()))
                    if (!patch(id, note, reference, scope)) return
                }
            }
        }
        requireCurrent(scope)
        val latest = drafts.getDraftSnapshotsForRecovery().firstOrNull { it.id == id }
        val pending = latest?.recoveryNotes()?.any { missing(it) } == true
        requireCurrent(scope)
        if (pending) {
            inbox.failed(TYPE, row.entityId, (reason ?: DiagnosticReason.MISSING_MEDIA).name, row.version, scope)
        } else {
            inbox.applied(TYPE, row.entityId, row.version, scope)
        }
    }

    private suspend fun patch(
        id: Uuid,
        source: JournalNote,
        reference: String,
        scope: DownloadScope,
    ): Boolean {
        repeat(3) {
            requireCurrent(scope)
            val latest = drafts.getDraftSnapshotsForRecovery().firstOrNull { it.id == id } ?: return true

            fun matches(note: JournalNote) =
                note.uid == source.uid && note.type == source.type && note.mediaRefOrNull() == source.mediaRefOrNull()
            if (latest.recoveryNotes().none(::matches)) return true
            val repaired =
                latest.copy(
                    notes = latest.notes.map { if (matches(it)) withReference(it, reference) else it },
                    editBase =
                        latest.editBase?.let { base ->
                            if (matches(base.original)) base.copy(original = withReference(base.original, reference)) else base
                        },
                )
            if (drafts.compareAndSetDraftForRecovery(latest, repaired) { scope == inbox.currentScope() }) return true
        }
        return false
    }

    private fun withReference(
        note: JournalNote,
        reference: String,
    ): JournalNote =
        when (note) {
            is JournalNote.Image -> note.copy(mediaRef = reference)
            is JournalNote.Audio -> note.copy(mediaRef = reference)
            is JournalNote.Video -> note.copy(mediaRef = reference)
            else -> note
        }

    private fun EntryDraft.recoveryNotes(): List<JournalNote> =
        (notes + listOfNotNull(editBase?.original)).distinctBy { Triple(it.uid, it.type, it.mediaRefOrNull()) }

    private suspend fun missing(note: JournalNote): Boolean = note.mediaRefOrNull()?.let { !transfer.originalAvailable(it) } == true

    private fun requireCurrent(scope: DownloadScope) {
        if (scope != inbox.currentScope()) throw CancellationException("Draft media recovery scope changed")
    }

    private companion object {
        const val TYPE = "MEDIA_NATIVE_ENTRY_DRAFT"
    }
}
