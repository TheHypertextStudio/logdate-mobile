package app.logdate.wear.sync

import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests [WearPhoneMessageHandler], which reacts to messages from the phone: acknowledgements that
 * a note is stored, requests to sync now, and the phone becoming reachable again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearPhoneMessageHandlerTest {
    private val ackHandler = mockk<WearNoteAckHandler>(relaxed = true)
    private val syncManager = mockk<SyncManager>(relaxed = true)
    private val handler = WearPhoneMessageHandler(ackHandler, syncManager)

    @Test
    fun `ack message acknowledges the note`() =
        runTest {
            val noteId = Uuid.random()

            handler.onMessage(WearAudioRequestPaths.noteAckPath(noteId))

            coVerify(exactly = 1) { ackHandler.onNoteAcknowledged(noteId) }
            confirmVerified(syncManager)
        }

    @Test
    fun `versioned ack forwards the transfer identifier`() =
        runTest {
            val noteId = Uuid.random()
            val token = Uuid.random().toString()

            handler.onMessage(WearAudioRequestPaths.noteAckPath(noteId, token))

            coVerify { ackHandler.onNoteAcknowledged(noteId, token) }
        }

    @Test
    fun `ack message with an invalid note id is ignored`() =
        runTest {
            handler.onMessage("/logdate/notes/not-a-uuid/ack")

            confirmVerified(ackHandler)
        }

    @Test
    fun `ack message that fails to record does not propagate`() =
        runTest {
            val noteId = Uuid.random()
            coEvery { ackHandler.onNoteAcknowledged(noteId) } throws IllegalStateException("database closed")

            handler.onMessage(WearAudioRequestPaths.noteAckPath(noteId))

            coVerify(exactly = 1) { ackHandler.onNoteAcknowledged(noteId) }
        }

    @Test
    fun `sync request message sends pending changes`() =
        runTest {
            handler.onMessage(WearAudioRequestPaths.SYNC_REQUEST_PATH)

            coVerify(exactly = 1) { syncManager.fullSync() }
            confirmVerified(ackHandler)
        }

    @Test
    fun `unknown message path does nothing`() =
        runTest {
            handler.onMessage("/logdate/other/thing")

            confirmVerified(ackHandler, syncManager)
        }

    @Test
    fun `phone becoming reachable sends pending changes`() =
        runTest {
            handler.onPhoneReachable()

            coVerify(exactly = 1) { syncManager.fullSync() }
        }

    @Test
    fun `a failed sync does not propagate`() =
        runTest {
            coEvery { syncManager.fullSync() } throws IllegalStateException("no connection")

            handler.onPhoneReachable()

            coVerify(exactly = 1) { syncManager.fullSync() }
        }
}
