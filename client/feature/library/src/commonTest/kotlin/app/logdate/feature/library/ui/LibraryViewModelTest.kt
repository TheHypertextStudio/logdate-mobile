package app.logdate.feature.library.ui

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.feature.library.fakes.FakeIndexedMediaRepository
import app.logdate.feature.library.fakes.FakeJournalNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for [LibraryViewModel].
 *
 * Tests the logic for aggregating and grouping indexed media and journal notes
 * into the library view, ensuring that items are correctly prioritized,
 * grouped by date, and categorized by media type.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() =
        runTest(testDispatcher) {
            val notesRepository = FakeJournalNotesRepository()
            val repository = FakeIndexedMediaRepository()
            val viewModel = LibraryViewModel(notesRepository, repository, testDispatcher)
            assertEquals(LibraryUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `empty repository produces empty state`() =
        runTest(testDispatcher) {
            val notesRepository = FakeJournalNotesRepository(emptyList())
            val repository = FakeIndexedMediaRepository(emptyList())
            val viewModel = LibraryViewModel(notesRepository, repository, testDispatcher)

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertIs<LibraryUiState.Empty>(viewModel.uiState.value)
            collectJob.cancel()
        }

    @Test
    fun `repository failure produces a retryable error rather than an empty library`() =
        runTest(testDispatcher) {
            val failingRepository =
                object : IndexedMediaRepository by FakeIndexedMediaRepository() {
                    override fun observeAllMedia() = flow<List<IndexedMedia>> { error("database unavailable") }
                }
            val viewModel = LibraryViewModel(FakeJournalNotesRepository(), failingRepository, testDispatcher)

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertIs<LibraryUiState.Error>(viewModel.uiState.value)
            collectJob.cancel()
        }

    @Test
    fun `retry reopens the library after a transient repository failure`() =
        runTest(testDispatcher) {
            var failing = true
            val media =
                listOf(
                    IndexedMedia.Image(
                        uid = Uuid.random(),
                        uri = "file:///restored.jpg",
                        timestamp = Instant.parse("2024-03-09T00:00:00Z"),
                    ),
                )
            val repository =
                object : IndexedMediaRepository by FakeIndexedMediaRepository() {
                    override fun observeAllMedia() =
                        if (failing) flow<List<IndexedMedia>> { error("database unavailable") } else flowOf(media)
                }
            val viewModel = LibraryViewModel(FakeJournalNotesRepository(), repository, testDispatcher)
            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertIs<LibraryUiState.Error>(viewModel.uiState.value)

            failing = false
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(1, assertIs<LibraryUiState.Content>(viewModel.uiState.value).totalCount)
            collectJob.cancel()
        }

    @Test
    fun `indexed media produces content state`() =
        runTest(testDispatcher) {
            val media =
                listOf(
                    IndexedMedia.Image(
                        uid = Uuid.random(),
                        uri = "content://media/external/images/1",
                        timestamp = Instant.fromEpochMilliseconds(1710000000000),
                    ),
                    IndexedMedia.Video(
                        uid = Uuid.random(),
                        uri = "content://media/external/video/1",
                        timestamp = Instant.fromEpochMilliseconds(1710100000000),
                        duration = 3.seconds,
                    ),
                )
            val repository = FakeIndexedMediaRepository(media)
            val viewModel = LibraryViewModel(FakeJournalNotesRepository(emptyList()), repository, testDispatcher)

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Content>(state)
            assertEquals(2, state.totalCount)
            assertEquals(1, state.groups.size)
            assertEquals(2, state.groups[0].items.size)
            collectJob.cancel()
        }

    @Test
    fun `note only media still appears when indexed store is empty`() =
        runTest(testDispatcher) {
            val notes =
                listOf(
                    JournalNote.Image(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.fromEpochMilliseconds(1710000000000),
                        lastUpdated = Instant.fromEpochMilliseconds(1710000000000),
                        mediaRef = "content://media/external/images/note-only",
                    ),
                )
            val viewModel =
                LibraryViewModel(
                    FakeJournalNotesRepository(notes),
                    FakeIndexedMediaRepository(emptyList()),
                    testDispatcher,
                )

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Content>(state)
            val firstGroup = state.groups.first()
            val firstItem = firstGroup.items.first()
            assertEquals(1, state.totalCount)
            assertEquals(
                "content://media/external/images/note-only",
                firstItem.uri,
            )
            collectJob.cancel()
        }

    @Test
    fun `indexed media preferred over duplicate note uri`() =
        runTest(testDispatcher) {
            val sharedUri = "content://media/external/images/shared"
            val indexedId = Uuid.random()
            val notes =
                listOf(
                    JournalNote.Image(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.fromEpochMilliseconds(1710000000000),
                        lastUpdated = Instant.fromEpochMilliseconds(1710000000000),
                        mediaRef = sharedUri,
                    ),
                )
            val indexedMedia =
                listOf(
                    IndexedMedia.Image(
                        uid = indexedId,
                        uri = sharedUri,
                        timestamp = Instant.fromEpochMilliseconds(1710100000000),
                    ),
                )
            val viewModel =
                LibraryViewModel(
                    FakeJournalNotesRepository(notes),
                    FakeIndexedMediaRepository(indexedMedia),
                    testDispatcher,
                )

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Content>(state)
            val firstGroup = state.groups.first()
            val firstItem = firstGroup.items.first()
            assertEquals(1, state.totalCount)
            assertEquals(
                indexedId,
                firstItem.uid,
            )
            collectJob.cancel()
        }

    @Test
    fun `media grouped by month`() =
        runTest(testDispatcher) {
            val media =
                listOf(
                    IndexedMedia.Image(
                        uid = Uuid.random(),
                        uri = "content://media/external/images/1",
                        timestamp = Instant.fromEpochMilliseconds(1710000000000), // March 2024
                    ),
                    IndexedMedia.Image(
                        uid = Uuid.random(),
                        uri = "content://media/external/images/2",
                        timestamp = Instant.fromEpochMilliseconds(1704067200000), // January 2024
                    ),
                )
            val repository = FakeIndexedMediaRepository(media)
            val viewModel = LibraryViewModel(FakeJournalNotesRepository(emptyList()), repository, testDispatcher)

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Content>(state)
            assertEquals(2, state.groups.size)
            collectJob.cancel()
        }

    @Test
    fun `video items marked as video`() =
        runTest(testDispatcher) {
            val media =
                listOf(
                    IndexedMedia.Video(
                        uid = Uuid.random(),
                        uri = "content://media/external/video/1",
                        timestamp = Instant.fromEpochMilliseconds(1710000000000),
                        duration = 5.seconds,
                    ),
                )
            val repository = FakeIndexedMediaRepository(media)
            val viewModel = LibraryViewModel(FakeJournalNotesRepository(emptyList()), repository, testDispatcher)

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertIs<LibraryUiState.Content>(state)
            assertEquals(true, state.groups[0].items[0].isVideo)
            collectJob.cancel()
        }

    @Test
    fun `audio notes appear as playable audio items`() =
        runTest(testDispatcher) {
            val noteId = Uuid.random()
            val notes =
                listOf(
                    JournalNote.Audio(
                        uid = noteId,
                        creationTimestamp = Instant.fromEpochMilliseconds(1710000000000),
                        lastUpdated = Instant.fromEpochMilliseconds(1710000000000),
                        mediaRef = "file:///recording.m4a",
                        durationMs = 12_500,
                    ),
                )
            val viewModel =
                LibraryViewModel(
                    FakeJournalNotesRepository(notes),
                    FakeIndexedMediaRepository(emptyList()),
                    testDispatcher,
                )

            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = assertIs<LibraryUiState.Content>(viewModel.uiState.value)
            val item =
                state.groups
                    .single()
                    .items
                    .single()
            assertEquals(noteId, item.uid)
            assertEquals(true, item.isAudio)
            assertEquals(false, item.isVideo)
            assertEquals(12_500, item.durationMs)
            collectJob.cancel()
        }
}
