package app.logdate.wear.sync

import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import io.github.aakira.napier.Napier

/**
 * Reacts to the phone: a note acknowledgement clears it from the watch's outbox, and a sync
 * request or the phone becoming reachable again sends whatever is still waiting.
 */
class WearPhoneMessageHandler(
    private val ackHandler: WearNoteAckHandler,
    private val syncManager: SyncManager,
) {
    suspend fun onMessage(path: String) {
        when {
            WearAudioRequestPaths.isNoteAckPath(path) -> acknowledge(path)
            path == WearAudioRequestPaths.SYNC_REQUEST_PATH -> sendPendingChanges()
            else -> Napier.d("Unknown message path: $path")
        }
    }

    suspend fun onPhoneReachable() = sendPendingChanges()

    private suspend fun acknowledge(path: String) {
        val noteId =
            try {
                WearAudioRequestPaths.noteIdFromAckPath(path)
            } catch (e: IllegalArgumentException) {
                Napier.w("Invalid note ID in ack path: $path", e)
                return
            }
        try {
            val transferId = WearAudioRequestPaths.transferIdFromAckPath(path)
            if (transferId == null) ackHandler.onNoteAcknowledged(noteId) else ackHandler.onNoteAcknowledged(noteId, transferId)
        } catch (e: Exception) {
            Napier.w("Failed to record the phone's acknowledgement of note $noteId", e)
        }
    }

    private suspend fun sendPendingChanges() {
        try {
            syncManager.fullSync()
        } catch (e: Exception) {
            Napier.w("Failed to send pending changes to the phone", e)
        }
    }
}
