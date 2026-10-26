package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests [WatchNoteIngestor], which turns notes and audio bytes arriving from the watch into phone
 * notes that are queued for cloud backup.
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
                Unit
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
    fun `different metadata for an existing audio note is retained without a false acknowledgement`() =
        runTest {
            val note = watchAudioNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))
            ingestor.onAudioBytes(note.uid, bytes("m4a"))
            val incoming = mapper.toDataMap(note.copy(durationMs = note.durationMs + 1))

            ingestor.onNoteMetadata(incoming)

            assertEquals(listOf(note.uid), acknowledger.acknowledged)
            assertEquals(incoming, store.stashedMetadata(note.uid))
            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
        }

    @Test
    fun `versioned metadata waits for matching bytes and echoes its transfer token`() =
        runTest {
            val note = watchAudioNote()
            val token = Uuid.random().toString()
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            val metadata =
                mapper.toDataMap(note) +
                    mapOf(
                        WearAudioRequestPaths.KEY_TRANSFER_ID to token,
                        WearAudioRequestPaths.KEY_AUDIO_DIGEST to expected,
                    )
            ingestor.onNoteMetadata(metadata)
            ingestor.onAudioBytes(note.uid, bytes("old"))
            assertTrue(acknowledger.acknowledged.isEmpty())
            assertFalse(note.uid in stored)

            ingestor.onAudioBytes(note.uid, bytes("abc"))

            assertEquals(listOf<String?>(token), acknowledger.transferIds)
            assertTrue(note.uid in stored)
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

            coVerify(exactly = 1) { notesRepository.create(note) }
            assertEquals(listOf<JournalNote>(note), notified)
            assertTrue(acknowledger.acknowledged.isEmpty())
        }

    @Test
    fun `redelivered text note updates the stored copy without queuing another backup`() =
        runTest {
            val note = watchTextNote()
            ingestor.onNoteMetadata(mapper.toDataMap(note))

            ingestor.onNoteMetadata(mapper.toDataMap(note))

            coVerify(exactly = 1) { notesRepository.create(any<JournalNote>()) }
            coVerify(exactly = 1) { notesRepository.createFromSync(note) }
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

            coVerify(exactly = 1) { notesRepository.create(other) }
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

            coVerify(exactly = 1) { notesRepository.create(other) }
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

    private class RecordingAcknowledger : WatchNoteAcknowledger {
        val acknowledged = mutableListOf<Uuid>()
        val transferIds = mutableListOf<String?>()

        override suspend fun acknowledge(
            noteId: Uuid,
            transferId: String?,
        ) {
            transferIds += transferId
            acknowledge(noteId)
        }

        override suspend fun acknowledge(noteId: Uuid) {
            acknowledged += noteId
        }
    }

    private class InMemoryWatchAudioStore : WatchAudioStore {
        private val audio = mutableMapOf<Uuid, ByteArray>()
        private val metadata = mutableMapOf<Uuid, Map<String, String>>()
        var failNextWrite = false

        fun audioText(noteId: Uuid): String? = audio[noteId]?.decodeToString()

        override fun audioPath(noteId: Uuid): String = "/phone/audio_notes/wear_$noteId.m4a"

        override fun hasAudio(noteId: Uuid): Boolean = noteId in audio

        override suspend fun matchesAudioDigest(
            noteId: Uuid,
            expectedDigest: String,
        ): Boolean =
            audio[noteId]?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expectedDigest
            } ?: false

        var writeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var writes = 0

        override suspend fun writeAudio(
            noteId: Uuid,
            source: InputStream,
        ): Boolean {
            writeGate?.await()
            writes++
            if (failNextWrite) {
                failNextWrite = false
                return false
            }
            audio[noteId] = source.readBytes()
            return true
        }

        override suspend fun stashMetadata(
            noteId: Uuid,
            data: Map<String, String>,
        ) {
            metadata[noteId] = data
        }

        override suspend fun stashedMetadata(noteId: Uuid): Map<String, String>? = metadata[noteId]

        override suspend fun clearMetadata(noteId: Uuid) {
            metadata.remove(noteId)
        }

        override suspend fun discard(noteId: Uuid) {
            audio.remove(noteId)
            metadata.remove(noteId)
        }

        private val deleted = mutableSetOf<Uuid>()

        override suspend fun markDeleted(noteId: Uuid) {
            deleted += noteId
        }

        override suspend fun isDeleted(noteId: Uuid): Boolean = noteId in deleted
    }
}
