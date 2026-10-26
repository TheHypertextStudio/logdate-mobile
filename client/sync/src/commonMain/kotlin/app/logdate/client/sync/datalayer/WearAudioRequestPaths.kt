package app.logdate.client.sync.datalayer

import kotlin.uuid.Uuid

/**
 * Shared Wear Data Layer paths for note audio transfer in both directions and for the phone's
 * acknowledgement that a watch note has been stored.
 */
object WearAudioRequestPaths {
    const val KEY_TRANSFER_ID = "wearTransferId"
    const val KEY_AUDIO_DIGEST = "wearAudioSha256"

    const val SYNC_REQUEST_PATH = "/logdate/sync/request"

    private const val NOTES_PATH_PREFIX = "/logdate/notes"
    private const val AUDIO_SEGMENT = "/audio"
    private const val REQUEST_SUFFIX = "$AUDIO_SEGMENT/request"
    private const val ACK_SUFFIX = "/ack"

    fun audioTransferPath(noteId: Uuid): String = "$NOTES_PATH_PREFIX/$noteId$AUDIO_SEGMENT"

    fun audioRequestPath(noteId: Uuid): String = "$NOTES_PATH_PREFIX/$noteId$REQUEST_SUFFIX"

    /** Sent by the phone once it has stored a watch note and, for audio notes, the audio file. */
    fun noteAckPath(noteId: Uuid): String = "$NOTES_PATH_PREFIX/$noteId$ACK_SUFFIX"

    fun noteAckPath(
        noteId: Uuid,
        transferId: String,
    ): String {
        require(Uuid.parseOrNull(transferId) != null) { "Invalid transfer identifier" }
        return "${noteAckPath(noteId)}/$transferId"
    }

    fun transferIdFromAckPath(path: String): String? = path.substringAfter("$ACK_SUFFIX/", "").takeIf { Uuid.parseOrNull(it) != null }

    fun isAudioRequestPath(path: String): Boolean =
        path.startsWith("$NOTES_PATH_PREFIX/") &&
            path.endsWith(REQUEST_SUFFIX)

    fun isAudioTransferPath(path: String): Boolean =
        path.startsWith("$NOTES_PATH_PREFIX/") &&
            path.endsWith(AUDIO_SEGMENT)

    fun isNoteAckPath(path: String): Boolean =
        path.startsWith("$NOTES_PATH_PREFIX/") &&
            (path.endsWith(ACK_SUFFIX) || transferIdFromAckPath(path) != null)

    fun noteIdFromAudioRequestPath(path: String): Uuid {
        require(isAudioRequestPath(path)) { "Invalid audio request path: $path" }
        return noteIdFrom(path, REQUEST_SUFFIX)
    }

    fun noteIdFromAudioTransferPath(path: String): Uuid {
        require(isAudioTransferPath(path)) { "Invalid audio transfer path: $path" }
        return noteIdFrom(path, AUDIO_SEGMENT)
    }

    fun noteIdFromAckPath(path: String): Uuid {
        require(isNoteAckPath(path)) { "Invalid note ack path: $path" }
        return noteIdFrom(path.substringBefore("$ACK_SUFFIX/"), ACK_SUFFIX)
    }

    private fun noteIdFrom(
        path: String,
        suffix: String,
    ): Uuid = Uuid.parse(path.removePrefix("$NOTES_PATH_PREFIX/").removeSuffix(suffix))
}
