package app.logdate.wear.sync

import kotlin.uuid.Uuid

/** Receives the phone's confirmation that a note, and its audio file, are stored on the phone. */
fun interface WearNoteAckHandler {
    suspend fun onNoteAcknowledged(noteId: Uuid)

    suspend fun onNoteAcknowledged(
        noteId: Uuid,
        transferId: String?,
    ) = onNoteAcknowledged(noteId)
}
