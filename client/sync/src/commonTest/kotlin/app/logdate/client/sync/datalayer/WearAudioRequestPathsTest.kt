package app.logdate.client.sync.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for [WearAudioRequestPaths].
 *
 * Verifies the path construction and parsing logic used for transferring audio
 * data between the phone and Wear OS device, ensuring correct identification
 * of audio transfer and request paths.
 */
class WearAudioRequestPathsTest {
    private val noteId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `audio transfer path uses note id`() {
        assertEquals(
            "/logdate/notes/550e8400-e29b-41d4-a716-446655440000/audio",
            WearAudioRequestPaths.audioTransferPath(noteId),
        )
    }

    @Test
    fun `audio request path uses note id`() {
        assertEquals(
            "/logdate/notes/550e8400-e29b-41d4-a716-446655440000/audio/request",
            WearAudioRequestPaths.audioRequestPath(noteId),
        )
    }

    @Test
    fun `request path detection only matches audio request paths`() {
        assertTrue(WearAudioRequestPaths.isAudioRequestPath(WearAudioRequestPaths.audioRequestPath(noteId)))
        assertFalse(WearAudioRequestPaths.isAudioRequestPath(WearAudioRequestPaths.audioTransferPath(noteId)))
        assertFalse(WearAudioRequestPaths.isAudioRequestPath("/logdate/sync/request"))
    }

    @Test
    fun `note id can be parsed from audio request path`() {
        assertEquals(noteId, WearAudioRequestPaths.noteIdFromAudioRequestPath(WearAudioRequestPaths.audioRequestPath(noteId)))
    }

    @Test
    fun `ack path uses note id`() {
        assertEquals(
            "/logdate/notes/550e8400-e29b-41d4-a716-446655440000/ack",
            WearAudioRequestPaths.noteAckPath(noteId),
        )
    }

    @Test
    fun `ack path detection only matches ack paths`() {
        assertTrue(WearAudioRequestPaths.isNoteAckPath(WearAudioRequestPaths.noteAckPath(noteId)))
        assertFalse(WearAudioRequestPaths.isNoteAckPath(WearAudioRequestPaths.audioTransferPath(noteId)))
        assertFalse(WearAudioRequestPaths.isNoteAckPath(WearAudioRequestPaths.audioRequestPath(noteId)))
        assertFalse(WearAudioRequestPaths.isNoteAckPath("/logdate/sync/request"))
    }

    @Test
    fun `note id can be parsed from ack path`() {
        assertEquals(noteId, WearAudioRequestPaths.noteIdFromAckPath(WearAudioRequestPaths.noteAckPath(noteId)))
    }

    @Test
    fun `versioned acknowledgement preserves note and transfer identities`() {
        val transferId = Uuid.random().toString()
        val path = WearAudioRequestPaths.noteAckPath(noteId, transferId)

        assertTrue(WearAudioRequestPaths.isNoteAckPath(path))
        assertEquals(noteId, WearAudioRequestPaths.noteIdFromAckPath(path))
        assertEquals(transferId, WearAudioRequestPaths.transferIdFromAckPath(path))
        assertFalse(WearAudioRequestPaths.isNoteAckPath("/logdate/notes/$noteId/ack/not-a-transfer"))
    }

    @Test
    fun `transfer path detection only matches audio transfer paths`() {
        assertTrue(WearAudioRequestPaths.isAudioTransferPath(WearAudioRequestPaths.audioTransferPath(noteId)))
        assertFalse(WearAudioRequestPaths.isAudioTransferPath(WearAudioRequestPaths.audioRequestPath(noteId)))
        assertFalse(WearAudioRequestPaths.isAudioTransferPath(WearAudioRequestPaths.noteAckPath(noteId)))
    }

    @Test
    fun `note id can be parsed from audio transfer path`() {
        assertEquals(noteId, WearAudioRequestPaths.noteIdFromAudioTransferPath(WearAudioRequestPaths.audioTransferPath(noteId)))
    }
}
