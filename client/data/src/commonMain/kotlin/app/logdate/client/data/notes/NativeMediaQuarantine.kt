package app.logdate.client.data.notes

import app.logdate.client.data.journals.NativeDraftMediaQuarantine
import app.logdate.client.data.notes.drafts.NativeEntryDraftMediaQuarantine
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlin.uuid.Uuid

/** Removes destination-ambiguous provider identities before restored content becomes visible. */
class NativeMediaQuarantine(
    private val database: LogDateDatabase,
    private val drafts: NativeDraftMediaQuarantine,
    private val transactions: SyncTransactionManager,
    private val transcriptions: TranscriptionRepository,
    private val deliveryGate: NativeRestoreDeliveryGate = ImmediateNativeRestoreDeliveryGate,
    private val entryDrafts: NativeEntryDraftMediaQuarantine? = null,
    private val mediaExists: suspend (String) -> Boolean,
) {
    suspend fun run(isCurrent: () -> Boolean): Boolean =
        deliveryGate.withLock {
            var availabilityChecksComplete = true
            val quarantine: suspend (String) -> String? = { reference ->
                quarantined(reference) { availabilityChecksComplete = false }
            }
            check(isCurrent()) { "Native media delivery changed" }
            // File checks run before the transaction, so restored storage I/O never holds the
            // database writer. Each update only applies if the reference is still the one checked.
            val images = database.imageNoteDao().getAll().mapNotNull { replacement(it.uid, it.contentUri, quarantine) }
            val audio = database.audioNoteDao().getAll().mapNotNull { replacement(it.uid, it.contentUri, quarantine) }
            val videos = database.videoNoteDao().getAll().mapNotNull { replacement(it.uid, it.contentUri, quarantine) }
            val covers =
                database.journalDao().getAll().mapNotNull { journal ->
                    journal.coverImageUri?.let { replacement(journal.id, it, quarantine) }
                }
            transactions.withTransaction {
                images.forEach { (uid, previous, next) -> database.imageNoteDao().updateContentUriIfUnchanged(uid, previous, next) }
                audio.forEach { (uid, previous, next) ->
                    database.audioNoteDao().updateContentUriIfUnchanged(uid, previous, next)
                    transcriptions.rebindMediaReference(uid, previous, next)
                }
                videos.forEach { (uid, previous, next) -> database.videoNoteDao().updateContentUriIfUnchanged(uid, previous, next) }
                covers.forEach { (id, previous, next) -> database.journalDao().updateCoverImageUriIfUnchanged(id, previous, next) }
                check(isCurrent()) { "Native media delivery changed" }
            }
            drafts.run(isCurrent, quarantine)
            val currentDraftsComplete = entryDrafts?.run(isCurrent, quarantine) ?: true
            check(isCurrent()) { "Native media delivery changed" }
            availabilityChecksComplete && currentDraftsComplete
        }

    private suspend fun replacement(
        id: Uuid,
        reference: String,
        quarantine: suspend (String) -> String?,
    ): ReferenceReplacement? = quarantine(reference)?.let { ReferenceReplacement(id, reference, it) }

    private data class ReferenceReplacement(
        val id: Uuid,
        val previous: String,
        val next: String,
    )

    private suspend fun quarantined(
        reference: String,
        onUnknown: () -> Unit,
    ): String? {
        if (UnavailableMediaReferences.requiresNativeQuarantine(reference)) {
            return UnavailableMediaReferences.nativeRestore(reference)
        }
        if (UnavailableMediaReferences.isUnavailable(reference)) return null
        if (MediaReference.parse(reference) !is MediaReference.Owned &&
            MediaReference.parse(reference) !is MediaReference.LocalFile
        ) {
            return null
        }
        val available =
            try {
                mediaExists(reference)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onUnknown()
                // Keep the source reference inside the unavailable marker so a later retry can
                // resolve it, while preventing restored UI from treating an unchecked URI as
                // present during startup.
                return UnavailableMediaReferences.nativeRestore(reference)
            }
        return if (available) null else UnavailableMediaReferences.nativeRestore(reference)
    }
}

private object ImmediateNativeRestoreDeliveryGate : NativeRestoreDeliveryGate {
    override suspend fun <T> withLock(block: suspend () -> T): T = block()
}

internal suspend fun quarantineNativeDraft(
    draft: EditorDraft,
    quarantine: suspend (String) -> String?,
): EditorDraft {
    suspend fun repair(reference: String?): String? = reference?.let { quarantine(it) ?: it }
    val blocks =
        draft.blocks.map { block ->
            when (block) {
                is SerializableImageBlock -> block.copy(uri = repair(block.uri))
                is SerializableVideoBlock -> block.copy(uri = repair(block.uri), thumbnailUri = repair(block.thumbnailUri))
                is SerializableAudioBlock -> block.copy(uri = repair(block.uri))
                is SerializableCameraBlock -> block.copy(uri = repair(block.uri))
                else -> block
            }
        }
    return draft.copy(blocks = blocks)
}
