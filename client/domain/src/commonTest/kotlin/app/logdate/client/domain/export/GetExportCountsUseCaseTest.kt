package app.logdate.client.domain.export

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.NoteCounts
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for [GetExportCountsUseCase].
 *
 * Verifies the logic for aggregating counts of journals, notes, drafts, and media items
 * to be displayed in the export summary. These tests ensure that different types of
 * media (images, audio, video) are correctly identified and that no items are
 * double-counted across categories.
 */
class GetExportCountsUseCaseTest {
    private lateinit var mockJournalRepository: MockJournalRepository
    private lateinit var mockNotesRepository: MockJournalNotesRepository
    private lateinit var useCase: GetExportCountsUseCase

    @BeforeTest
    fun setUp() {
        mockJournalRepository = MockJournalRepository()
        mockNotesRepository = MockJournalNotesRepository()
        useCase =
            GetExportCountsUseCase(
                journalRepository = mockJournalRepository,
                journalNotesRepository = mockNotesRepository,
            )
    }

    @Test
    fun `returns correct counts when all repositories have data`() =
        runTest {
            val now = Clock.System.now()
            mockJournalRepository.testJournals =
                listOf(
                    Journal(id = Uuid.random(), title = "Journal 1", description = "", created = now, lastUpdated = now),
                    Journal(id = Uuid.random(), title = "Journal 2", description = "", created = now, lastUpdated = now),
                )
            mockNotesRepository.testNotes =
                listOf(
                    JournalNote.Text(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, content = "Hello"),
                    JournalNote.Image(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///img.jpg"),
                    JournalNote.Audio(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///audio.m4a"),
                    JournalNote.Video(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///video.mp4"),
                )
            mockJournalRepository.testDrafts =
                listOf(
                    EditorDraft(id = Uuid.random(), blocks = emptyList()),
                )

            val counts = useCase()

            assertEquals(2, counts.journalCount, "Should count 2 journals")
            assertEquals(4, counts.noteCount, "Should count 4 notes")
            assertEquals(1, counts.draftCount, "Should count 1 draft")
            assertEquals(3, counts.mediaCount, "Should count 3 media items (image, audio, video)")
        }

    @Test
    fun `note totals come from counting instead of loading every note`() =
        runTest {
            val counting =
                object : JournalNotesRepository by mockNotesRepository {
                    override val allNotesObserved: Flow<List<JournalNote>> = flow { error("Export counts must not load every note") }

                    override suspend fun countNotes() = NoteCounts(total = 1_200, withMedia = 300)
                }

            val counts = GetExportCountsUseCase(mockJournalRepository, counting)()

            assertEquals(1_200, counts.noteCount)
            assertEquals(300, counts.mediaCount)
        }

    @Test
    fun `returns all zeros when repositories are empty`() =
        runTest {
            mockJournalRepository.testJournals = emptyList()
            mockNotesRepository.testNotes = emptyList()
            mockJournalRepository.testDrafts = emptyList()

            val counts = useCase()

            assertEquals(0, counts.journalCount)
            assertEquals(0, counts.noteCount)
            assertEquals(0, counts.draftCount)
            assertEquals(0, counts.mediaCount)
        }

    @Test
    fun `correctly counts media notes excluding text notes`() =
        runTest {
            val now = Clock.System.now()
            mockNotesRepository.testNotes =
                listOf(
                    JournalNote.Text(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, content = "Note 1"),
                    JournalNote.Text(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, content = "Note 2"),
                    JournalNote.Image(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///img.jpg"),
                    JournalNote.Text(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, content = "Note 3"),
                    JournalNote.Video(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///video.mp4"),
                )

            val counts = useCase()

            assertEquals(5, counts.noteCount, "All 5 notes should be counted")
            // mediaCount = 2 (Image + Video from notesWithMedia) + 0 (no Audio notes from getAllAudioNotesUseCase)
            assertEquals(2, counts.mediaCount, "Only Image and Video should count as media when no audio notes exist")
        }

    @Test
    fun `counts audio notes once as media`() =
        runTest {
            val now = Clock.System.now()
            // Only audio notes - no image or video
            mockNotesRepository.testNotes =
                listOf(
                    JournalNote.Audio(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///a1.m4a"),
                    JournalNote.Audio(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, mediaRef = "file:///a2.m4a"),
                )

            val counts = useCase()

            assertEquals(2, counts.mediaCount, "Audio notes should be counted once")
        }

    @Test
    fun `single audio note is not double-counted`() =
        runTest {
            val now = Clock.System.now()
            val audioNote =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "file:///audio.m4a",
                )
            mockNotesRepository.testNotes = listOf(audioNote)

            val counts = useCase()

            assertEquals(1, counts.mediaCount, "A single audio note should count once")
            assertEquals(1, counts.noteCount, "Note count should still be 1")
        }

    @Test
    fun `handles large counts correctly`() =
        runTest {
            val now = Clock.System.now()
            val journalCount = 500
            val noteCount = 2000
            val draftCount = 100

            mockJournalRepository.testJournals =
                (1..journalCount).map {
                    Journal(id = Uuid.random(), title = "Journal $it", description = "", created = now, lastUpdated = now)
                }
            mockNotesRepository.testNotes =
                (1..noteCount).map { i ->
                    when (i % 4) {
                        0 -> {
                            JournalNote.Text(uid = Uuid.random(), creationTimestamp = now, lastUpdated = now, content = "Note $i")
                        }

                        1 -> {
                            JournalNote.Image(
                                uid = Uuid.random(),
                                creationTimestamp = now,
                                lastUpdated = now,
                                mediaRef = "file:///img$i.jpg",
                            )
                        }

                        2 -> {
                            JournalNote.Audio(
                                uid = Uuid.random(),
                                creationTimestamp = now,
                                lastUpdated = now,
                                mediaRef = "file:///audio$i.m4a",
                            )
                        }

                        else -> {
                            JournalNote.Video(
                                uid = Uuid.random(),
                                creationTimestamp = now,
                                lastUpdated = now,
                                mediaRef = "file:///video$i.mp4",
                            )
                        }
                    }
                }
            mockJournalRepository.testDrafts =
                (1..draftCount).map {
                    EditorDraft(id = Uuid.random(), blocks = emptyList())
                }

            val counts = useCase()

            assertEquals(journalCount, counts.journalCount)
            assertEquals(noteCount, counts.noteCount)
            assertEquals(draftCount, counts.draftCount)

            assertEquals(1500, counts.mediaCount, "Media count should include image, audio, and video notes once")
        }

    private class MockJournalRepository : JournalRepository {
        private val journalsFlow = MutableStateFlow<List<Journal>>(emptyList())
        var testJournals: List<Journal> = emptyList()
            set(value) {
                field = value
                journalsFlow.value = value
            }
        var testDrafts: List<EditorDraft> = emptyList()

        override val allJournalsObserved: Flow<List<Journal>> = journalsFlow

        override suspend fun getJournalById(id: Uuid): Journal? = testJournals.find { it.id == id }

        override suspend fun create(journal: Journal): Uuid = journal.id

        override suspend fun update(journal: Journal) {}

        override suspend fun delete(journalId: Uuid) {}

        override fun observeJournalById(id: Uuid): Flow<Journal> = flowOf(testJournals.firstOrNull() ?: Journal(id = id))

        override suspend fun saveDraft(draft: EditorDraft) {}

        override suspend fun getLatestDraft(): EditorDraft? = null

        override suspend fun getAllDrafts(): List<EditorDraft> = testDrafts

        override suspend fun getDraft(id: Uuid): EditorDraft? = null

        override suspend fun deleteDraft(id: Uuid) {}
    }

    private class MockJournalNotesRepository : JournalNotesRepository {
        private val notesFlow = MutableStateFlow<List<JournalNote>>(emptyList())
        var testNotes: List<JournalNote> = emptyList()
            set(value) {
                field = value
                notesFlow.value = value
            }
        var notesByJournal: Map<Uuid, List<JournalNote>> = emptyMap()

        override val allNotesObserved: Flow<List<JournalNote>> = notesFlow

        override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(notesByJournal[journalId] ?: emptyList())

        override fun observeNotesInRange(
            start: Instant,
            end: Instant,
        ): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesPage(
            pageSize: Int,
            offset: Int,
        ): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = flowOf(emptyList())

        override suspend fun create(note: JournalNote): Uuid = note.uid

        override suspend fun remove(note: JournalNote) {}

        override suspend fun removeById(noteId: Uuid) {}

        override suspend fun create(
            note: JournalNote,
            journalId: Uuid,
        ) {}

        override suspend fun removeFromJournal(
            noteId: Uuid,
            journalId: Uuid,
        ) {}

        override suspend fun getNoteById(noteId: Uuid): JournalNote? = null

        override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()
    }
}
