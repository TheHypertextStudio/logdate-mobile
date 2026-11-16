package app.logdate.feature.journals.ui.picker

import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.feature.journals.ui.detail.FakeDetailJournalRepository
import app.logdate.feature.journals.ui.detail.FakeJournalNotesRepository
import app.logdate.shared.model.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.content_picker_add_failed
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class JournalContentPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val journal = Journal(id = Uuid.random(), title = "Field notes")
    private val newestText = noteText("Writing", "2026-10-05T18:00:00Z")
    private val matchingPhoto = noteImage("2026-10-04T18:00:00Z")
    private val olderAudio = noteAudio("2026-10-03T18:00:00Z")
    private val currentMember = noteVideo("2026-10-02T18:00:00Z")
    private lateinit var contentRepository: PickerJournalContentRepository
    private lateinit var notesRepository: FakeJournalNotesRepository
    private lateinit var searchRepository: PickerSearchRepository
    private lateinit var viewModel: JournalContentPickerViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        contentRepository = PickerJournalContentRepository(mapOf(journal.id to listOf(currentMember)))
        searchRepository = PickerSearchRepository(listOf(searchResult(matchingPhoto)))
        notesRepository = FakeJournalNotesRepository(listOf(newestText, matchingPhoto, olderAudio, currentMember))
        viewModel = pickerViewModel(notesRepository)
        viewModel.setJournalId(journal.id)
    }

    private fun pickerViewModel(notes: FakeJournalNotesRepository) =
        JournalContentPickerViewModel(
            journalRepository = FakeDetailJournalRepository(listOf(journal)),
            journalNotesRepository = notes,
            journalContentRepository = contentRepository,
            searchRepository = searchRepository,
            workContext = dispatcher,
        )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shows eligible mixed content newest first while preserving selection across search and removing review items`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(
                listOf(newestText.uid, matchingPhoto.uid, olderAudio.uid),
                viewModel.uiState.value.groups
                    .flatMap { group -> group.items.map { it.id } },
            )
            assertEquals(
                3,
                viewModel.uiState.value.groups
                    .flatMap { it.items }
                    .map { it.kind }
                    .toSet()
                    .size,
            )

            viewModel.toggleSelection(newestText.uid)
            viewModel.updateSearchQuery("sunset")
            advanceUntilIdle()

            assertEquals(
                listOf(matchingPhoto.uid),
                viewModel.uiState.value.groups
                    .flatMap { it.items.map { item -> item.id } },
            )
            assertEquals(
                listOf(newestText.uid),
                viewModel.uiState.value.selectedItems
                    .map { it.id },
            )

            viewModel.removeSelection(newestText.uid)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.selectedItems
                    .isEmpty(),
            )
        }

    @Test
    fun `browses one page at a time without subscribing to every note`() =
        runTest(dispatcher) {
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val history = (0 until 150).map { day -> noteText("Day $day", (start + day.days).toString()) }
            val notes = FakeJournalNotesRepository(history)
            val picker = pickerViewModel(notes).apply { setJournalId(journal.id) }
            backgroundScope.launch { picker.uiState.collect {} }
            advanceUntilIdle()

            val firstPage =
                picker.uiState.value.groups
                    .flatMap { it.items }
            assertEquals(60, firstPage.size)
            assertEquals(history.last().uid, firstPage.first().id)
            assertTrue(picker.uiState.value.canLoadMore)

            picker.loadMore()
            picker.loadMore()
            advanceUntilIdle()
            assertEquals(
                120,
                picker.uiState.value.groups
                    .sumOf { it.items.size },
            )

            picker.loadMore()
            advanceUntilIdle()
            assertEquals(
                150,
                picker.uiState.value.groups
                    .sumOf { it.items.size },
            )
            assertFalse(picker.uiState.value.canLoadMore)

            picker.updateSearchQuery("sunset")
            advanceUntilIdle()
            assertFalse(picker.uiState.value.canLoadMore)
            assertEquals(0, notes.wholeTableSubscriptions)
        }

    @Test
    fun `adds selected content and keeps selection for retryable failures`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.toggleSelection(newestText.uid)

            viewModel.addSelected()
            advanceUntilIdle()

            assertEquals(listOf(newestText.uid), contentRepository.addedBatches.single())
            assertEquals(1, viewModel.uiState.value.addedCount)

            contentRepository.failAdds = true
            viewModel.toggleSelection(matchingPhoto.uid)
            viewModel.addSelected()
            advanceUntilIdle()

            assertEquals(Res.string.content_picker_add_failed, viewModel.uiState.value.addError)
            assertEquals(
                setOf(newestText.uid, matchingPhoto.uid),
                viewModel.uiState.value.selectedItems
                    .map { it.id }
                    .toSet(),
            )
        }

    private fun noteText(
        content: String,
        timestamp: String,
    ) = JournalNote.Text(
        uid = Uuid.random(),
        creationTimestamp = Instant.parse(timestamp),
        lastUpdated = Instant.parse(timestamp),
        content = content,
    )

    private fun noteImage(timestamp: String) =
        JournalNote.Image(
            uid = Uuid.random(),
            creationTimestamp = Instant.parse(timestamp),
            lastUpdated = Instant.parse(timestamp),
            mediaRef = "file:///photo.jpg",
            caption = "Sunset",
        )

    private fun noteAudio(timestamp: String) =
        JournalNote.Audio(
            uid = Uuid.random(),
            creationTimestamp = Instant.parse(timestamp),
            lastUpdated = Instant.parse(timestamp),
            mediaRef = "file:///recording.m4a",
            durationMs = 10_000,
        )

    private fun noteVideo(timestamp: String) =
        JournalNote.Video(
            uid = Uuid.random(),
            creationTimestamp = Instant.parse(timestamp),
            lastUpdated = Instant.parse(timestamp),
            mediaRef = "file:///video.mp4",
        )

    private fun searchResult(note: JournalNote) =
        SearchResult(
            uid = note.uid,
            content = "Sunset",
            created = note.creationTimestamp,
            contentType = SearchContentType.MEDIA_CAPTION,
        )

    private class PickerJournalContentRepository(
        memberships: Map<Uuid, List<JournalNote>>,
    ) : JournalContentRepository {
        private val memberships = MutableStateFlow(memberships)
        val addedBatches = mutableListOf<List<Uuid>>()
        var failAdds = false

        override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> = memberships.map { it[journalId].orEmpty() }

        override fun observeJournalsForContent(contentId: Uuid) = flowOf(emptyList<Journal>())

        override suspend fun addContentToJournal(
            contentId: Uuid,
            journalId: Uuid,
        ) = Unit

        override suspend fun addContentsToJournal(
            contentIds: Collection<Uuid>,
            journalId: Uuid,
        ): Int {
            check(!failAdds) { "Write failed" }
            val added = contentIds.distinct()
            addedBatches += added
            return added.size
        }

        override suspend fun removeContentFromJournal(
            contentId: Uuid,
            journalId: Uuid,
        ) = Unit

        override suspend fun addContentToJournals(
            contentId: Uuid,
            journalIds: List<Uuid>,
        ) = Unit

        override suspend fun removeContentFromAllJournals(contentId: Uuid) = Unit

        override fun observeJournalsForContents(contentIds: Set<Uuid>) = flowOf(emptyMap<Uuid, List<Journal>>())
    }

    private class PickerSearchRepository(
        private val results: List<SearchResult>,
    ) : SearchRepository {
        override fun search(query: String): Flow<List<SearchResult>> = flowOf(results)

        override fun searchWithLimit(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = flowOf(results.take(limit))

        override fun searchWithSnippets(query: String): Flow<List<SearchResult>> = flowOf(results)

        override fun searchRanked(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = flowOf(results.take(limit))

        override fun searchInJournal(
            query: String,
            journalId: Uuid,
            limit: Int,
        ): Flow<List<SearchResult>> = flowOf(results.take(limit))
    }
}
