package app.logdate.client.data.transcription

import app.logdate.client.data.fakes.FakeAudioNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionSegmentEntity
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.client.media.audio.transcription.TranscriptionManager
import app.logdate.client.media.audio.transcription.TranscriptionPriority
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import app.logdate.client.repository.transcription.TranscriptionStatus as WorkStatus

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstTranscriptionRepositoryTest {
    private val now = Instant.parse("2026-09-01T12:00:00Z")

    @Test
    fun `remote work persisted before upgrade cannot start recognition`() =
        runTest {
            val id = Uuid.random()
            val uri = "https://example.test/original.m4a"
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id).copy(contentUri = uri)) }
            val rows = FakeTranscriptionDao()
            val repository = OfflineFirstTranscriptionRepository(rows, notes, FakeTranscriptionManager(true), now = { now })
            repository.updateTranscription(id, null, WorkStatus.IN_PROGRESS)
            assertEquals(null, repository.beginTranscription(id, uri))
            assertEquals(TranscriptionStatus.PENDING, rows.getTranscriptionByNoteId(id)?.status)
        }

    @Test
    fun `lost work is recovered after its lease without another database emission`() =
        runTest {
            var clock = now
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val rows = FakeTranscriptionDao()
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(rows, notes, manager, now = { clock })
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            repository.beginTranscription(id, audioNote(id).contentUri)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
            clock += 11.minutes
            advanceTimeBy(10.minutes.inWholeMilliseconds + 1)
            runCurrent()
            assertEquals(2, manager.enqueueCount)
            assertEquals(TranscriptionStatus.PENDING, rows.getTranscriptionByNoteId(id)?.status)
            assertEquals(TranscriptionPriority.RECOVERY, manager.priorities.last())
        }

    @Test
    fun `periodic recovery does not repeatedly restart terminal failures`() =
        runTest {
            var clock = now
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val manager = FakeTranscriptionManager(false)
            val repository = OfflineFirstTranscriptionRepository(FakeTranscriptionDao(), notes, manager, now = { clock })
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
            clock += 31.minutes
            advanceTimeBy(30.minutes.inWholeMilliseconds + 1)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
        }

    @Test
    fun `live words attach when the recording path becomes an owned media reference`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao()
            val references =
                StoredMediaReferences {
                    it.removePrefix("file://").replace("/install/audio_notes/", "logdate-media://recordings/")
                }
            val repository =
                OfflineFirstTranscriptionRepository(
                    FakeTranscriptionDao(),
                    notes,
                    FakeTranscriptionManager(true),
                    now = { now },
                    mediaReferences = references,
                )
            val work = repository.captureRecordingTranscript(id, "/install/audio_notes/live.m4a")
            notes.addNote(audioNote(id).copy(contentUri = "logdate-media://recordings/live.m4a"))
            val document = TranscriptDocument.fromPlainText("Live words", TranscriptDocumentStatus.LISTENING)
            val saved =
                repository.persistRecordingTranscript(
                    work,
                    document,
                    WorkStatus.IN_PROGRESS,
                )
            assertTrue(saved != null)
            assertEquals("Live words", repository.getTranscription(id)?.text)
        }

    @Test
    fun `visible remote audio waits for the original instead of downloading it for recognition`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id).copy(contentUri = "https://example.test/original.m4a")) }
            val rows = FakeTranscriptionDao()
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(rows, notes, manager, now = { now })
            assertFalse(repository.requestTranscription(id))
            assertEquals(0, manager.enqueueCount)
            assertEquals(null, rows.getTranscriptionByNoteId(id))
        }

    @Test
    fun `deferring interrupted work preserves the document and revision`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val rows = FakeTranscriptionDao()
            val repository = OfflineFirstTranscriptionRepository(rows, notes, FakeTranscriptionManager(true), now = { now })
            repository.updateTranscription(id, "Accepted partial words", WorkStatus.IN_PROGRESS)
            val work = repository.beginTranscription(id, audioNote(id).contentUri)!!
            val previous = rows.getTranscriptionByNoteId(id)!!
            assertTrue(repository.deferTranscription(work))
            assertEquals(previous.copy(status = TranscriptionStatus.PENDING), rows.getTranscriptionByNoteId(id))
        }

    @Test
    fun `stale cancellation cannot requeue a newer completed transcript`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val rows = FakeTranscriptionDao()
            val repository = OfflineFirstTranscriptionRepository(rows, notes, FakeTranscriptionManager(true), now = { now })
            repository.requestTranscription(id)
            val work = repository.beginTranscription(id, audioNote(id).contentUri)!!
            repository.acceptSyncedTranscript(id, TranscriptDocument.fromPlainText("New accepted words").copy(revision = 8))
            val newer = rows.getTranscriptionByNoteId(id)
            assertTrue(repository.deferTranscription(work))
            assertEquals(newer, rows.getTranscriptionByNoteId(id))
            assertTrue(repository.failTranscription(work, "PersistenceError"))
            assertEquals(newer, rows.getTranscriptionByNoteId(id))
        }

    @Test
    fun `startup recovery yields to newly saved and explicitly requested audio`() =
        runTest {
            val first = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(first)) }
            val transcriptions = FakeTranscriptionDao()
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertTrue(repository.requestTranscription(first))
            repository.beginTranscription(first, audioNote(first).contentUri)
            val active = transcriptions.getTranscriptionByNoteId(first)
            assertTrue(repository.requestTranscription(first))
            assertEquals(active, transcriptions.getTranscriptionByNoteId(first))
            notes.addNote(audioNote(Uuid.random()))
            runCurrent()
            assertEquals(
                listOf(
                    TranscriptionPriority.RECOVERY,
                    TranscriptionPriority.FOREGROUND,
                    TranscriptionPriority.FOREGROUND,
                    TranscriptionPriority.FOREGROUND,
                ),
                manager.priorities,
            )
        }

    @Test
    fun `failed transcription retries once each repository startup`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val transcriptions =
                FakeTranscriptionDao().apply {
                    insertTranscription(transcription(id, TranscriptionStatus.FAILED, now))
                }
            val manager = FakeTranscriptionManager(false)
            repeat(2) { attempt ->
                val repository = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
                repository.startAutomaticTranscriptions(backgroundScope)
                runCurrent()
                assertEquals(attempt + 1, manager.enqueueCount)
            }
        }

    @Test
    fun `invalid completed document is regenerated instead of suppressing work`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val transcriptions =
                FakeTranscriptionDao().apply {
                    insertTranscription(transcription(id, TranscriptionStatus.COMPLETED, now).copy(documentJson = "{}"))
                }
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
            assertEquals(TranscriptionStatus.PENDING, transcriptions.getTranscriptionByNoteId(id)?.status)
        }

    @Test
    fun `replacing audio invalidates its former completed transcript and schedules the new revision`() =
        runTest {
            val id = Uuid.random()
            val note = audioNote(id)
            val notes = FakeAudioNoteDao().apply { addNote(note) }
            val transcriptions = FakeTranscriptionDao()
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            repository.acceptSyncedTranscript(id, TranscriptDocument.fromPlainText("Former recording"))
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(0, manager.enqueueCount)
            notes.addNote(note.copy(contentUri = "replacement.m4a", durationMs = note.durationMs + 1000))
            runCurrent()
            assertEquals(1, manager.enqueueCount)
            assertEquals(null, repository.getTranscription(id)?.transcriptDocument)
        }

    @Test
    fun `same URI with changed duration is a new media revision after restart`() =
        runTest {
            val id = Uuid.random()
            val note = audioNote(id)
            val notes = FakeAudioNoteDao().apply { addNote(note) }
            val transcriptions = FakeTranscriptionDao()
            val manager = FakeTranscriptionManager(true)
            val first = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            first.acceptSyncedTranscript(id, TranscriptDocument.fromPlainText("Former recording"))
            notes.addNote(note.copy(durationMs = note.durationMs + 1000))
            val reopened = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            reopened.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
        }

    @Test
    fun `recording refinements renew ownership but cannot replace an incoming transcript`() =
        runTest {
            val id = Uuid.random()
            val note = audioNote(id)
            val notes = FakeAudioNoteDao()
            val repository =
                OfflineFirstTranscriptionRepository(FakeTranscriptionDao(), notes, FakeTranscriptionManager(true), now = { now })
            val recording = repository.captureRecordingTranscript(id, note.contentUri)
            notes.addNote(note)
            val refined =
                repository.persistRecordingTranscript(
                    recording,
                    TranscriptDocument.fromPlainText("First pass"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.IN_PROGRESS,
                )!!
            assertTrue(refined.transcriptRevision > recording.transcriptRevision)
            val final =
                repository.persistRecordingTranscript(
                    refined,
                    TranscriptDocument.fromPlainText("Final pass"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                )!!
            assertEquals("Final pass", repository.getTranscription(id)?.text)
            val incoming = TranscriptDocument.fromPlainText("Newer synced words").copy(revision = 12)
            repository.acceptSyncedTranscript(id, incoming)
            assertEquals(
                null,
                repository.persistRecordingTranscript(
                    final,
                    TranscriptDocument.fromPlainText("Delayed refinement"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
            assertEquals(incoming, repository.getTranscription(id)?.transcriptDocument)
        }

    @Test
    fun `delayed recording transcript cannot attach to replaced media`() =
        runTest {
            val id = Uuid.random()
            val note = audioNote(id)
            val notes = FakeAudioNoteDao().apply { addNote(note) }
            val repository =
                OfflineFirstTranscriptionRepository(FakeTranscriptionDao(), notes, FakeTranscriptionManager(true), now = { now })
            val work = repository.captureRecordingTranscript(id, note.contentUri)
            notes.updateContentUri(id, "replacement.m4a")
            assertEquals(
                null,
                repository.persistRecordingTranscript(
                    work,
                    TranscriptDocument.fromPlainText("Wrong recording"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
            assertEquals(null, repository.getTranscription(id))
        }

    @Test
    fun `repository restart resumes an interrupted recent transcription once`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val transcriptions =
                FakeTranscriptionDao().apply {
                    insertTranscription(transcription(id, TranscriptionStatus.IN_PROGRESS, now))
                }
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
        }

    @Test
    fun `repository lifecycle schedules existing and newly imported audio once`() =
        runTest {
            val first = Uuid.random()
            val second = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(first)) }
            val transcriptions = FakeTranscriptionDao()
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(transcriptions, notes, manager, now = { now })
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(1, manager.enqueueCount)
            notes.addNote(audioNote(second))
            runCurrent()
            assertEquals(2, manager.enqueueCount)
            repository.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(2, manager.enqueueCount)
        }

    @Test
    fun `obsolete recognition cannot replace a transcript accepted during work`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val repository =
                OfflineFirstTranscriptionRepository(FakeTranscriptionDao(), notes, FakeTranscriptionManager(true), now = { now })
            repository.requestTranscription(id)
            val work = repository.beginTranscription(id, audioNote(id).contentUri)!!
            val incoming = TranscriptDocument.fromPlainText("New accepted transcript").copy(revision = 8)
            assertTrue(repository.acceptSyncedTranscript(id, incoming))
            assertTrue(
                repository.completeTranscription(
                    work,
                    TranscriptDocument.fromPlainText("Stale words"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
            assertEquals("New accepted transcript", repository.getTranscription(id)?.text)
        }

    @Test
    fun `obsolete recognition cannot write to replaced media or deleted audio`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val repository =
                OfflineFirstTranscriptionRepository(FakeTranscriptionDao(), notes, FakeTranscriptionManager(true), now = { now })
            repository.requestTranscription(id)
            val work = repository.beginTranscription(id, audioNote(id).contentUri)!!
            notes.updateContentUri(id, "replacement.m4a")
            assertTrue(
                repository.completeTranscription(
                    work,
                    TranscriptDocument.fromPlainText("Stale words"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
            assertEquals(null, repository.getTranscription(id)?.text)
            notes.removeNote(id)
            assertTrue(
                repository.completeTranscription(
                    work,
                    TranscriptDocument.fromPlainText("Deleted words"),
                    app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
            assertEquals(null, repository.getTranscription(id)?.text)
        }

    @Test
    fun `completed empty transcript is durable and never scheduled again`() =
        runTest {
            val id = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(id)) }
            val manager = FakeTranscriptionManager(true)
            val repository = OfflineFirstTranscriptionRepository(FakeTranscriptionDao(), notes, manager, now = { now })
            assertTrue(repository.updateTranscription(id, "", app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED))
            assertTrue(repository.getTranscription(id)?.transcriptDocument?.isFinal == true)
            assertTrue(repository.requestTranscription(id))
            assertEquals(0, manager.enqueueCount)
        }

    @Test
    fun `a rejected enqueue is persisted as failed instead of stranded pending`() =
        runTest {
            val noteId = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(noteId)) }
            val transcriptions = FakeTranscriptionDao()
            val repository =
                OfflineFirstTranscriptionRepository(
                    transcriptionDao = transcriptions,
                    audioNoteDao = notes,
                    transcriptionManager = FakeTranscriptionManager(enqueueResult = false),
                    now = { now },
                )

            assertFalse(repository.requestTranscription(noteId))
            assertEquals(
                TranscriptionStatus.FAILED,
                transcriptions.getTranscriptionByNoteId(noteId)?.status,
            )
        }

    @Test
    fun `a stale in-progress transcription is requeued`() =
        runTest {
            val noteId = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(noteId)) }
            val transcriptions =
                FakeTranscriptionDao().apply {
                    insertTranscription(
                        transcription(
                            noteId = noteId,
                            status = TranscriptionStatus.IN_PROGRESS,
                            lastUpdated = now - 11.minutes,
                        ),
                    )
                }
            val manager = FakeTranscriptionManager(enqueueResult = true)
            val repository =
                OfflineFirstTranscriptionRepository(
                    transcriptionDao = transcriptions,
                    audioNoteDao = notes,
                    transcriptionManager = manager,
                    now = { now },
                )

            assertTrue(repository.requestTranscription(noteId))
            assertEquals(1, manager.enqueueCount)
            assertEquals(
                TranscriptionStatus.PENDING,
                transcriptions.getTranscriptionByNoteId(noteId)?.status,
            )
        }

    @Test
    fun `an update that touches no row is reported as a persistence failure`() =
        runTest {
            val noteId = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(noteId)) }
            val transcriptions =
                FakeTranscriptionDao(updateRowCount = 0).apply {
                    insertTranscription(transcription(noteId, TranscriptionStatus.IN_PROGRESS, now))
                }
            val repository =
                OfflineFirstTranscriptionRepository(
                    transcriptionDao = transcriptions,
                    audioNoteDao = notes,
                    transcriptionManager = FakeTranscriptionManager(true),
                    now = { now },
                )

            assertFalse(
                repository.updateTranscription(
                    noteId = noteId,
                    text = "words",
                    status = app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
        }

    @Test
    fun `a scheduling failure that cannot be recorded is reported without escaping`() =
        runTest {
            val noteId = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(noteId)) }
            val transcriptions =
                FakeTranscriptionDao(throwOnUpdate = true).apply {
                    insertTranscription(transcription(noteId, TranscriptionStatus.PENDING, now))
                }
            val repository =
                OfflineFirstTranscriptionRepository(
                    transcriptionDao = transcriptions,
                    audioNoteDao = notes,
                    transcriptionManager = FakeTranscriptionManager(false),
                    now = { now },
                )

            assertFalse(repository.requestTranscription(noteId))
        }

    @Test
    fun `a structured transcript insert failure is returned to the realtime retry path`() =
        runTest {
            val noteId = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(noteId)) }
            val repository =
                OfflineFirstTranscriptionRepository(
                    transcriptionDao = FakeTranscriptionDao(throwOnInsert = true),
                    audioNoteDao = notes,
                    transcriptionManager = FakeTranscriptionManager(true),
                    now = { now },
                )

            assertFalse(
                repository.updateTranscriptDocument(
                    noteId = noteId,
                    document = TranscriptDocument.fromPlainText("words"),
                    status = app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
        }

    @Test
    fun `a plain transcript insert failure is returned to the realtime retry path`() =
        runTest {
            val noteId = Uuid.random()
            val notes = FakeAudioNoteDao().apply { addNote(audioNote(noteId)) }
            val repository =
                OfflineFirstTranscriptionRepository(
                    transcriptionDao = FakeTranscriptionDao(throwOnInsert = true),
                    audioNoteDao = notes,
                    transcriptionManager = FakeTranscriptionManager(true),
                    now = { now },
                )

            assertFalse(
                repository.updateTranscription(
                    noteId = noteId,
                    text = "words",
                    status = app.logdate.client.repository.transcription.TranscriptionStatus.COMPLETED,
                ),
            )
        }

    private fun audioNote(noteId: Uuid) =
        AudioNoteEntity(
            uid = noteId,
            contentUri = "file:///recording.m4a",
            created = now,
            lastUpdated = now,
        )

    private fun transcription(
        noteId: Uuid,
        status: TranscriptionStatus,
        lastUpdated: Instant,
    ) = TranscriptionEntity(
        noteId = noteId,
        text = null,
        status = status,
        created = lastUpdated,
        lastUpdated = lastUpdated,
    )
}

internal class FakeTranscriptionManager(
    private val enqueueResult: Boolean,
) : TranscriptionManager {
    var enqueueCount = 0
    val priorities = mutableListOf<TranscriptionPriority>()

    override suspend fun enqueueTranscription(
        noteId: Uuid,
        audioUri: String,
        mediaRevision: String,
        priority: TranscriptionPriority,
    ): Boolean {
        priorities += priority
        return enqueueTranscription(noteId, audioUri)
    }

    override suspend fun enqueueTranscription(
        noteId: Uuid,
        audioUri: String,
    ): Boolean {
        enqueueCount += 1
        return enqueueResult
    }

    override suspend fun cancelTranscription(noteId: Uuid): Boolean = true

    override suspend fun cancelAllTranscriptions(): Int = 0
}

internal class FakeTranscriptionDao(
    private val updateRowCount: Int = 1,
    private val throwOnInsert: Boolean = false,
    private val throwOnUpdate: Boolean = false,
) : TranscriptionDao {
    private val values = linkedMapOf<Uuid, TranscriptionEntity>()
    private val flows = mutableMapOf<Uuid, MutableStateFlow<TranscriptionEntity?>>()
    private val allRows = MutableStateFlow<List<TranscriptionEntity>>(emptyList())

    override suspend fun insertTranscription(transcription: TranscriptionEntity): Long {
        if (throwOnInsert) error("insert unavailable")
        values[transcription.noteId] = transcription
        allRows.value = values.values.toList()
        flows.getOrPut(transcription.noteId) { MutableStateFlow(null) }.value = transcription
        return 1
    }

    override suspend fun insertSegments(segments: List<TranscriptionSegmentEntity>) = Unit

    override suspend fun updateTranscription(transcription: TranscriptionEntity): Int {
        if (throwOnUpdate) error("update unavailable")
        if (updateRowCount > 0) {
            values[transcription.noteId] = transcription
            allRows.value = values.values.toList()
            flows.getOrPut(transcription.noteId) { MutableStateFlow(null) }.value = transcription
        }
        return updateRowCount
    }

    override suspend fun getTranscriptionById(id: Uuid): TranscriptionEntity? = values.values.firstOrNull { it.id == id }

    override suspend fun getTranscriptionByNoteId(noteId: Uuid): TranscriptionEntity? = values[noteId]

    override suspend fun getSegmentsByNoteId(noteId: Uuid): List<TranscriptionSegmentEntity> = emptyList()

    override fun observeTranscriptionByNoteId(noteId: Uuid): Flow<TranscriptionEntity?> =
        flows.getOrPut(noteId) { MutableStateFlow(values[noteId]) }

    override fun observeAllTranscriptions(): Flow<List<TranscriptionEntity>> = allRows

    override suspend fun getAllTranscriptions(): List<TranscriptionEntity> = values.values.toList()

    override suspend fun getTranscriptionsByStatus(status: TranscriptionStatus): List<TranscriptionEntity> =
        values.values.filter { it.status == status }

    override suspend fun getActiveTranscriptions(): List<TranscriptionEntity> =
        values.values.filter { it.status == TranscriptionStatus.PENDING || it.status == TranscriptionStatus.IN_PROGRESS }

    override suspend fun updateTranscriptionStatus(
        id: Uuid,
        status: TranscriptionStatus,
        errorMessage: String?,
        timestamp: Instant,
    ): Int = updateById(id) { it.copy(status = status, errorMessage = errorMessage, lastUpdated = timestamp) }

    override suspend fun updateTranscriptionText(
        id: Uuid,
        text: String,
        timestamp: Instant,
    ): Int = updateById(id) { it.copy(text = text, lastUpdated = timestamp) }

    override suspend fun deleteTranscription(id: Uuid): Int {
        val key = values.entries.firstOrNull { it.value.id == id }?.key ?: return 0
        values.remove(key)
        return 1
    }

    override suspend fun deleteTranscriptionByNoteId(noteId: Uuid): Int = if (values.remove(noteId) != null) 1 else 0

    override suspend fun deleteSegmentsByNoteId(noteId: Uuid): Int = 0

    private fun updateById(
        id: Uuid,
        transform: (TranscriptionEntity) -> TranscriptionEntity,
    ): Int {
        val entry = values.entries.firstOrNull { it.value.id == id } ?: return 0
        values[entry.key] = transform(entry.value)
        return 1
    }
}
