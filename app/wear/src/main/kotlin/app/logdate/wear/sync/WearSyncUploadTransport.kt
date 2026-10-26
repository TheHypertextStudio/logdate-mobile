package app.logdate.wear.sync

import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.PendingUpload
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Encodes and sends one captured outbox operation; settlement stays in the retry coordinator. */
internal class WearSyncUploadTransport(
    private val dataLayerClient: WearDataLayerClient,
    private val notesRepository: JournalNotesRepository,
    private val journalRepository: JournalRepository,
    private val noteDataMapper: NoteDataMapper,
    private val journalDataMapper: JournalDataMapper,
    private val associationDataMapper: AssociationDataMapper,
    private val mediaFiles: MediaFileResolver,
    private val clock: Clock,
    private val deliveryStore: WearAudioDeliveryStore,
) {
    suspend fun notifyRemoved(noteId: Uuid) {
        if (!dataLayerClient.putDataItem(NoteDataMapper.noteDeletePath(noteId), deleteSignal(noteId))) {
            Napier.w("Could not tell the phone note $noteId was removed")
        }
    }

    // -----------------------------------------------------------------------
    // Note upload helpers
    // -----------------------------------------------------------------------

    suspend fun uploadNote(pending: PendingUpload): WearUploadOutcome {
        val noteId = parseUuid(pending.entityId) ?: return WearUploadOutcome.SKIPPED

        val note = notesRepository.getNoteById(noteId)
        if (note == null) {
            Napier.w("Note $noteId not found in repository, skipping sync")
            return WearUploadOutcome.SKIPPED
        }

        val path = NoteDataMapper.notePath(noteId)
        val data =
            if (note is JournalNote.Audio) {
                val snapshot = deliverySnapshot(note)
                val delivery = deliveryStore.capture(pending, snapshot)
                val digest = snapshot[WearAudioRequestPaths.KEY_AUDIO_DIGEST]
                dataMapFor(note) + (WearAudioRequestPaths.KEY_TRANSFER_ID to delivery.token) +
                    (digest?.let { mapOf(WearAudioRequestPaths.KEY_AUDIO_DIGEST to it) } ?: emptyMap())
            } else {
                dataMapFor(note)
            }
        val putSuccess = dataLayerClient.putDataItem(path, data)

        if (!putSuccess) {
            Napier.w("Failed to put data item for note $noteId")
            return WearUploadOutcome.FAILED
        }

        if (note !is JournalNote.Audio) return WearUploadOutcome.SUCCESS

        if (!dataLayerClient.sendFile("$path/audio", mediaFiles.filePath(note.mediaRef) ?: note.mediaRef)) {
            Napier.w("Audio file transfer failed for note $noteId")
            return WearUploadOutcome.FAILED
        }
        return WearUploadOutcome.AWAITING_ACK
    }

    /**
     * Audio notes carry the send time so a resend is a changed data item. The Data Layer drops a
     * put that matches what it already holds, and the phone may have lost the earlier one.
     */
    private fun dataMapFor(note: JournalNote): Map<String, String> {
        val dataMap = noteDataMapper.toDataMap(note)
        if (note !is JournalNote.Audio) return dataMap
        return dataMap + (WearDataLayerSyncManager.KEY_SENT_AT to clock.now().toEpochMilliseconds().toString())
    }

    suspend fun deliverySnapshot(note: JournalNote.Audio): Map<String, String> =
        noteDataMapper.toDataMap(note) +
            (audioDigest(note)?.let { mapOf(WearAudioRequestPaths.KEY_AUDIO_DIGEST to it) } ?: emptyMap())

    private suspend fun audioDigest(note: JournalNote.Audio): String? =
        withContext(Dispatchers.IO) {
            val path = mediaFiles.filePath(note.mediaRef) ?: note.mediaRef
            val file = File(path)
            if (!file.isFile) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    suspend fun uploadNoteDelete(pending: PendingUpload): WearUploadOutcome {
        val noteId = parseUuid(pending.entityId) ?: return WearUploadOutcome.SKIPPED

        return if (dataLayerClient.putDataItem(NoteDataMapper.noteDeletePath(noteId), deleteSignal(noteId))) {
            WearUploadOutcome.SUCCESS
        } else {
            WearUploadOutcome.FAILED
        }
    }

    private fun deleteSignal(noteId: Uuid): Map<String, String> =
        mapOf(
            NoteDataMapper.KEY_UID to noteId.toString(),
            NoteDataMapper.KEY_NOTE_TYPE to "DELETE",
        )

    // -----------------------------------------------------------------------
    // Journal upload helpers
    // -----------------------------------------------------------------------

    suspend fun uploadJournal(pending: PendingUpload): WearUploadOutcome {
        val journalId = parseUuid(pending.entityId) ?: return WearUploadOutcome.SKIPPED

        val journal = journalRepository.getJournalById(journalId)
        if (journal == null) {
            Napier.w("Journal $journalId not found in repository, skipping sync")
            return WearUploadOutcome.SKIPPED
        }

        val dataMap = journalDataMapper.toDataMap(journal)
        val path = JournalDataMapper.journalPath(journalId)

        return if (dataLayerClient.putDataItem(path, dataMap)) {
            WearUploadOutcome.SUCCESS
        } else {
            Napier.w("Failed to put data item for journal $journalId")
            WearUploadOutcome.FAILED
        }
    }

    suspend fun uploadJournalDelete(pending: PendingUpload): WearUploadOutcome {
        val journalId = parseUuid(pending.entityId) ?: return WearUploadOutcome.SKIPPED

        val path = JournalDataMapper.journalDeletePath(journalId)
        val deleteData = mapOf(JournalDataMapper.KEY_UID to journalId.toString())
        return if (dataLayerClient.putDataItem(path, deleteData)) {
            WearUploadOutcome.SUCCESS
        } else {
            WearUploadOutcome.FAILED
        }
    }

    // -----------------------------------------------------------------------
    // Association upload helpers
    // -----------------------------------------------------------------------

    suspend fun uploadAssociation(pending: PendingUpload): WearUploadOutcome {
        val key = AssociationPendingKey.fromPendingId(pending.entityId)
        if (key == null) {
            Napier.w("Invalid association key in pending upload: ${pending.entityId}")
            return WearUploadOutcome.SKIPPED
        }

        val dataMap = associationDataMapper.toDataMap(key.journalId, key.contentId)
        val path = AssociationDataMapper.associationPath(key.journalId, key.contentId)

        return if (dataLayerClient.putDataItem(path, dataMap)) {
            WearUploadOutcome.SUCCESS
        } else {
            Napier.w("Failed to put data item for association ${pending.entityId}")
            WearUploadOutcome.FAILED
        }
    }

    suspend fun uploadAssociationDelete(pending: PendingUpload): WearUploadOutcome {
        val key = AssociationPendingKey.fromPendingId(pending.entityId)
        if (key == null) {
            Napier.w("Invalid association key in pending delete: ${pending.entityId}")
            return WearUploadOutcome.SKIPPED
        }

        val path = AssociationDataMapper.associationDeletePath(key.journalId, key.contentId)
        val deleteData = associationDataMapper.toDataMap(key.journalId, key.contentId)
        return if (dataLayerClient.putDataItem(path, deleteData)) {
            WearUploadOutcome.SUCCESS
        } else {
            WearUploadOutcome.FAILED
        }
    }

    private fun parseUuid(value: String): Uuid? =
        try {
            Uuid.parse(value)
        } catch (e: IllegalArgumentException) {
            Napier.w("Invalid UUID in pending upload: $value", e)
            null
        }
}

internal enum class WearUploadOutcome {
    SUCCESS,
    SKIPPED,
    FAILED,

    /** Sent in full; the outbox entry stays until the phone confirms it. */
    AWAITING_ACK,
}
