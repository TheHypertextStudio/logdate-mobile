package app.logdate.feature.editor.ui.editor

import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.domain.editor.ObserveEditorDataUseCase
import app.logdate.client.domain.editor.SaveEntryUseCase
import app.logdate.client.domain.journals.GetCurrentUserJournalsUseCase
import app.logdate.client.domain.journals.GetDefaultSelectedJournalsUseCase
import app.logdate.client.domain.location.LocationRetryWorker
import app.logdate.client.domain.location.LogCurrentLocationUseCase
import app.logdate.client.domain.notes.AddNoteUseCase
import app.logdate.client.domain.notes.FetchEntryUseCase
import app.logdate.client.domain.notes.FetchTodayNotesUseCase
import app.logdate.client.domain.notes.drafts.CleanupExpiredDraftsUseCase
import app.logdate.client.domain.notes.drafts.CreateEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.DeleteAllDraftsUseCase
import app.logdate.client.domain.notes.drafts.DeleteEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.FetchEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.notes.drafts.GetAllDraftsUseCase
import app.logdate.client.domain.notes.drafts.UpdateEntryDraftUseCase
import app.logdate.client.domain.world.LogLocationUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.editor.delegate.ContentLoader
import app.logdate.feature.editor.ui.editor.delegate.DraftManager
import app.logdate.feature.editor.ui.editor.fakes.FakeActivityTimelineRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeClientLocationProvider
import app.logdate.feature.editor.ui.editor.fakes.FakeEntryDraftRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeJournalContentRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeJournalNotesRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeJournalRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeLocationHistoryRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeLocationTrackingSettingsRepository
import app.logdate.feature.editor.ui.editor.fakes.FakeMediaManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class PreferredEntryLaunchTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val journalNotesRepository = FakeJournalNotesRepository()
    private val journalContentRepository = FakeJournalContentRepository()

    @BeforeTest fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `each saved modality opens one focused block`() =
        testScope.runTest {
            val expected =
                mapOf(
                    EntryStartMode.TEXT to TextBlockUiState::class,
                    EntryStartMode.HANDWRITING to HandwritingBlockUiState::class,
                    EntryStartMode.CAMERA to CameraBlockUiState::class,
                    EntryStartMode.AUDIO to AudioBlockUiState::class,
                    EntryStartMode.GALLERY to ImageBlockUiState::class,
                )
            for ((mode, type) in expected) {
                val vm = createViewModel()
                vm.initializePreferredEntryMode(mode)
                vm.initializePreferredEntryMode(mode)
                advanceUntilIdle()
                val block =
                    vm.editorState.value.blocks
                        .single()
                assertEquals(type, block::class)
                assertEquals(block.id, vm.editorState.value.expandedBlockId)
                if (block is AudioBlockUiState) assertFalse(block.startOnOpen)
            }
        }

    @Test fun `a preferred mode never replaces a draft or its focus`() =
        testScope.runTest {
            val vm = createViewModel()
            val block = HandwritingBlockUiState()
            vm.mutableEditorState.value =
                EditorState(blocks = listOf(block), expandedBlockId = block.id, draftState = DraftState.Active(Uuid.random()))
            vm.initializePreferredEntryMode(EntryStartMode.CAMERA)
            advanceUntilIdle()
            assertEquals(listOf(block), vm.editorState.value.blocks)
            assertEquals(block.id, vm.editorState.value.expandedBlockId)
        }

    @Test fun `a pending draft load prevents a default block being created`() =
        testScope.runTest {
            val vm = createViewModel()
            vm.activeLoadRequest = EntryEditorViewModel.LoadRequest.Draft(Uuid.random())
            vm.initializePreferredEntryMode(EntryStartMode.CAMERA)
            advanceUntilIdle()
            assertTrue(
                vm.editorState.value.blocks
                    .isEmpty(),
            )
        }

    @Test fun `a first entry opens on the empty editor, which offers every way to start`() =
        testScope.runTest {
            val drafts = FakeEntryDraftRepository().apply { draftListGate = CompletableDeferred() }
            val vm = createViewModel(drafts)
            vm.resumeFirstEntryDraft()
            advanceUntilIdle()
            val state = vm.editorState.value
            assertTrue(state.blocks.isEmpty(), "a first entry was started for the person: ${state.blocks}")
            assertFalse(state.isLoading)
            assertTrue(state.isLoadingDrafts)
        }

    @Test fun `a first entry opens the draft it was left as`() =
        testScope.runTest {
            val gate = CompletableDeferred<Unit>()
            val drafts = FakeEntryDraftRepository().apply { draftListGate = gate }
            val saved = drafts.createDraft(listOf(textNote("Coffee on the porch")))
            val vm = createViewModel(drafts)
            vm.resumeFirstEntryDraft()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            val state = vm.editorState.value
            assertEquals(DraftState.Active(saved), state.draftState)
            assertEquals("Coffee on the porch", (state.blocks.single() as TextBlockUiState).content)
        }

    @Test fun `a saved draft never replaces words typed while it loaded`() =
        testScope.runTest {
            val gate = CompletableDeferred<Unit>()
            val drafts = FakeEntryDraftRepository().apply { draftListGate = gate }
            drafts.createDraft(listOf(textNote("Coffee on the porch")))
            val vm = createViewModel(drafts)
            vm.resumeFirstEntryDraft()
            advanceUntilIdle()
            val opened = vm.createNewBlock(BlockType.TEXT) as TextBlockUiState
            vm.updateBlock(opened.copy(content = "Typed first"))
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "Typed first",
                (
                    vm.editorState.value.blocks
                        .single() as TextBlockUiState
                ).content,
            )
        }

    private fun textNote(content: String): JournalNote.Text {
        val now = Clock.System.now()
        return JournalNote.Text(creationTimestamp = now, lastUpdated = now, content = content)
    }

    private fun createViewModel(entryDraftRepository: FakeEntryDraftRepository = FakeEntryDraftRepository()): EntryEditorViewModel {
        val journalRepository = FakeJournalRepository()

        val locationProvider = FakeClientLocationProvider()
        val activityTimelineRepository = FakeActivityTimelineRepository()
        val locationHistoryRepository = FakeLocationHistoryRepository()
        val locationRetryWorker =
            LocationRetryWorker(
                locationProvider = locationProvider,
                locationHistoryRepository = locationHistoryRepository,
                coroutineScope = testScope.backgroundScope,
            )
        val logLocationUseCase = LogLocationUseCase(locationProvider, activityTimelineRepository)
        val logCurrentLocationUseCase =
            LogCurrentLocationUseCase(
                locationProvider = locationProvider,
                locationHistoryRepository = locationHistoryRepository,
                locationRetryWorker = locationRetryWorker,
                canonicalOwnerProvider = TestCanonicalOwnerProvider(),
                deviceIdProvider = TestDeviceIdProvider(),
            )
        val mediaManager = FakeMediaManager()

        val addNoteUseCase =
            AddNoteUseCase(
                repository = journalNotesRepository,
                journalContentRepository = journalContentRepository,
                logLocationUseCase = logLocationUseCase,
                logCurrentLocationUseCase = logCurrentLocationUseCase,
                settingsRepository = FakeLocationTrackingSettingsRepository(),
                mediaManager = mediaManager,
            )
        val deleteEntryDraft = DeleteEntryDraftUseCase(entryDraftRepository, journalNotesRepository)

        val observeEditorData =
            ObserveEditorDataUseCase(
                fetchTodayNotes = FetchTodayNotesUseCase(journalNotesRepository),
                getCurrentUserJournals = GetCurrentUserJournalsUseCase(journalRepository),
                fetchMostRecentDraft = FetchMostRecentDraftUseCase(entryDraftRepository),
                getAllDrafts = GetAllDraftsUseCase(entryDraftRepository),
            )
        val saveEntryUseCase =
            SaveEntryUseCase(
                addNoteUseCase = addNoteUseCase,
                deleteEntryDraft = deleteEntryDraft,
            )
        val draftManager =
            DraftManager(
                updateEntryDraft = UpdateEntryDraftUseCase(entryDraftRepository),
                createEntryDraft = CreateEntryDraftUseCase(entryDraftRepository),
                fetchEntryDraft = FetchEntryDraftUseCase(entryDraftRepository),
                deleteEntryDraft = deleteEntryDraft,
                deleteAllDraftsUseCase = DeleteAllDraftsUseCase(entryDraftRepository),
                cleanupExpiredDraftsUseCase = CleanupExpiredDraftsUseCase(entryDraftRepository),
            )
        val contentLoader =
            ContentLoader(
                fetchEntryUseCase = FetchEntryUseCase(journalNotesRepository),
                getDefaultSelectedJournals =
                    GetDefaultSelectedJournalsUseCase(
                        journalNotesRepository,
                        journalContentRepository,
                    ),
            )

        return EntryEditorViewModel(
            observeEditorData = observeEditorData,
            saveEntryUseCase = saveEntryUseCase,
            draftManager = draftManager,
            contentLoader = contentLoader,
        )
    }
}
