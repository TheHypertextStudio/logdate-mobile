package app.logdate.client.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Tests [MessageWatchNoteAcknowledger], which tells the watch a note is stored on the phone.
 */
class MessageWatchNoteAcknowledgerTest {
    private val noteId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `acknowledging a note messages the watch on the note ack path`() =
        runTest {
            val sentPaths = mutableListOf<String>()
            val acknowledger = MessageWatchNoteAcknowledger { path -> sentPaths.add(path) }

            acknowledger.acknowledge(noteId)

            assertEquals(listOf("/logdate/notes/550e8400-e29b-41d4-a716-446655440000/ack"), sentPaths)
        }

    @Test
    fun `a versioned acknowledgement echoes the transfer identifier`() =
        runTest {
            val sentPaths = mutableListOf<String>()
            val acknowledger = MessageWatchNoteAcknowledger { path -> sentPaths.add(path) }
            val token = Uuid.random().toString()

            acknowledger.acknowledge(noteId, token)

            assertEquals(listOf("/logdate/notes/$noteId/ack/$token"), sentPaths)
        }

    @Test
    fun `an undeliverable acknowledgement does not fail the caller`() =
        runTest {
            val acknowledger = MessageWatchNoteAcknowledger { false }

            acknowledger.acknowledge(noteId)
        }
}
