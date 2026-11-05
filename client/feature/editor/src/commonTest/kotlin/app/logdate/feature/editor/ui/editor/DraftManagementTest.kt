package app.logdate.feature.editor.ui.editor

import androidx.lifecycle.viewModelScope
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
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.journals.PendingMediaType
import app.logdate.client.text.UiText
import app.logdate.feature.editor.ui.camera.CapturedMediaType
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
import app.logdate.shared.model.Journal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_error_save_draft_failed
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests for draft management in the editor.
 * Verifies draft creation, loading, deletion, and state persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftManagementTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var viewModel: EntryEditorViewModel
    private lateinit var entryDraftRepository: FakeEntryDraftRepository
    private lateinit var journalNotesRepository: FakeJournalNotesRepository
    private lateinit var journalContentRepository: FakeJournalContentRepository
    private lateinit var journalRepository: FakeJournalRepository
    private lateinit var draftManager: DraftManager

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        journalNotesRepository = FakeJournalNotesRepository()
        journalContentRepository = FakeJournalContentRepository()
        journalRepository = FakeJournalRepository()
        entryDraftRepository = FakeEntryDraftRepository()

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
        draftManager =
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

        viewModel =
            EntryEditorViewModel(
                observeEditorData = observeEditorData,
                saveEntryUseCase = saveEntryUseCase,
                draftManager = draftManager,
                contentLoader = contentLoader,
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun textNote(content: String): JournalNote.Text {
        val now = Clock.System.now()
        return JournalNote.Text(
            creationTimestamp = now,
            lastUpdated = now,
            content = content,
        )
    }

    @Test
    fun `background autosave captures edits before the public editor flow catches up`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()
            viewModel.updateBlock(block.copy(content = "Typed just before Home"))
            viewModel.autoSaveLatestEntry()
            advanceUntilIdle()

            val stored = entryDraftRepository.getDrafts().first().single()
            assertEquals("Typed just before Home", (stored.notes.single() as JournalNote.Text).content)
        }

    @Test
    fun `text not yet saved is handed off under the id its first draft is then saved with`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()
            viewModel.updateBlock(block.copy(content = "Halfway up the ridge trail"))

            val handedOff = viewModel.draftIdForHandoff()
            advanceUntilIdle()

            val stored = entryDraftRepository.getDrafts().first().single()
            assertEquals(handedOff, stored.id)
            assertEquals(DraftState.Active(stored.id), viewModel.editorState.value.draftState)
        }

    @Test
    fun `an empty new entry hands off no draft`() =
        testScope.runTest {
            advanceUntilIdle()
            assertEquals(null, viewModel.draftIdForHandoff())
        }

    @Test
    fun `draft id survives combine reemission`() =
        testScope.runTest {
            // Create a text block with content
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()

            viewModel.updateBlock(block.copy(content = "Test content"))
            advanceUntilIdle()

            // Trigger auto-save
            viewModel.autoSaveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            // draftState should be Active even after combine re-emits
            val state = viewModel.editorState.value
            assertTrue(
                state.draftState is DraftState.Active,
                "draftState should be Active after auto-save",
            )
        }

    @Test
    fun `auto save waits for durable draft persistence`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Durable content"))
            advanceUntilIdle()
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate

            val save = async { viewModel.persistDraft(viewModel.editorState.value) }
            runCurrent()

            assertEquals(1, entryDraftRepository.createDraftCallCount)
            assertFalse(save.isCompleted, "Autosave must await the local repository write")
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)

            persistenceGate.complete(Unit)
            val draftId = save.await()
            advanceUntilIdle()

            assertNotNull(draftId)
            assertEquals(DraftState.Active(draftId), viewModel.editorState.value.draftState)
        }

    @Test
    fun `concurrent auto saves preserve single draft identity`() =
        testScope.runTest {
            val firstBlock = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(firstBlock.copy(content = "First version"))
            advanceUntilIdle()
            val firstState = viewModel.editorState.value
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate

            val firstSave = async { viewModel.persistDraft(firstState) }
            runCurrent()

            viewModel.updateBlock(firstBlock.copy(content = "Second version"))
            advanceUntilIdle()
            val secondSave = async { viewModel.persistDraft(viewModel.editorState.value) }
            runCurrent()

            persistenceGate.complete(Unit)
            val firstId = firstSave.await()
            val secondId = secondSave.await()
            advanceUntilIdle()

            val drafts = entryDraftRepository.getDrafts().first()
            assertNotNull(firstId)
            assertEquals(firstId, secondId)
            assertEquals(1, drafts.size)
            assertEquals(1, entryDraftRepository.createDraftCallCount)
            assertEquals(1, entryDraftRepository.updateDraftCallCount)
        }

    @Test
    fun `clearing active draft persists empty content`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Content that will be cleared"))
            advanceUntilIdle()
            val draftId = viewModel.persistDraft(viewModel.editorState.value)
            advanceUntilIdle()
            assertNotNull(draftId)

            viewModel.removeBlock(block.id)
            advanceUntilIdle()
            val returnedId = viewModel.persistDraft(viewModel.editorState.value)
            advanceUntilIdle()

            val storedDraft = entryDraftRepository.getDraft(draftId).first().getOrThrow()
            assertEquals(draftId, returnedId)
            assertTrue(storedDraft.notes.isEmpty(), "Clearing the editor must clear the durable draft")
            assertTrue(storedDraft.pendingMedia.isEmpty())
        }

    @Test
    fun `clearing active draft sole block cannot reset to picker before empty snapshot persists`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Content that must be cleared durably"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))

            viewModel.updateBlock(block.copy(content = ""))
            advanceUntilIdle()

            assertFalse(
                viewModel.clearSingleEmptyBlock(),
                "An active draft must route Back through save/discard instead of clearing its dirty bit",
            )
            assertTrue(viewModel.editorState.value.isDirty)
            assertFalse(viewModel.editorState.value.canExitWithoutSaving)

            assertEquals(draftId, viewModel.persistDraft(viewModel.editorState.value))
            val storedDraft = entryDraftRepository.getDraft(draftId).first().getOrThrow()
            assertTrue(storedDraft.notes.isEmpty())
        }

    @Test
    fun `save as draft waits for durable draft persistence`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Exit draft"))
            advanceUntilIdle()
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate

            val save = async { viewModel.saveAsDraft(viewModel.editorState.value) }
            runCurrent()

            assertFalse(save.isCompleted, "Explicit draft save must await durable persistence")
            assertFalse(viewModel.editorState.value.shouldExit)

            persistenceGate.complete(Unit)
            assertTrue(save.await())
            advanceUntilIdle()
            assertNull(viewModel.editorState.value.errorMessage)
        }

    @Test
    fun `save as draft persists latest changes before returning success`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Click-time content"))
            advanceUntilIdle()
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate

            val save = async { viewModel.saveAsDraft(viewModel.editorState.value) }
            runCurrent()

            val selectedJournalId = Uuid.random()
            viewModel.updateBlock(block.copy(content = "Latest content while saving"))
            viewModel.setSelectedJournals(listOf(selectedJournalId))
            runCurrent()

            persistenceGate.complete(Unit)
            assertTrue(save.await())
            advanceUntilIdle()

            val stored = entryDraftRepository.getDrafts().first().single()
            val storedText = stored.notes.single() as JournalNote.Text
            assertEquals("Latest content while saving", storedText.content)
            assertEquals(listOf(selectedJournalId), stored.selectedJournalIds)
            assertEquals(1, entryDraftRepository.createDraftCallCount)
            assertEquals(1, entryDraftRepository.updateDraftCallCount)
        }

    @Test
    fun `save as draft failure keeps editor open and reports error`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Unsaved exit draft"))
            advanceUntilIdle()
            entryDraftRepository.createDraftFailure = IllegalStateException("disk full")

            val saved = viewModel.saveAsDraft(viewModel.editorState.value)
            advanceUntilIdle()

            assertFalse(saved)
            assertFalse(viewModel.editorState.value.shouldExit)
            val error = assertIs<UiText.Resource>(viewModel.editorState.value.errorMessage)
            assertEquals(Res.string.editor_error_save_draft_failed, error.resource)
            assertTrue(
                error.args
                    .single()
                    .toString()
                    .contains("disk full"),
            )
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)
        }

    @Test
    fun `manual save waits for in flight durable auto save`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Publish after durable autosave"))
            advanceUntilIdle()
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate

            val autoSave = async { viewModel.persistDraft(viewModel.editorState.value) }
            runCurrent()
            viewModel.updateBlock(block.copy(content = "Latest content at publish time"))
            runCurrent()
            viewModel.saveEntry(viewModel.editorState.value)
            runCurrent()

            assertFalse(
                viewModel.editorState.value.shouldExit,
                "Publish must wait for an in-flight local draft write",
            )

            persistenceGate.complete(Unit)
            autoSave.await()
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertTrue(finalState.shouldExit)
            assertEquals(DraftState.None, finalState.draftState)
            assertTrue(entryDraftRepository.getDrafts().first().isEmpty())
            val published = journalNotesRepository.allNotesObserved.first().single() as JournalNote.Text
            assertEquals("Latest content at publish time", published.content)
        }

    @Test
    fun `publish admission rejects late text journal and block mutations`() =
        testScope.runTest {
            val initialJournalId = Uuid.random()
            val rejectedJournalId = Uuid.random()
            val originalBlock = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(originalBlock.copy(content = "Snapshot admitted for publish"))
            viewModel.initializeSelectedJournals(listOf(initialJournalId))
            advanceUntilIdle()
            val publishGate = CompletableDeferred<Unit>()
            journalNotesRepository.createGate = publishGate

            viewModel.saveEntry(viewModel.editorState.value)
            runCurrent()

            viewModel.updateBlock(originalBlock.copy(content = "Late text that must be rejected"))
            viewModel.setSelectedJournals(listOf(rejectedJournalId))
            viewModel.createNewBlock(BlockType.IMAGE, Uuid.random())
            runCurrent()

            publishGate.complete(Unit)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertTrue(finalState.shouldExit)
            assertEquals(listOf(initialJournalId), finalState.selectedJournalIds)
            assertEquals(1, finalState.blocks.size)
            assertEquals(
                "Snapshot admitted for publish",
                (finalState.blocks.single() as TextBlockUiState).content,
            )
            val published = journalNotesRepository.allNotesObserved.first().single() as JournalNote.Text
            assertEquals("Snapshot admitted for publish", published.content)
        }

    @Test
    fun `publish does not clear editor when durable fingerprint changes during save`() =
        testScope.runTest {
            val originalBlock = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(originalBlock.copy(content = "Publish with invariant"))
            advanceUntilIdle()
            val publishGate = CompletableDeferred<Unit>()
            journalNotesRepository.createGate = publishGate

            viewModel.saveEntry(viewModel.editorState.value)
            runCurrent()

            journalRepository.create(
                Journal(
                    id = Uuid.random(),
                    title = "Default appearing during save",
                ),
            )
            runCurrent()
            publishGate.complete(Unit)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertFalse(finalState.shouldExit, "A changed durable fingerprint must fail closed")
            assertTrue(finalState.isDirty, "The editor must retain the changed content for review")
            assertNotNull(finalState.errorMessage)
        }

    @Test
    fun `rapid double submit publishes entry only once`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Publish once"))
            advanceUntilIdle()
            val submitState = viewModel.editorState.value

            viewModel.saveEntry(submitState)
            viewModel.saveEntry(submitState)
            advanceUntilIdle()

            val published = journalNotesRepository.allNotesObserved.first()
            assertEquals(1, published.size)
            assertEquals("Publish once", (published.single() as JournalNote.Text).content)
        }

    @Test
    fun `publish cancellation does not become ui error`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Cancelled publish"))
            advanceUntilIdle()
            journalNotesRepository.createFailure = CancellationException("publish cancelled")

            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            assertNull(viewModel.editorState.value.errorMessage)
            assertFalse(viewModel.editorState.value.shouldExit)
            assertFalse(viewModel.editorState.value.isSaving)
        }

    @Test
    fun `publish cancellation while waiting for draft mutex clears exclusive save state`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Waiting for draft mutex"))
            advanceUntilIdle()
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate
            val inFlightDraftSave = async { viewModel.persistDraft(viewModel.editorState.value) }
            runCurrent()
            val viewModelJob = assertNotNull(viewModel.viewModelScope.coroutineContext[Job])
            val childrenBeforePublish = viewModelJob.children.toSet()

            viewModel.saveEntry(viewModel.editorState.value)
            runCurrent()
            val publishJob =
                viewModelJob.children
                    .filterNot { it in childrenBeforePublish }
                    .single()
            publishJob.cancel(CancellationException("cancel while waiting for draft mutex"))
            runCurrent()

            val cancelledState = viewModel.editorState.value
            assertFalse(cancelledState.isSaving)
            assertFalse(cancelledState.isEditingLocked)
            assertNull(cancelledState.errorMessage)
            assertFalse(cancelledState.shouldExit)

            persistenceGate.complete(Unit)
            inFlightDraftSave.await()
        }

    @Test
    fun `empty draft delete cancellation clears exclusive save state`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Durable content before clear"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            viewModel.updateBlock(block.copy(content = ""))
            entryDraftRepository.setDeletionFailure(
                draftId = draftId,
                failure = CancellationException("empty draft deletion cancelled"),
            )
            advanceUntilIdle()

            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            val cancelledState = viewModel.editorState.value
            assertFalse(cancelledState.isSaving)
            assertFalse(cancelledState.isEditingLocked)
            assertNull(cancelledState.errorMessage)
            assertFalse(cancelledState.shouldExit)
        }

    @Test
    fun `journal selection only draft persists and loads selection`() =
        testScope.runTest {
            val selectedJournalId = Uuid.random()

            viewModel.setSelectedJournals(listOf(selectedJournalId))
            advanceUntilIdle()

            assertTrue(viewModel.editorState.value.isModified)
            val draftId = viewModel.persistDraft(viewModel.editorState.value)
            assertNotNull(draftId)
            val storedDraft = entryDraftRepository.getDraft(draftId).first().getOrThrow()
            assertEquals(listOf(selectedJournalId), storedDraft.selectedJournalIds)

            viewModel.setSelectedJournals(emptyList())
            viewModel.loadDraft(draftId)
            advanceUntilIdle()

            assertEquals(listOf(selectedJournalId), viewModel.editorState.value.selectedJournalIds)
        }

    @Test
    fun `remounting route context preserves the users changed journal selection`() =
        testScope.runTest {
            val navigationJournalId = Uuid.random()
            val selectedJournalId = Uuid.random()
            viewModel.initializeSelectedJournals(listOf(navigationJournalId))
            viewModel.setSelectedJournals(listOf(selectedJournalId))
            viewModel.autoSaveLatestEntry()
            advanceUntilIdle()
            viewModel.initializeSelectedJournals(listOf(navigationJournalId))
            advanceUntilIdle()

            assertEquals(listOf(selectedJournalId), viewModel.editorState.value.selectedJournalIds)
        }

    @Test
    fun `navigation journal context overrides initialized default without dirtying editor`() =
        testScope.runTest {
            val defaultJournalId = Uuid.random()
            val navigationJournalId = Uuid.random()

            viewModel.initializeSelectedJournals(listOf(defaultJournalId))
            viewModel.initializeSelectedJournals(listOf(navigationJournalId))
            advanceUntilIdle()

            assertEquals(listOf(navigationJournalId), viewModel.editorState.value.selectedJournalIds)
            assertFalse(viewModel.editorState.value.isModified)
        }

    @Test
    fun `clearing transient empty block preserves journal selection dirty state`() =
        testScope.runTest {
            val selectedJournalId = Uuid.random()
            viewModel.setSelectedJournals(listOf(selectedJournalId))
            viewModel.createNewBlock(BlockType.TEXT)

            assertTrue(viewModel.clearSingleEmptyBlock())
            advanceUntilIdle()
            val stateAfterPickerReset = viewModel.editorState.value
            assertTrue(stateAfterPickerReset.blocks.isEmpty())
            assertTrue(stateAfterPickerReset.isDirty)
            assertFalse(stateAfterPickerReset.canExitWithoutSaving)

            val draftId = assertNotNull(viewModel.persistDraft(stateAfterPickerReset))
            val stored = entryDraftRepository.getDraft(draftId).first().getOrThrow()
            assertEquals(listOf(selectedJournalId), stored.selectedJournalIds)
        }

    @Test
    fun `deselecting every journal remains dirty and creates empty selection draft`() =
        testScope.runTest {
            viewModel.initializeSelectedJournals(listOf(Uuid.random()))
            viewModel.setSelectedJournals(emptyList())
            advanceUntilIdle()

            assertTrue(viewModel.editorState.value.isDirty)
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            val stored = entryDraftRepository.getDraft(draftId).first().getOrThrow()
            assertTrue(stored.selectedJournalIds.isEmpty())
        }

    @Test
    fun `explicit empty journal selection overrides derived default after persistence`() =
        testScope.runTest {
            val defaultJournalId = Uuid.random()
            journalRepository.create(Journal(id = defaultJournalId, title = "Default"))
            advanceUntilIdle()
            assertEquals(listOf(defaultJournalId), viewModel.editorState.value.selectedJournalIds)

            viewModel.setSelectedJournals(emptyList())
            advanceUntilIdle()

            assertTrue(viewModel.editorState.value.isDirty)
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            advanceUntilIdle()

            assertTrue(
                entryDraftRepository
                    .getDraft(draftId)
                    .first()
                    .getOrThrow()
                    .selectedJournalIds
                    .isEmpty(),
            )
            assertTrue(
                viewModel.editorState.value
                    .selectedJournalIds
                    .isEmpty(),
            )
        }

    @Test
    fun `immediate save as draft keeps explicit empty journal selection authoritative`() =
        testScope.runTest {
            val defaultJournalId = Uuid.random()
            journalRepository.create(Journal(id = defaultJournalId, title = "Default"))
            advanceUntilIdle()
            assertEquals(listOf(defaultJournalId), viewModel.editorState.value.selectedJournalIds)

            viewModel.setSelectedJournals(emptyList())
            val saved = viewModel.saveAsDraft(viewModel.editorState.value)

            assertTrue(saved)
            val stored = entryDraftRepository.getDrafts().first().single()
            assertTrue(stored.selectedJournalIds.isEmpty())
        }

    @Test
    fun `publish after explicit empty save draft does not restore combined default`() =
        testScope.runTest {
            val defaultJournalId = Uuid.random()
            journalRepository.create(Journal(id = defaultJournalId, title = "Default"))
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "No journal publish"))
            advanceUntilIdle()

            viewModel.setSelectedJournals(emptyList())
            assertTrue(viewModel.saveAsDraft(viewModel.editorState.value))
            assertTrue(
                entryDraftRepository
                    .getDrafts()
                    .first()
                    .single()
                    .selectedJournalIds
                    .isEmpty(),
            )
            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            assertTrue(journalContentRepository.addedJournalIds.isEmpty())
        }

    @Test
    fun `derived default journal stays selected once the entry has a draft`() =
        testScope.runTest {
            val defaultJournalId = Uuid.random()
            journalRepository.create(Journal(id = defaultJournalId, title = "Default"))
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Filed by default"))
            advanceUntilIdle()
            assertEquals(listOf(defaultJournalId), viewModel.editorState.value.selectedJournalIds)

            assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            advanceUntilIdle()

            assertEquals(listOf(defaultJournalId), viewModel.editorState.value.selectedJournalIds)
            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()
            assertEquals(listOf(defaultJournalId), journalContentRepository.addedJournalIds.distinct())
        }

    @Test
    fun `draft manager rethrows load cancellation`() =
        testScope.runTest {
            entryDraftRepository.getDraftFailure = CancellationException("load cancelled")

            assertFailsWith<CancellationException> {
                draftManager.loadDraft(Uuid.random())
            }
        }

    @Test
    fun `draft manager rethrows delete cancellation`() =
        testScope.runTest {
            val draftId = Uuid.random()
            entryDraftRepository.setDeletionFailure(
                draftId = draftId,
                failure = CancellationException("delete cancelled"),
            )

            assertFailsWith<CancellationException> {
                draftManager.deleteDraft(draftId)
            }
        }

    @Test
    fun `draft manager rethrows delete all cancellation`() =
        testScope.runTest {
            entryDraftRepository.deleteAllDraftsFailure = CancellationException("delete all cancelled")

            assertFailsWith<CancellationException> {
                draftManager.deleteAllDrafts()
            }
        }

    @Test
    fun `retry after partial draft create preserves single identity`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Retry stable identity"))
            advanceUntilIdle()
            entryDraftRepository.createDraftFailureAfterCommit =
                IllegalStateException("store acknowledged after commit")

            val firstFailure = runCatching { viewModel.persistDraft(viewModel.editorState.value) }
            assertTrue(firstFailure.isFailure)

            val retryId = viewModel.persistDraft(viewModel.editorState.value)
            assertNotNull(retryId)

            val drafts = entryDraftRepository.getDrafts().first()
            assertEquals(1, drafts.size)
            assertEquals(retryId, drafts.single().id)
            assertEquals(2, entryDraftRepository.createDraftCallCount)
        }

    @Test
    fun `publish after partial draft create deletes retained candidate`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Publish after partial draft create"))
            advanceUntilIdle()
            entryDraftRepository.createDraftFailureAfterCommit =
                IllegalStateException("draft committed before acknowledgement")

            val failedDraftSave = runCatching { viewModel.persistDraft(viewModel.editorState.value) }
            assertTrue(failedDraftSave.isFailure)
            assertEquals(1, entryDraftRepository.getDrafts().first().size)
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)

            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            assertTrue(viewModel.editorState.value.shouldExit)
            assertTrue(
                entryDraftRepository.getDrafts().first().isEmpty(),
                "Publish must delete the post-commit candidate retained after a failed acknowledgement",
            )
        }

    @Test
    fun `discard deletes active draft before exit`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Discard durable draft"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))

            val discarded = viewModel.discardAndExit()
            advanceUntilIdle()

            assertTrue(discarded)
            assertTrue(entryDraftRepository.getDrafts().first().none { it.id == draftId })
            assertTrue(viewModel.editorState.value.shouldExit)
            assertEquals(EditorExitReason.DISCARDED, viewModel.editorState.value.exitReason)
        }

    @Test
    fun `discard deletion failure fails closed`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Keep when deletion fails"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            entryDraftRepository.setDeletionFailure(draftId)

            val discarded = viewModel.discardAndExit()
            advanceUntilIdle()

            assertFalse(discarded)
            assertTrue(entryDraftRepository.getDrafts().first().any { it.id == draftId })
            assertFalse(viewModel.editorState.value.shouldExit)
            assertFalse(viewModel.editorState.value.isSaving)
            assertFalse(viewModel.editorState.value.isEditingLocked)
            assertNotNull(viewModel.editorState.value.errorMessage)
        }

    @Test
    fun `discard deletes retained partial create before exit`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Discard retained candidate"))
            advanceUntilIdle()
            entryDraftRepository.createDraftFailureAfterCommit =
                IllegalStateException("draft committed before acknowledgement")
            assertTrue(runCatching { viewModel.persistDraft(viewModel.editorState.value) }.isFailure)
            assertEquals(1, entryDraftRepository.getDrafts().first().size)

            val discarded = viewModel.discardAndExit()
            advanceUntilIdle()

            assertTrue(discarded)
            assertTrue(entryDraftRepository.getDrafts().first().isEmpty())
            assertTrue(viewModel.editorState.value.shouldExit)
            assertEquals(EditorExitReason.DISCARDED, viewModel.editorState.value.exitReason)
        }

    @Test
    fun `discard cancellation fails closed and releases editor lock`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Retain cancelled discard"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            val deleteGate = CompletableDeferred<Unit>()
            entryDraftRepository.deleteDraftGate = deleteGate

            val discard = async { viewModel.discardAndExit() }
            runCurrent()
            assertTrue(viewModel.editorState.value.isSaving)
            assertTrue(viewModel.editorState.value.isEditingLocked)

            discard.cancel(CancellationException("discard cancelled"))
            assertFailsWith<CancellationException> { discard.await() }
            advanceUntilIdle()

            assertTrue(entryDraftRepository.getDrafts().first().any { it.id == draftId })
            assertFalse(viewModel.editorState.value.shouldExit)
            assertFalse(viewModel.editorState.value.isSaving)
            assertFalse(viewModel.editorState.value.isEditingLocked)
        }

    @Test
    fun `delete all waits for in flight draft persistence`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Delete after write"))
            advanceUntilIdle()
            val persistenceGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = persistenceGate

            val save = async { viewModel.persistDraft(viewModel.editorState.value) }
            runCurrent()
            viewModel.deleteAllDrafts()
            runCurrent()

            persistenceGate.complete(Unit)
            save.await()
            advanceUntilIdle()

            assertTrue(entryDraftRepository.getDrafts().first().isEmpty())
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)
        }

    @Test
    fun `deleting active draft invalidates queued autosave snapshot`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Delete without resurrection"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            advanceUntilIdle()
            val staleSnapshot = viewModel.editorState.value

            viewModel.deleteDraft(draftId)
            advanceUntilIdle()

            val staleResult = viewModel.persistDraft(staleSnapshot)
            advanceUntilIdle()

            assertNull(staleResult, "A queued snapshot must not recreate an explicitly deleted draft")
            assertTrue(entryDraftRepository.getDrafts().first().isEmpty())
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)
        }

    @Test
    fun `deleting all drafts invalidates queued autosave snapshot`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Delete all without resurrection"))
            advanceUntilIdle()
            assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            advanceUntilIdle()
            val staleSnapshot = viewModel.editorState.value

            viewModel.deleteAllDrafts()
            advanceUntilIdle()

            val staleResult = viewModel.persistDraft(staleSnapshot)
            advanceUntilIdle()

            assertNull(staleResult, "A queued snapshot must not recreate drafts after delete all")
            assertTrue(entryDraftRepository.getDrafts().first().isEmpty())
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)
        }

    @Test
    fun `delete draft completion preserves edit made while deletion is suspended`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Persisted before delete"))
            advanceUntilIdle()
            val draftId = assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            val deleteGate = CompletableDeferred<Unit>()
            entryDraftRepository.deleteDraftGate = deleteGate

            viewModel.deleteDraft(draftId)
            runCurrent()
            viewModel.updateBlock(block.copy(content = "Edit made during delete"))
            runCurrent()
            deleteGate.complete(Unit)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertEquals(DraftState.None, finalState.draftState)
            assertEquals(
                "Edit made during delete",
                (finalState.blocks.single() as TextBlockUiState).content,
            )
            assertTrue(finalState.isDirty, "Delete completion must not clear a newer edit")
        }

    @Test
    fun `delete all completion preserves edit made while deletion is suspended`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Persisted before delete all"))
            advanceUntilIdle()
            assertNotNull(viewModel.persistDraft(viewModel.editorState.value))
            val deleteGate = CompletableDeferred<Unit>()
            entryDraftRepository.deleteAllDraftsGate = deleteGate

            viewModel.deleteAllDrafts()
            runCurrent()
            viewModel.updateBlock(block.copy(content = "Edit made during delete all"))
            runCurrent()
            deleteGate.complete(Unit)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertEquals(DraftState.None, finalState.draftState)
            assertEquals(
                "Edit made during delete all",
                (finalState.blocks.single() as TextBlockUiState).content,
            )
            assertTrue(finalState.isDirty, "Delete-all completion must not clear a newer edit")
        }

    @Test
    fun `load draft wins after older autosave completes`() =
        testScope.runTest {
            val targetDraftId = entryDraftRepository.createDraft(listOf(textNote("Loaded target")))
            val oldBlock = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(oldBlock.copy(content = "Older editor content"))
            advanceUntilIdle()
            val saveGate = CompletableDeferred<Unit>()
            entryDraftRepository.createDraftGate = saveGate

            val oldAutoSave = async { viewModel.persistDraft(viewModel.editorState.value) }
            runCurrent()
            viewModel.loadDraft(targetDraftId)
            runCurrent()
            saveGate.complete(Unit)
            oldAutoSave.await()
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertEquals(DraftState.Active(targetDraftId), finalState.draftState)
            assertEquals(
                "Loaded target",
                (finalState.blocks.single() as TextBlockUiState).content,
            )
        }

    @Test
    fun `first entry resumes the most recently updated draft`() =
        testScope.runTest {
            val older = entryDraftRepository.createDraft(listOf(textNote("Older draft")))
            val recent = entryDraftRepository.createDraft(listOf(textNote("Recent draft")))
            advanceUntilIdle()

            viewModel.resumeFirstEntryDraft()
            advanceUntilIdle()

            assertEquals(DraftState.Active(recent), viewModel.editorState.value.draftState)
            assertEquals(
                "Recent draft",
                (
                    viewModel.editorState.value.blocks
                        .single() as TextBlockUiState
                ).content,
            )
            assertTrue(older != recent)
        }

    @Test
    fun `queued old autosave cannot retarget content to newly loaded draft`() =
        testScope.runTest {
            val targetDraftId = entryDraftRepository.createDraft(listOf(textNote("Loaded target")))
            val oldBlock = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(oldBlock.copy(content = "Queued old content"))
            advanceUntilIdle()
            val staleState = viewModel.editorState.value
            val loadGate = CompletableDeferred<Unit>()
            entryDraftRepository.getDraftGate = loadGate

            viewModel.loadDraft(targetDraftId)
            runCurrent()
            val queuedAutoSave = async { viewModel.persistDraft(staleState) }
            runCurrent()
            loadGate.complete(Unit)
            val queuedResult = queuedAutoSave.await()
            advanceUntilIdle()

            assertNull(queuedResult, "A snapshot from before load must be rejected")
            val finalState = viewModel.editorState.value
            assertEquals(DraftState.Active(targetDraftId), finalState.draftState)
            assertEquals(
                "Loaded target",
                (finalState.blocks.single() as TextBlockUiState).content,
            )
        }

    @Test
    fun `slow load completion does not overwrite newer editor mutation`() =
        testScope.runTest {
            val targetDraftId = entryDraftRepository.createDraft(listOf(textNote("Loaded target")))
            val currentBlock = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(currentBlock.copy(content = "Before load"))
            advanceUntilIdle()
            val loadGate = CompletableDeferred<Unit>()
            entryDraftRepository.getDraftGate = loadGate

            viewModel.loadDraft(targetDraftId)
            runCurrent()
            viewModel.updateBlock(currentBlock.copy(content = "Newer edit while loading"))
            runCurrent()
            loadGate.complete(Unit)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertEquals(
                "Newer edit while loading",
                (finalState.blocks.single() as TextBlockUiState).content,
            )
            assertFalse(finalState.draftState == DraftState.Active(targetDraftId))
            assertTrue(finalState.isDirty)
        }

    @Test
    fun `save entry deletes draft after auto save`() =
        testScope.runTest {
            // Create content and auto-save to create a draft
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()

            viewModel.updateBlock(block.copy(content = "Content to publish"))
            advanceUntilIdle()

            viewModel.autoSaveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            // Verify draft exists
            val stateBeforeSave = viewModel.editorState.value
            assertTrue(
                stateBeforeSave.draftState is DraftState.Active,
                "draftState should be Active before save",
            )

            // Now save the entry (publish)
            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            // Draft should be deleted from the repository
            val finalState = viewModel.editorState.value
            val remainingDrafts = finalState.availableDrafts
            assertTrue(remainingDrafts.isEmpty(), "Draft should be deleted after save")
            assertEquals(DraftState.None, finalState.draftState, "Draft state should reset after save")
            assertFalse(finalState.isModified, "Editor should no longer be marked modified after save")
        }

    @Test
    fun `save entry after clearing active draft deletes old durable content before exit`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            viewModel.updateBlock(block.copy(content = "Old durable content"))
            advanceUntilIdle()
            assertNotNull(viewModel.persistDraft(viewModel.editorState.value))

            viewModel.updateBlock(block.copy(content = ""))
            advanceUntilIdle()
            viewModel.saveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            assertTrue(viewModel.editorState.value.shouldExit)
            assertEquals(DraftState.None, viewModel.editorState.value.draftState)
            assertTrue(
                entryDraftRepository.getDrafts().first().isEmpty(),
                "Publishing an empty cleared draft must not leave its old content behind",
            )
        }

    @Test
    fun `save entry deletes draft when caller state misses latest autosave`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()

            viewModel.updateBlock(block.copy(content = "Content with stale caller state"))
            advanceUntilIdle()

            val stalePreAutosaveState = viewModel.editorState.value
            assertEquals(DraftState.None, stalePreAutosaveState.draftState)

            viewModel.autoSaveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            assertTrue(
                viewModel.editorState.value.draftState is DraftState.Active,
                "draftState should be Active after autosave",
            )

            viewModel.saveEntry(stalePreAutosaveState)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertTrue(finalState.availableDrafts.isEmpty(), "Draft should be deleted even with stale caller state")
            assertEquals(DraftState.None, finalState.draftState, "Draft state should reset after save")
            assertTrue(finalState.shouldExit, "Successful save should mark the editor for exit")
        }

    @Test
    fun `delayed auto save after successful save does not recreate draft`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()

            viewModel.updateBlock(block.copy(content = "Content to publish"))
            advanceUntilIdle()

            viewModel.autoSaveEntry(viewModel.editorState.value)
            advanceUntilIdle()

            val staleAutoSaveState = viewModel.editorState.value
            assertTrue(staleAutoSaveState.draftState is DraftState.Active)

            viewModel.saveEntry(staleAutoSaveState)
            advanceUntilIdle()

            viewModel.autoSaveEntry(staleAutoSaveState)
            advanceUntilIdle()

            val finalState = viewModel.editorState.value
            assertTrue(finalState.availableDrafts.isEmpty(), "A stale autosave callback must not recreate drafts")
            assertEquals(DraftState.None, finalState.draftState, "Draft state should remain cleared after save")
            assertTrue(finalState.shouldExit, "Editor should still be in exit state after save")
        }

    @Test
    fun `load draft sets is modified`() =
        testScope.runTest {
            // Pre-populate a draft in the repository
            val draftId =
                entryDraftRepository.createDraft(
                    listOf(
                        JournalNote.Text(
                            uid = Uuid.random(),
                            creationTimestamp = Clock.System.now(),
                            lastUpdated = Clock.System.now(),
                            content = "Draft content",
                        ),
                    ),
                )
            advanceUntilIdle()

            // Load the draft
            viewModel.loadDraft(draftId)
            advanceUntilIdle()

            val state = viewModel.editorState.value
            assertTrue(state.isModified, "isModified should be true after loading a draft")
            assertTrue(state.isDirty, "isDirty should be true after loading a draft with content")
            assertFalse(
                state.canExitWithoutSaving,
                "Should not be able to exit without saving after loading a draft",
            )
        }

    @Test
    fun `a recovered recording keeps the time zone it was started in`() =
        testScope.runTest {
            val block =
                AudioBlockUiState(
                    captureState = AudioCaptureState.Stopping(filePath = "/audio_notes/recording.m4a"),
                    timeZoneId = "Pacific/Kiritimati",
                )

            val draftId = assertNotNull(draftManager.autoSave(EditorState(blocks = listOf(block))))
            val recovered =
                draftManager
                    .loadDraft(draftId)
                    .getOrThrow()
                    .blocks
                    .single()

            assertEquals(block.id, recovered.id)
            assertEquals("Pacific/Kiritimati", recovered.timeZoneId)
        }

    @Test
    fun `a recovered recording from a draft without a zone records none instead of the current one`() =
        testScope.runTest {
            val draftId =
                entryDraftRepository.createDraft(
                    uid = Uuid.random(),
                    notes = emptyList(),
                    pendingMedia =
                        listOf(
                            PendingMediaRecord(
                                blockId = Uuid.random(),
                                mediaType = PendingMediaType.AUDIO,
                                createdAt = Clock.System.now(),
                                filePath = "/audio_notes/older.m4a",
                            ),
                        ),
                    selectedJournalIds = emptyList(),
                )

            val recovered =
                draftManager
                    .loadDraft(draftId)
                    .getOrThrow()
                    .blocks
                    .single()

            assertNull(recovered.timeZoneId)
        }

    @Test
    fun `camera target is durable before capture and reopens in the same draft block`() =
        testScope.runTest {
            val block = viewModel.createNewBlock(BlockType.CAMERA) as CameraBlockUiState
            advanceUntilIdle()
            val target = "/data/user/0/studio.hypertext.logdate/files/camera-captures/capture.jpg.tmp"

            assertTrue(viewModel.persistCameraCaptureTarget(block.id, target, CapturedMediaType.PHOTO))

            val saved = entryDraftRepository.getDrafts().first().single()
            assertEquals(PendingMediaType.CAMERA_PHOTO, saved.pendingMedia.single().mediaType)
            assertEquals(target, saved.pendingMedia.single().filePath)
            val reopened =
                draftManager
                    .loadDraft(saved.id)
                    .getOrThrow()
                    .blocks
                    .single() as CameraBlockUiState
            assertEquals(block.id, reopened.id)
            assertEquals(target, reopened.pendingFilePath)
        }

    @Test
    fun `auto save skipped during manual save`() =
        testScope.runTest {
            // Create content
            val block = viewModel.createNewBlock(BlockType.TEXT) as TextBlockUiState
            advanceUntilIdle()

            viewModel.updateBlock(block.copy(content = "Some content"))
            advanceUntilIdle()

            // Start a manual save — this synchronously sets isSaving = true
            viewModel.saveEntry(viewModel.editorState.value)

            // Read the state after isSaving was set but before the coroutine completes.
            // We need to advance once so the combine re-emits with isSaving = true.
            advanceUntilIdle()

            // Verify isSaving was set (the manual save completed and reset it,
            // so verify that no drafts were created as a side effect)
            val drafts = viewModel.editorState.value.availableDrafts
            assertTrue(drafts.isEmpty(), "No drafts should exist after manual save")

            // Now try auto-save after the save completed — should work normally
            // but there's nothing to save since shouldExit is true
            assertFalse(
                viewModel.editorState.value.isSaving,
                "isSaving should be false after save completes",
            )
            assertTrue(
                viewModel.editorState.value.shouldExit,
                "Successful save should mark the editor for exit",
            )
        }

    @Test
    fun `delete all drafts clears all at once`() =
        testScope.runTest {
            // Create multiple drafts
            entryDraftRepository.createDraft(
                listOf(
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Clock.System.now(),
                        lastUpdated = Clock.System.now(),
                        content = "Draft 1",
                    ),
                ),
            )
            entryDraftRepository.createDraft(
                listOf(
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Clock.System.now(),
                        lastUpdated = Clock.System.now(),
                        content = "Draft 2",
                    ),
                ),
            )
            entryDraftRepository.createDraft(
                listOf(
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Clock.System.now(),
                        lastUpdated = Clock.System.now(),
                        content = "Draft 3",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(3, viewModel.editorState.value.availableDrafts.size)

            // Delete all drafts atomically
            viewModel.deleteAllDrafts()
            advanceUntilIdle()

            // All drafts should be gone
            val remaining = viewModel.editorState.value.availableDrafts
            assertTrue(remaining.isEmpty(), "All drafts should be deleted")

            // DraftState should be cleared
            assertEquals(
                DraftState.None,
                viewModel.editorState.value.draftState,
                "DraftState should be None after deleting all drafts",
            )
        }
}
