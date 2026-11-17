package app.logdate.client.sync

import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.withCapturedOn
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.copyToDigesting
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tests [WatchNoteIngestor], which turns notes and audio bytes arriving from the watch into phone
 * notes that are queued for cloud backup.
 *
 * Metadata and audio bytes travel over different Data Layer channels and can arrive in either
 * order, so the invariants are: a watch audio note is stored only once its file is on the phone,
 * it is queued for backup exactly once, and the watch is told when it is safe to stop retrying.
 */
class WatchNoteIngestorTest {
    private val mapper = NoteDataMapper()
    private val store = InMemoryWatchAudioStore()
    private val acknowledger = RecordingAcknowledger()
    private val notified = mutableListOf<JournalNote>()
    private val stored = mutableMapOf<Uuid, JournalNote>()
    private val notesRepository =
        mockk<SyncableJournalNotesRepository>(relaxed = true).also { repository ->
            coEvery { repository.getNoteById(any()) } answers { stored[firstArg()] }
            coEvery { repository.create(any<JournalNote>()) } answers {
                val note = firstArg<JournalNote>()
                stored[note.uid] = note
                note.uid
            }
            coEvery { repository.removeById(any()) } answers {
                stored.remove(firstArg<Uuid>())
            }
        }
    private val ingestor =
        WatchNoteIngestor(
            notesRepository = notesRepository,
            audioStore = store,
            acknowledger = acknowledger,
            notifier = { notified += it },
            noteDataMapper = mapper,
        )

    @Test
    fun `audio note is not stored until its file arrives`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
            assertTrue(acknowledger.acknowledged.isEmpty())
            assertTrue(notified.isEmpty())
        }

    @Test
    fun `audio note is stored with the phone file path when the file arrives after the metadata`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            val created = stored.getValue(note.uid) as JournalNote.Audio
            assertEquals(store.audioPath(note.uid), created.mediaRef)
            assertEquals(note.durationMs, created.durationMs)
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
            assertEquals(listOf<JournalNote>(created), notified)
            assertEquals("m4a", store.audioText(note.uid))
        }

    @Test
    fun `audio note is stored when the file arrives before the metadata`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onAudioBytes(note.uid, bytes("m4a"))
            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            val created = stored.getValue(note.uid) as JournalNote.Audio
            assertEquals(store.audioPath(note.uid), created.mediaRef)
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
        }

    @Test
    fun `audio note is queued once when metadata and file arrive concurrently`() =
        runTest {
            val note = watchAudioNote()

            listOf(
                async { ingestor.onNoteMetadata(mapper.toDataMap(note)) },
                async { ingestor.onAudioBytes(note.uid, bytes("m4a")) },
            ).awaitAll()

            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
            assertEquals(1, notified.size)
        }

    @Test
    fun `redelivered file for a stored note acknowledges again without storing it twice`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
            assertEquals(listOf(note.uid, note.uid), acknowledger.acknowledged)
            assertEquals(1, notified.size)
        }

    @Test
    fun `redelivered metadata for a stored audio note acknowledges again without storing it twice`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
            assertEquals(listOf(note.uid, note.uid), acknowledger.acknowledged)
            assertEquals(1, notified.size)
        }

    @Test
    fun `failed file write neither stores nor acknowledges the note`() =
        runTest {
            val note = watchAudioNote()
            store.failNextWrite = true
            ingestor.onNoteMetadata(mapper.toDataMap(note))

            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
            assertTrue(acknowledger.acknowledged.isEmpty())
            assertFalse(store.hasAudio(note.uid))
        }

    @Test
    fun `retry after a failed file write completes the note`() =
        runTest {
            val note = watchAudioNote()
            store.failNextWrite = true
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            assertEquals(store.audioPath(note.uid), (stored.getValue(note.uid) as JournalNote.Audio).mediaRef)
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
        }

    @Test
    fun `text note from the watch is stored and queued for backup`() =
        runTest {
            val note = watchTextNote()

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            coVerify(exactly = 1) { notesRepository.create(note.withCapturedOn(CaptureDevice.WATCH)) }
            assertEquals(listOf(note.withCapturedOn(CaptureDevice.WATCH)), notified)
            assertTrue(acknowledger.acknowledged.isEmpty())
        }

    @Test
    fun `redelivered text note updates the stored copy without queuing another backup`() =
        runTest {
            val note = watchTextNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
            coVerify(exactly = 1) { notesRepository.createFromSync(note.withCapturedOn(CaptureDevice.WATCH)) }
            assertEquals(1, notified.size)
        }

    @Test
    fun `redelivered text note is ignored when the repository cannot update from sync`() =
        runTest {
            val plainRepository = mockk<JournalNotesRepository>(relaxed = true)
            val existing = watchTextNote()
            coEvery { plainRepository.getNoteById(existing.uid) } returns existing
            val plainIngestor =
                WatchNoteIngestor(
                    notesRepository = plainRepository,
                    audioStore = store,
                    acknowledger = acknowledger,
                    notifier = { notified += it },
                    noteDataMapper = mapper,
                )

            plainIngestor.onNoteMetadata(mapper.toDataMap(existing))

            coVerify(exactly = 0) { plainRepository.create(any<JournalNote>()) }
            assertTrue(notified.isEmpty())
        }

    @Test
    fun `deleting a stored note removes it through the backup-aware path and discards the phone file`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            ingestor.onNoteDeleted(note.uid)

            coVerify(exactly = 1) { notesRepository.removeById(note.uid) }
            assertNull(stored[note.uid])
            assertFalse(store.hasAudio(note.uid))
        }

    @Test
    fun `deleting a note that never finished arriving clears what was received and removes nothing`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))

            ingestor.onNoteDeleted(note.uid)
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            coVerify(exactly = 0) { notesRepository.removeById(any()) }
            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
        }

    @Test
    fun `a note deleted before its metadata arrives is not stored when the metadata shows up late`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteDeleted(note.uid)

            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
            assertTrue(acknowledger.acknowledged.isEmpty())
            assertTrue(notified.isEmpty())
            assertFalse(store.hasAudio(note.uid))
        }

    @Test
    fun `audio for a deleted note is not written even when it arrives first`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteDeleted(note.uid)

            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            assertFalse(store.hasAudio(note.uid))
        }

    @Test
    fun `a text note deleted before it arrives is not stored`() =
        runTest {
            val note = watchTextNote()
            ingestor.onNoteDeleted(note.uid)

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
            assertTrue(notified.isEmpty())
        }

    @Test
    fun `deleting a note does not affect other notes that arrive later`() =
        runTest {
            val deleted = watchTextNote()
            val other = watchTextNote()
            ingestor.onNoteDeleted(deleted.uid)

            ingestor.onNoteMetadata(mapper.toDataMap(other))

            coVerify(exactly = 1) { notesRepository.create(other.withCapturedOn(CaptureDevice.WATCH)) }
        }

    @Test
    fun `a slow audio transfer for one note does not block another note`() =
        runTest {
            val slow = watchAudioNote()
            val other = watchTextNote()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            store.writeGate = gate
            val transfer = async { ingestor.onAudioBytes(slow.uid, bytes("m4a")) }
            runCurrent()

            ingestor.onNoteMetadata(mapper.toDataMap(other))

            coVerify(exactly = 1) { notesRepository.create(other.withCapturedOn(CaptureDevice.WATCH)) }
            gate.complete(Unit)
            transfer.await()
        }

    @Test
    fun `two deliveries of the same audio are written one after the other`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            val first = async { ingestor.onAudioBytes(note.uid, bytes("m4a")) }
            val second = async { ingestor.onAudioBytes(note.uid, bytes("m4a")) }

            listOf(first, second).awaitAll()

            assertEquals(1, store.writes)
            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
        }

    @Test
    fun `a note's lock is released once its ingestion finishes`() =
        runTest {
            val notes = List(3) { watchAudioNote() }
            notes.forEach { note ->
                ingestor.onNoteMetadata(mapper.toDataMap(note))
                ingestor.onAudioBytes(note.uid, bytes("m4a"))
                ingestor.onNoteDeleted(note.uid)
            }

            assertEquals(0, ingestor.activeNoteLockCount)
        }

    @Test
    fun `a lock stays while a transfer for its note is running and goes when it ends`() =
        runTest {
            val note = watchAudioNote()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            store.writeGate = gate
            val transfer = async { ingestor.onAudioBytes(note.uid, bytes("m4a")) }
            runCurrent()
            assertEquals(1, ingestor.activeNoteLockCount)

            gate.complete(Unit)
            transfer.await()

            assertEquals(0, ingestor.activeNoteLockCount)
        }

    @Test
    fun `metadata without a note payload is rejected`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { ingestor.onNoteMetadata(emptyMap()) }
        }

    @Test
    fun `audio matching what the watch measured is stored`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onNoteMetadata(withDigestOf("complete audio", note))
            ingestor.onAudioBytes(note.uid, bytes("complete audio"))

            assertTrue(stored.containsKey(note.uid))
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
        }

    @Test
    fun `audio cut short on the way is neither stored nor acknowledged`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onNoteMetadata(withDigestOf("complete audio", note))
            ingestor.onAudioBytes(note.uid, bytes("complete"))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
            assertTrue(acknowledger.acknowledged.isEmpty())
            assertFalse(store.hasAudio(note.uid))
        }

    @Test
    fun `audio cut short is stored once the watch resends it whole`() =
        runTest {
            val note = watchAudioNote()
            val metadata = withDigestOf("complete audio", note)
            ingestor.onNoteMetadata(metadata)
            ingestor.onAudioBytes(note.uid, bytes("complete"))

            ingestor.onNoteMetadata(metadata)
            ingestor.onAudioBytes(note.uid, bytes("complete audio"))

            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
        }

    @Test
    fun `audio that arrived first is checked when its metadata follows`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onAudioBytes(note.uid, bytes("complete"))
            ingestor.onNoteMetadata(withDigestOf("complete audio", note))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
            assertFalse(store.hasAudio(note.uid))
        }

    @Test
    fun `a digest that cannot be read rejects the audio`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onNoteMetadata(mapper.toDataMap(note) + (NoteDataMapper.KEY_AUDIO_SIZE to "unknown"))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            coVerify(exactly = 0) { notesRepository.create(any<JournalNote>()) }
        }

    private fun withDigestOf(
        audio: String,
        note: JournalNote.Audio,
    ): Map<String, String> =
        NoteDataMapper.withAudioDigest(
            mapper.toDataMap(note),
            ByteArrayInputStream(audio.encodeToByteArray()).copyToDigesting(ByteArrayOutputStream()),
        )

    @Test
    fun `the acknowledgement names the version of the note the phone stored`() =
        runTest {
            val note = watchAudioNote()

            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            assertEquals(listOf(note.lastUpdated), acknowledger.versions)
        }

    @Test
    fun `a repeated delivery is acknowledged with the version already stored`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))

            ingestor.onNoteMetadata(mapper.toDataMap(note.copy(lastUpdated = note.lastUpdated + 5.seconds)))

            assertEquals(listOf(note.lastUpdated, note.lastUpdated), acknowledger.versions)
        }

    @Test
    fun `a different audio edit is not acknowledged by the already stored note`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("audio"))
            ingestor.onNoteMetadata(mapper.toDataMap(note.copy(durationMs = note.durationMs + 1000)))
            ingestor.onAudioBytes(note.uid, bytes("different audio"))
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
            assertTrue(store.stashedMetadata(note.uid) != null)
            assertEquals("audio", store.audioText(note.uid))
        }

    @Test
    fun `a mismatched digest cannot acknowledge an already stored audio note`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(withDigestOf("audio", note))
            ingestor.onAudioBytes(note.uid, bytes("audio"))
            ingestor.onNoteMetadata(withDigestOf("different audio", note))
            assertEquals(listOf(note.uid), acknowledger.acknowledged)
            assertTrue(store.stashedMetadata(note.uid) != null)
        }

    private fun bytes(text: String): InputStream = ByteArrayInputStream(text.encodeToByteArray())

    private fun watchAudioNote(id: Uuid = Uuid.random()): JournalNote.Audio {
        val now = Clock.System.now()
        return JournalNote.Audio(
            mediaRef = "/data/user/0/studio.hypertext.logdate/files/audio_notes/recording_$id.m4a",
            durationMs = 42_000,
            uid = id,
            creationTimestamp = now,
            lastUpdated = now,
        )
    }

    private fun watchTextNote(id: Uuid = Uuid.random()): JournalNote.Text {
        val now = Clock.System.now()
        return JournalNote.Text(uid = id, creationTimestamp = now, lastUpdated = now, content = "From the watch")
    }
}
