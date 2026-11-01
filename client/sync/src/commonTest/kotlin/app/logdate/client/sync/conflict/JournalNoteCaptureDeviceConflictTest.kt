package app.logdate.client.sync.conflict

import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A note's capture device is written once, where the note was captured. A copy that lacks it (an
 * older app version, or a server that predates the field) must never erase it.
 */
class JournalNoteCaptureDeviceConflictTest {
    private val resolver = JournalNoteConflictResolver()
    private val noteId = Uuid.random()
    private val created = Instant.fromEpochMilliseconds(1_710_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_710_000_100_000)

    private fun text(
        content: String,
        capturedOn: CaptureDevice?,
    ) = JournalNote.Text(
        uid = noteId,
        creationTimestamp = created,
        lastUpdated = created,
        content = content,
        capturedOn = capturedOn,
    )

    private fun resolve(
        local: JournalNote,
        remote: JournalNote,
    ) = resolver.resolve(local, remote, created, later)

    @Test
    fun `a remote copy without a device keeps the local device when the content matches`() {
        val resolved = assertIs<ConflictResolution.KeepRemote<JournalNote>>(resolve(text("Walk", CaptureDevice.WATCH), text("Walk", null)))

        assertEquals(CaptureDevice.WATCH, resolved.value.capturedOn)
    }

    @Test
    fun `divergent text keeps the local device without filling the remote original`() {
        val local = text("Walk", CaptureDevice.WATCH)
        val remote = text("Walk by the river", null)
        val review = assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(resolve(local, remote))
        assertEquals(local, review.local)
        assertEquals(remote, review.remote)
        assertEquals(CaptureDevice.WATCH, review.local.capturedOn)
        assertEquals(null, review.remote.capturedOn)
    }

    @Test
    fun `divergent text keeps the remote device without filling the local original`() {
        val local = text("Walk by the river", null)
        val remote = text("Walk", CaptureDevice.WATCH)
        val review = assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(resolve(local, remote))
        assertEquals(local, review.local)
        assertEquals(remote, review.remote)
        assertEquals(null, review.local.capturedOn)
        assertEquals(CaptureDevice.WATCH, review.remote.capturedOn)
    }

    @Test
    fun `both divergent originals retain their own capture device`() {
        val withLocal = resolve(text("Morning", CaptureDevice.WATCH), text("Evening", null))
        val withRemote = resolve(text("Morning", null), text("Evening", CaptureDevice.WATCH))
        val localReview = assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(withLocal)
        val remoteReview = assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(withRemote)
        assertEquals(CaptureDevice.WATCH, localReview.local.capturedOn)
        assertEquals(null, localReview.remote.capturedOn)
        assertEquals(null, remoteReview.local.capturedOn)
        assertEquals(CaptureDevice.WATCH, remoteReview.remote.capturedOn)
    }

    @Test
    fun `an audio note keeps its local device when the remote copy has none`() {
        val local =
            JournalNote.Audio(
                uid = noteId,
                mediaRef = "voice.m4a",
                creationTimestamp = created,
                lastUpdated = created,
                capturedOn = CaptureDevice.WATCH,
            )

        val resolved = assertIs<ConflictResolution.KeepRemote<JournalNote>>(resolve(local, local.copy(capturedOn = null)))
        assertEquals(CaptureDevice.WATCH, resolved.value.capturedOn)
    }
}
