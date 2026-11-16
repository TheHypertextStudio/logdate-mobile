package app.logdate.feature.onboarding.ui

import androidx.lifecycle.SavedStateHandle
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.MediaPayload
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Exercises the [MemorySelectionViewModel] to ensure smooth media curation during onboarding.
 *
 * This suite validates the reactive UI state management for:
 * - Fetching and displaying personal media (memories) for user selection
 * - Falling back to recent media when requested date ranges are empty
 * - Curating memories using a local or generative AI client
 * - Recovering gracefully from media access or loading failures
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemorySelectionViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeMediaManager: FakeMediaManager
    private lateinit var notes: TestJournalNotesRepository
    private lateinit var importer: TestSelectedMemoryImporter

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeMediaManager = FakeMediaManager()
        notes = TestJournalNotesRepository()
        importer = TestSelectedMemoryImporter()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh memories falls back to recent media when date range is empty`() =
        runTest {
            val olderMemories = listOf(sampleImage("older-1"), sampleVideo("older-2"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(emptyList()) }
            fakeMediaManager.recentMediaFlow = { flowOf(olderMemories) }

            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()

            assertEquals(olderMemories.sortedByDescending(MediaObject::timestamp), viewModel.uiState.value.allMemories)
            assertEquals(
                olderMemories.toSet(),
                viewModel.uiState.value.aiCuratedMemories
                    .toSet(),
            )
            assertEquals(false, viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun `older library photos remain browsable and importable after the first page`() =
        runTest {
            val recentTimestamp = Clock.System.now()
            val recent = (1..21).map { sampleImage("recent-$it").copy(timestamp = recentTimestamp) }
            val older = sampleImage("older").copy(timestamp = Instant.parse("2011-03-04T12:00:00Z"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(listOf(older) + recent) }
            val viewModel = createViewModel()

            viewModel.refreshMemories()
            advanceUntilIdle()
            assertEquals(
                recent.take(20).map { it.uri },
                viewModel.uiState.value.allMemories
                    .map { it.uri },
            )
            assertTrue(viewModel.uiState.value.hasMoreMemories)
            viewModel.loadMoreMemories()
            advanceUntilIdle()

            assertEquals(
                older.uri,
                viewModel.uiState.value.allMemories
                    .last()
                    .uri,
            )
            assertTrue(fakeMediaManager.lastQueryStart!! < older.timestamp)
            assertTrue(fakeMediaManager.lastQueryEnd!! > recentTimestamp)
            viewModel.toggleMemorySelection(older.uri)
            assertTrue(viewModel.processSelectedMemories().isSuccess)
            assertEquals(older.timestamp, notes.saved.single().creationTimestamp)
        }

    @Test
    fun `refresh memories recovers after load failure`() =
        runTest {
            fakeMediaManager.queryMediaByDateFlow = {
                flow {
                    throw IllegalStateException("Media access denied")
                }
            }

            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loadFailed)
            assertTrue(
                viewModel.uiState.value.allMemories
                    .isEmpty(),
            )

            val recoveredMemories = listOf(sampleImage("recovered-1"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(emptyList()) }
            fakeMediaManager.recentMediaFlow = { flowOf(recoveredMemories) }

            viewModel.refreshMemories()
            advanceUntilIdle()

            assertEquals(recoveredMemories, viewModel.uiState.value.allMemories)
            assertFalse(viewModel.uiState.value.loadFailed)
            assertEquals(false, viewModel.uiState.value.isLoading)
        }

    @Test
    fun `selections survive a fresh view model over the same saved state as after process death`() =
        runTest {
            val memories = listOf(sampleImage("keep-1"), sampleImage("keep-2"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(memories) }
            val savedStateHandle = SavedStateHandle()

            val firstViewModel = createViewModel(savedStateHandle)
            firstViewModel.refreshMemories()
            advanceUntilIdle()
            firstViewModel.toggleMemorySelection(memories[0].uri)

            // Simulates the process dying and a fresh view model being created over the same
            // saved state, rather than a fresh, empty one.
            val secondViewModel = createViewModel(savedStateHandle)

            assertEquals(setOf(memories[0].uri), secondViewModel.uiState.value.selectedMemoryIds)
        }

    @Test
    fun `continuing with selected memories imports them and clears importing state`() =
        runTest {
            val memories = listOf(sampleImage("keep-1"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(memories) }

            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memories.single().uri)

            val result = viewModel.processSelectedMemories()

            assertTrue(result.isSuccess)
            assertFalse(viewModel.uiState.value.isImporting)
            assertFalse(viewModel.uiState.value.importFailed)
            val image = notes.saved.single() as JournalNote.Image
            assertEquals(memories.single().timestamp, image.creationTimestamp)
            assertEquals("file:///managed/keep-1", image.mediaRef)
            assertEquals(listOf(memories.single().uri), importer.imported)
        }

    @Test
    fun `a failed import surfaces importFailed instead of failing silently`() =
        runTest {
            val memories = listOf(sampleImage("broken-1"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(memories) }
            importer.errorFor = memories.single().uri

            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memories.single().uri)

            val result = viewModel.processSelectedMemories()

            assertTrue(result.isFailure)
            assertFalse(viewModel.uiState.value.isImporting)
            assertTrue(viewModel.uiState.value.importFailed)
        }

    @Test
    fun `a second concurrent import attempt is rejected instead of double importing`() =
        runTest {
            val memories = listOf(sampleImage("slow-1"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(memories) }
            importer.delayBeforeImport = { delay(1_000) }

            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memories.single().uri)

            val firstImport = async { viewModel.processSelectedMemories() }
            runCurrent()
            assertTrue(viewModel.uiState.value.isImporting)

            val secondImport = viewModel.processSelectedMemories()
            assertTrue(secondImport.isFailure)

            advanceUntilIdle()
            assertTrue(firstImport.await().isSuccess)
            assertEquals(1, notes.saved.size)
        }

    @Test
    fun `retry after a later photo fails does not duplicate the completed photo`() =
        runTest {
            val memories = listOf(sampleImage("first"), sampleImage("second"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(memories) }
            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            memories.forEach { viewModel.toggleMemorySelection(it.uri) }
            importer.errorFor = memories.last().uri

            assertTrue(viewModel.processSelectedMemories().isFailure)
            assertEquals(1, notes.saved.size)
            importer.errorFor = null

            assertTrue(viewModel.processSelectedMemories().isSuccess)
            assertEquals(2, notes.saved.size)
            assertEquals(1, importer.imported.count { it == memories.first().uri })
        }

    @Test
    fun `retry after note creation failure uses the same note id`() =
        runTest {
            val memory = sampleImage("uncertain")
            fakeMediaManager.queryMediaByDateFlow = { flowOf(listOf(memory)) }
            val savedStateHandle = SavedStateHandle()
            val firstViewModel = createViewModel(savedStateHandle)
            firstViewModel.refreshMemories()
            advanceUntilIdle()
            firstViewModel.toggleMemorySelection(memory.uri)
            notes.failAfterCreate = true

            assertTrue(firstViewModel.processSelectedMemories().isFailure)
            assertEquals(1, notes.saved.size)
            notes.failAfterCreate = false
            val secondViewModel = createViewModel(savedStateHandle)
            secondViewModel.refreshMemories()
            advanceUntilIdle()

            assertTrue(secondViewModel.processSelectedMemories().isSuccess)
            assertEquals(1, notes.saved.size)
            assertEquals(1, importer.imported.size)
        }

    @Test
    fun `failed note write discards its unpublished media copy`() =
        runTest {
            val memory = sampleImage("unpublished")
            fakeMediaManager.queryMediaByDateFlow = { flowOf(listOf(memory)) }
            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memory.uri)
            notes.failBeforeCreate = true

            assertTrue(viewModel.processSelectedMemories().isFailure)
            assertTrue(notes.saved.isEmpty())
            assertEquals(listOf("file:///managed/unpublished"), importer.discarded)
        }

    @Test
    fun `failed note write keeps media another note still references`() =
        runTest {
            val memory = sampleImage("shared")
            fakeMediaManager.queryMediaByDateFlow = { flowOf(listOf(memory)) }
            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memory.uri)
            notes.failBeforeCreate = true
            notes.referencedPaths = setOf("file:///managed/shared")

            assertTrue(viewModel.processSelectedMemories().isFailure)
            assertTrue(importer.discarded.isEmpty(), "Discarded ${importer.discarded}")
        }

    @Test
    fun `selected video is copied into a video entry at its capture time`() =
        runTest {
            val video = sampleVideo("clip")
            fakeMediaManager.queryMediaByDateFlow = { flowOf(listOf(video)) }
            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(video.uri)

            assertTrue(viewModel.processSelectedMemories().isSuccess)
            val saved = notes.saved.single() as JournalNote.Video
            assertEquals(video.timestamp, saved.creationTimestamp)
            assertEquals("file:///managed/clip", saved.mediaRef)
        }

    @Test
    fun `cancelled import clears the busy state`() =
        runTest {
            val memory = sampleImage("slow")
            fakeMediaManager.queryMediaByDateFlow = { flowOf(listOf(memory)) }
            importer.delayBeforeImport = { delay(1_000) }
            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memory.uri)

            val import = async { viewModel.processSelectedMemories() }
            runCurrent()
            assertTrue(viewModel.uiState.value.isImporting)
            import.cancelAndJoin()

            assertFalse(viewModel.uiState.value.isImporting)
            assertTrue(notes.saved.isEmpty())
        }

    @Test
    fun `selection cannot change while import is in progress`() =
        runTest {
            val memories = listOf(sampleImage("first"), sampleImage("second"))
            fakeMediaManager.queryMediaByDateFlow = { flowOf(memories) }
            importer.delayBeforeImport = { delay(1_000) }
            val viewModel = createViewModel()
            viewModel.refreshMemories()
            advanceUntilIdle()
            viewModel.toggleMemorySelection(memories.first().uri)

            val import = async { viewModel.processSelectedMemories() }
            runCurrent()
            viewModel.toggleMemorySelection(memories.last().uri)
            advanceUntilIdle()

            assertTrue(import.await().isSuccess)
            assertEquals(setOf(memories.first().uri), viewModel.uiState.value.selectedMemoryIds)
            assertEquals(1, notes.saved.size)
        }

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): MemorySelectionViewModel =
        MemorySelectionViewModel(
            mediaManager = fakeMediaManager,
            savedStateHandle = savedStateHandle,
            notesRepository = notes,
            mediaImporter = importer,
            workContext = testDispatcher,
        )

    private fun sampleImage(id: String): MediaObject.Image =
        MediaObject.Image(
            uri = "content://media/$id",
            name = "$id.jpg",
            size = 1024,
            timestamp = Instant.parse("2024-01-01T00:00:00Z"),
        )

    private fun sampleVideo(id: String): MediaObject.Video =
        MediaObject.Video(
            uri = "content://media/$id",
            name = "$id.mp4",
            size = 2048,
            timestamp = Instant.parse("2024-01-02T00:00:00Z"),
            duration = Duration.parse("30s"),
        )
}

private class TestSelectedMemoryImporter : SelectedMemoryMediaImporter {
    var errorFor: String? = null
    var delayBeforeImport: suspend () -> Unit = {}
    val imported = mutableListOf<String>()
    val discarded = mutableListOf<String>()

    override suspend fun import(sourceUri: String): String {
        delayBeforeImport()
        if (sourceUri == errorFor) error("Import failed")
        imported += sourceUri
        return "file:///managed/${sourceUri.substringAfterLast('/')}"
    }

    override suspend fun discard(managedUri: String) {
        discarded += managedUri
    }
}

private class TestJournalNotesRepository : JournalNotesRepository {
    val saved = mutableListOf<JournalNote>()
    var failAfterCreate = false
    var failBeforeCreate = false

    /** Paths some other note references under a different spelling than the one asked about. */
    var referencedPaths: Set<String> = emptySet()
    override val allNotesObserved = MutableStateFlow<List<JournalNote>>(emptyList())

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = allNotesObserved

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = allNotesObserved

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = allNotesObserved

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = allNotesObserved

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = allNotesObserved

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = saved.find { it.uid == noteId }

    override suspend fun create(note: JournalNote): Uuid {
        if (failBeforeCreate) error("Write failed")
        saved += note
        allNotesObserved.value = saved.toList()
        if (failAfterCreate) error("Write confirmation failed")
        return note.uid
    }

    override suspend fun remove(note: JournalNote) {
        saved.remove(note)
    }

    override suspend fun removeById(noteId: Uuid) {
        saved.removeAll { it.uid == noteId }
    }

    override suspend fun create(
        note: JournalNote,
        journalId: Uuid,
    ) {
        create(note)
    }

    override suspend fun removeFromJournal(
        noteId: Uuid,
        journalId: Uuid,
    ) = Unit

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()

    override suspend fun notesReferencingMediaPaths(paths: Set<String>): Set<String> = paths.intersect(referencedPaths)
}

private class FakeMediaManager : MediaManager {
    var queryMediaByDateFlow: () -> Flow<List<MediaObject>> = { flowOf(emptyList()) }
    var recentMediaFlow: () -> Flow<List<MediaObject>> = { flowOf(emptyList()) }
    var lastQueryStart: Instant? = null
    var lastQueryEnd: Instant? = null

    override suspend fun getMedia(uri: String): MediaObject = error("Not used in test")

    override suspend fun deleteOwnedMedia(uri: String): Boolean = false

    override suspend fun exists(mediaId: String): Boolean = false

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = recentMediaFlow()

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> {
        lastQueryStart = start
        lastQueryEnd = end
        return queryMediaByDateFlow()
    }

    override suspend fun addToDefaultCollection(uri: String) = Unit

    override suspend fun readMedia(uri: String): MediaPayload = error("Not used in test")

    override suspend fun saveMedia(payload: MediaPayload): String = error("Not used in test")

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String = error("Not used in test")
}
