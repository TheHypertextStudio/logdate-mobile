package app.logdate.client.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.data.journals.OfflineFirstJournalContentRepository
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.data.notes.EmptyNotePlaceResolver
import app.logdate.client.data.notes.OfflineFirstJournalNotesRepository
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Instrumented tests verifying that the Room data layer preserves every field
 * [ExportArchiveUseCase][app.logdate.client.domain.export.archive.ExportArchiveUseCase] reads when it writes an archive.
 *
 * These tests sit below the export/restore use cases: they write directly through
 * the real [OfflineFirstJournalRepository] and [OfflineFirstJournalNotesRepository]
 * implementations backed by a real in-memory Room database, then read the data
 * back through the same repositories and assert field-level equivalence.
 *
 * If a Room entity is missing a column, a type converter drops precision, or a DAO
 * query filters rows incorrectly, these tests catch it independently of any
 * serialization or archive logic.
 */
@RunWith(AndroidJUnit4::class)
class ExportDataLayerRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LogDateDatabase

    private lateinit var journalRepo: OfflineFirstJournalRepository
    private lateinit var notesRepo: OfflineFirstJournalNotesRepository
    private lateinit var contentRepo: OfflineFirstJournalContentRepository

    @Before
    fun setup() {
        val dispatcher = Dispatchers.Unconfined
        val syncMeta = InMemorySyncMetadataService()
        val draftRepo = InMemoryDraftRepository()

        db =
            Room
                .inMemoryDatabaseBuilder(context, LogDateDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        journalRepo =
            OfflineFirstJournalRepository(
                journalDao = db.journalDao(),
                remoteDataSource = NoOpRemoteJournalDataSource(),
                draftRepository = draftRepo,
                syncMetadataService = syncMeta,
                dispatcher = dispatcher,
                externalScope = kotlinx.coroutines.CoroutineScope(dispatcher),
            )

        notesRepo =
            OfflineFirstJournalNotesRepository(
                textNoteDao = db.textNoteDao(),
                imageNoteDao = db.imageNoteDao(),
                audioNoteDao = db.audioNoteDao(),
                videoNoteDao = db.videoNoteDao(),
                journalContentDao = db.journalContentDao(),
                journalRepository = journalRepo,
                mediaCaptionDao = db.mediaCaptionDao(),
                notePlaceResolver = EmptyNotePlaceResolver,
                syncMetadataService = syncMeta,
                syncScope = kotlinx.coroutines.CoroutineScope(dispatcher),
            )

        contentRepo =
            OfflineFirstJournalContentRepository(
                journalContentDao = db.journalContentDao(),
                journalRepository = journalRepo,
                journalNotesRepository = notesRepo,
                syncMetadataService = syncMeta,
                dispatcher = dispatcher,
            )
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    // ── Journal fields ───────────────────────────────────────────────────

    @Test
    fun `room journal all fields round trip`() =
        runTest {
            val now = now()
            val journal =
                Journal(
                    id = Uuid.random(),
                    title = "Trip to Japan 🇯🇵",
                    description = "Two weeks in Tokyo and Kyoto",
                    created = now - 48.hours,
                    lastUpdated = now,
                )
            journalRepo.create(journal)

            val restored = journalRepo.getJournalById(journal.id)
            assertNotNull(restored, "Journal not found in Room after create")
            assertEquals(journal.id, restored.id, "id")
            assertEquals(journal.title, restored.title, "title")
            assertEquals(journal.description, restored.description, "description")
            assertEquals(journal.created, restored.created, "created")
            assertEquals(journal.lastUpdated, restored.lastUpdated, "lastUpdated")
        }

    @Test
    fun `room all journals observed includes every created journal`() =
        runTest {
            val now = now()
            val ids =
                (1..5).map { i ->
                    val j = Journal(id = Uuid.random(), title = "Journal $i", created = now, lastUpdated = now)
                    journalRepo.create(j)
                    j.id
                }

            val observed = journalRepo.allJournalsObserved.first()
            ids.forEach { id ->
                assertTrue(observed.any { it.id == id }, "Journal $id missing from allJournalsObserved")
            }
        }

    // ── Text note fields ─────────────────────────────────────────────────

    @Test
    fun `room text note all fields round trip`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    content = "こんにちは 🌸 \"quoted\" newline\nend",
                    creationTimestamp = now - 1.hours,
                    lastUpdated = now,
                    location =
                        NoteLocation(
                            coordinates = NoteCoordinates(latitude = 35.6762, longitude = 139.6503),
                        ),
                    syncVersion = 5L,
                )
            notesRepo.create(note, journal.id)

            val restored = notesRepo.getNoteById(note.uid) as? JournalNote.Text
            assertNotNull(restored, "Text note not found in Room")
            assertEquals(note.uid, restored.uid, "uid")
            assertEquals(note.content, restored.content, "content")
            assertEquals(note.creationTimestamp, restored.creationTimestamp, "creationTimestamp")
            assertEquals(note.lastUpdated, restored.lastUpdated, "lastUpdated")
            assertEquals(note.syncVersion, restored.syncVersion, "syncVersion")
            val coords = restored.location?.coordinates
            assertNotNull(coords, "location.coordinates was null after Room round-trip")
            assertEquals(note.location!!.coordinates!!.latitude, coords.latitude, 1e-9, "latitude")
            assertEquals(note.location!!.coordinates!!.longitude, coords.longitude, 1e-9, "longitude")
        }

    @Test
    fun `room text note without location location remains null`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    content = "No location",
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            notesRepo.create(note, journal.id)

            val restored = notesRepo.getNoteById(note.uid) as JournalNote.Text
            assertNull(restored.location?.coordinates, "location should be null when not set")
        }

    // ── Image note fields ─────────────────────────────────────────────────

    @Test
    fun `room image note all fields round trip`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val note =
                JournalNote.Image(
                    uid = Uuid.random(),
                    mediaRef = "content://media/external/images/9876",
                    caption = "Sunset at the beach 🌅",
                    creationTimestamp = now - 2.hours,
                    lastUpdated = now,
                    syncVersion = 2L,
                )
            notesRepo.create(note, journal.id)

            val restored = notesRepo.getNoteById(note.uid) as? JournalNote.Image
            assertNotNull(restored, "Image note not found in Room")
            assertEquals(note.uid, restored.uid, "uid")
            assertEquals(note.mediaRef, restored.mediaRef, "mediaRef")
            assertEquals(note.caption, restored.caption, "caption")
            assertEquals(note.creationTimestamp, restored.creationTimestamp, "creationTimestamp")
            assertEquals(note.lastUpdated, restored.lastUpdated, "lastUpdated")
            assertEquals(note.syncVersion, restored.syncVersion, "syncVersion")
        }

    @Test
    fun `room image note empty caption round trips`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val note =
                JournalNote.Image(
                    uid = Uuid.random(),
                    mediaRef = "content://media/external/images/1",
                    caption = "",
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            notesRepo.create(note, journal.id)

            val restored = notesRepo.getNoteById(note.uid) as JournalNote.Image
            assertEquals("", restored.caption, "empty caption should survive Room round-trip")
        }

    // ── Audio note fields ─────────────────────────────────────────────────

    @Test
    fun `room audio note all fields round trip`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val note =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    mediaRef = "content://media/external/audio/5555",
                    durationMs = 187_500L,
                    creationTimestamp = now,
                    lastUpdated = now,
                    syncVersion = 1L,
                )
            notesRepo.create(note, journal.id)

            val restored = notesRepo.getNoteById(note.uid) as? JournalNote.Audio
            assertNotNull(restored, "Audio note not found in Room")
            assertEquals(note.uid, restored.uid, "uid")
            assertEquals(note.mediaRef, restored.mediaRef, "mediaRef")
            assertEquals(note.durationMs, restored.durationMs, "durationMs")
            assertEquals(note.creationTimestamp, restored.creationTimestamp, "creationTimestamp")
            assertEquals(note.lastUpdated, restored.lastUpdated, "lastUpdated")
            assertEquals(note.syncVersion, restored.syncVersion, "syncVersion")
        }

    // ── Video note fields ─────────────────────────────────────────────────

    @Test
    fun `room video note all fields round trip`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val note =
                JournalNote.Video(
                    uid = Uuid.random(),
                    mediaRef = "content://media/external/video/7777",
                    caption = "Dog compilation 🐶",
                    creationTimestamp = now,
                    lastUpdated = now,
                    syncVersion = 4L,
                )
            notesRepo.create(note, journal.id)

            val restored = notesRepo.getNoteById(note.uid) as? JournalNote.Video
            assertNotNull(restored, "Video note not found in Room")
            assertEquals(note.uid, restored.uid, "uid")
            assertEquals(note.mediaRef, restored.mediaRef, "mediaRef")
            assertEquals(note.caption, restored.caption, "caption")
            assertEquals(note.creationTimestamp, restored.creationTimestamp, "creationTimestamp")
            assertEquals(note.lastUpdated, restored.lastUpdated, "lastUpdated")
            assertEquals(note.syncVersion, restored.syncVersion, "syncVersion")
        }

    // ── Journal-note links ───────────────────────────────────────────────

    @Test
    fun `room journal note links all links returned by get all journal note links`() =
        runTest {
            val now = now()
            val j1 = Journal(id = Uuid.random(), title = "J1", created = now, lastUpdated = now)
            val j2 = Journal(id = Uuid.random(), title = "J2", created = now, lastUpdated = now)
            journalRepo.create(j1)
            journalRepo.create(j2)

            val n1 = JournalNote.Text(uid = Uuid.random(), content = "A", creationTimestamp = now, lastUpdated = now)
            val n2 = JournalNote.Text(uid = Uuid.random(), content = "B", creationTimestamp = now, lastUpdated = now)
            val n3 = JournalNote.Text(uid = Uuid.random(), content = "C", creationTimestamp = now, lastUpdated = now)
            notesRepo.create(n1, j1.id)
            notesRepo.create(n2, j1.id)
            notesRepo.create(n3, j2.id)

            val links = notesRepo.getAllJournalNoteLinks()
            assertTrue(links.any { it.first == j1.id && it.second == n1.uid }, "link j1→n1 missing")
            assertTrue(links.any { it.first == j1.id && it.second == n2.uid }, "link j1→n2 missing")
            assertTrue(links.any { it.first == j2.id && it.second == n3.uid }, "link j2→n3 missing")
        }

    @Test
    fun `room journal content repo observes correct notes per journal`() =
        runTest {
            val now = now()
            val j1 = Journal(id = Uuid.random(), title = "J1", created = now, lastUpdated = now)
            val j2 = Journal(id = Uuid.random(), title = "J2", created = now, lastUpdated = now)
            journalRepo.create(j1)
            journalRepo.create(j2)

            val n1 = JournalNote.Text(uid = Uuid.random(), content = "In J1", creationTimestamp = now, lastUpdated = now)
            val n2 = JournalNote.Text(uid = Uuid.random(), content = "Also J1", creationTimestamp = now, lastUpdated = now)
            val n3 = JournalNote.Text(uid = Uuid.random(), content = "In J2", creationTimestamp = now, lastUpdated = now)
            notesRepo.create(n1, j1.id)
            notesRepo.create(n2, j1.id)
            notesRepo.create(n3, j2.id)

            val j1Notes = contentRepo.observeContentForJournal(j1.id).first()
            val j2Notes = contentRepo.observeContentForJournal(j2.id).first()

            assertEquals(2, j1Notes.size, "j1 should have 2 notes")
            assertEquals(1, j2Notes.size, "j2 should have 1 note")
            assertTrue(j1Notes.any { it.uid == n1.uid })
            assertTrue(j1Notes.any { it.uid == n2.uid })
            assertTrue(j2Notes.any { it.uid == n3.uid })
        }

    // ── allNotesObserved ─────────────────────────────────────────────────

    @Test
    fun `room all notes observed includes all note types`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val text = JournalNote.Text(uid = Uuid.random(), content = "T", creationTimestamp = now, lastUpdated = now)
            val image =
                JournalNote.Image(
                    uid = Uuid.random(),
                    mediaRef = "img://1",
                    caption = "",
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val audio =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    mediaRef = "audio://1",
                    durationMs = 1000,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val video =
                JournalNote.Video(
                    uid = Uuid.random(),
                    mediaRef = "video://1",
                    caption = "",
                    creationTimestamp = now,
                    lastUpdated = now,
                )

            notesRepo.create(text, journal.id)
            notesRepo.create(image, journal.id)
            notesRepo.create(audio, journal.id)
            notesRepo.create(video, journal.id)

            val all = notesRepo.allNotesObserved.first()
            assertTrue(all.any { it.uid == text.uid }, "text note missing from allNotesObserved")
            assertTrue(all.any { it.uid == image.uid }, "image note missing from allNotesObserved")
            assertTrue(all.any { it.uid == audio.uid }, "audio note missing from allNotesObserved")
            assertTrue(all.any { it.uid == video.uid }, "video note missing from allNotesObserved")
        }

    // ── Draft fields ─────────────────────────────────────────────────────

    @Test
    fun `room draft all fields round trip`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val draft =
                EditorDraft(
                    id = Uuid.random(),
                    blocks =
                        listOf(
                            SerializableTextBlock(id = Uuid.random(), timestamp = now, content = "Draft body 📝"),
                        ),
                    selectedJournalIds = listOf(journal.id),
                    createdAt = now - 1.hours,
                    lastModifiedAt = now,
                )
            journalRepo.saveDraft(draft)

            val restored = journalRepo.getDraft(draft.id)
            assertNotNull(restored, "Draft not found in Room")
            assertEquals(draft.id, restored.id, "id")
            assertEquals(draft.createdAt, restored.createdAt, "createdAt")
            assertEquals(draft.lastModifiedAt, restored.lastModifiedAt, "lastModifiedAt")
            assertEquals(draft.selectedJournalIds, restored.selectedJournalIds, "selectedJournalIds")
            assertEquals(1, restored.blocks.size, "block count")
            val block = restored.blocks.first() as SerializableTextBlock
            assertEquals("Draft body 📝", block.content, "block content")
        }

    @Test
    fun `room draft with media blocks media refs preserved`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val draft =
                EditorDraft(
                    id = Uuid.random(),
                    blocks =
                        listOf(
                            SerializableImageBlock(id = Uuid.random(), timestamp = now, uri = "content://media/1"),
                            SerializableAudioBlock(id = Uuid.random(), timestamp = now, uri = "content://audio/2", duration = 5000L),
                            SerializableVideoBlock(id = Uuid.random(), timestamp = now, uri = "content://video/3"),
                        ),
                    selectedJournalIds = listOf(journal.id),
                    createdAt = now,
                    lastModifiedAt = now,
                )
            journalRepo.saveDraft(draft)

            val restored = journalRepo.getDraft(draft.id)!!
            assertEquals(3, restored.blocks.size, "block count")
            val img = restored.blocks[0] as SerializableImageBlock
            val audio = restored.blocks[1] as SerializableAudioBlock
            val video = restored.blocks[2] as SerializableVideoBlock
            assertEquals("content://media/1", img.uri, "image uri")
            assertEquals("content://audio/2", audio.uri, "audio uri")
            assertEquals(5000L, audio.duration, "audio duration")
            assertEquals("content://video/3", video.uri, "video uri")
        }

    @Test
    fun `room get all drafts returns all saved drafts`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            journalRepo.create(journal)

            val draftIds =
                (1..3).map {
                    val d =
                        EditorDraft(
                            id = Uuid.random(),
                            blocks = listOf(SerializableTextBlock(id = Uuid.random(), timestamp = now, content = "Draft $it")),
                            selectedJournalIds = listOf(journal.id),
                            createdAt = now - it.hours,
                            lastModifiedAt = now,
                        )
                    journalRepo.saveDraft(d)
                    d.id
                }

            val all = journalRepo.getAllDrafts()
            draftIds.forEach { id ->
                assertTrue(all.any { it.id == id }, "Draft $id missing from getAllDrafts")
            }
        }

    // ── Inline fakes ─────────────────────────────────────────────────────

    private class InMemorySyncMetadataService : SyncMetadataService {
        private val pending = mutableMapOf<EntityType, MutableMap<String, PendingUpload>>()
        private val syncTimes = mutableMapOf<EntityType, Instant>()
        private val pendingCountFlow = MutableStateFlow(0)

        override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> =
            pending[entityType]?.values?.toList() ?: emptyList()

        override suspend fun markAsSynced(
            entityId: String,
            entityType: EntityType,
            syncedAt: Instant,
            version: Long,
        ) {
            pending[entityType]?.remove(entityId)
            updateCount()
        }

        override suspend fun getLastSyncTime(entityType: EntityType): Instant? = syncTimes[entityType]

        override suspend fun updateLastSyncTime(
            entityType: EntityType,
            syncedAt: Instant,
        ) {
            syncTimes[entityType] = syncedAt
        }

        override suspend fun enqueuePending(
            entityId: String,
            entityType: EntityType,
            operation: PendingOperation,
        ) {
            pending.getOrPut(entityType) { mutableMapOf() }[entityId] = PendingUpload(entityId, operation, 0)
            updateCount()
        }

        override suspend fun resetSyncStatus(
            entityId: String,
            entityType: EntityType,
        ) {
            pending.getOrPut(entityType) { mutableMapOf() }[entityId] = PendingUpload(entityId, PendingOperation.UPDATE, 0)
            updateCount()
        }

        override suspend fun getPendingCount(): Int = pending.values.sumOf { it.size }

        override fun observePendingCount(): Flow<Int> = pendingCountFlow

        override fun observePendingUploads(): Flow<List<QueuedUpload>> = flowOf(emptyList())

        override suspend fun incrementRetryCount(
            entityId: String,
            entityType: EntityType,
        ) {
            val existing = pending[entityType]?.get(entityId) ?: return
            pending[entityType]!![entityId] = existing.copy(retryCount = existing.retryCount + 1)
        }

        override suspend fun clearPending() {
            pending.clear()
            pendingCountFlow.value = 0
        }

        override suspend fun resetAllCursors() {
            syncTimes.clear()
        }

        private fun updateCount() {
            pendingCountFlow.value = pending.values.sumOf { it.size }
        }
    }

    private class InMemoryDraftRepository : DraftRepository {
        private val drafts = mutableMapOf<Uuid, EditorDraft>()
        private val flow = MutableStateFlow<List<EditorDraft>>(emptyList())

        override suspend fun saveDraft(draft: EditorDraft) {
            drafts[draft.id] = draft
            flow.value = drafts.values.toList()
        }

        override suspend fun getLatestDraft(): EditorDraft? = drafts.values.maxByOrNull { it.lastModifiedAt }

        override suspend fun getAllDrafts(): List<EditorDraft> = drafts.values.toList()

        override val allDrafts: Flow<List<EditorDraft>> get() = flow

        override suspend fun getDraft(id: Uuid): EditorDraft? = drafts[id]

        override suspend fun deleteDraft(id: Uuid) {
            drafts.remove(id)
            flow.value = drafts.values.toList()
        }

        override suspend fun clearAllDrafts() {
            drafts.clear()
            flow.value = emptyList()
        }
    }

    private class NoOpRemoteJournalDataSource : RemoteJournalDataSource {
        override suspend fun observeAllJournals(): List<Journal> = emptyList()

        override suspend fun addJournal(journal: Journal): String = journal.id.toString()

        override suspend fun editJournal(journal: Journal) {}

        override suspend fun deleteJournal(journalId: String) {}
    }
}
