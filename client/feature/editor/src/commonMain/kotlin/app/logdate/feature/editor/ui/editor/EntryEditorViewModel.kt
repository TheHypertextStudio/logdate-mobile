package app.logdate.feature.editor.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.editor.EditorData
import app.logdate.client.domain.editor.ObserveEditorDataUseCase
import app.logdate.client.domain.editor.SaveEntryUseCase
import app.logdate.client.domain.places.CapturePlace
import app.logdate.client.domain.places.ObserveCapturePlaceUseCase
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.feature.editor.navigation.EditorLaunchMode
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.delegate.AudioBlockFinalizer
import app.logdate.feature.editor.ui.editor.delegate.ContentLoader
import app.logdate.feature.editor.ui.editor.delegate.DraftManager
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioRecoverer
import app.logdate.feature.editor.ui.mapper.toDomainBlock
import app.logdate.shared.model.location.VisitMemoryContext
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_error_recording_not_finalized
import kotlin.uuid.Uuid

/**
 * ViewModel for the note editor screen.
 *
 * Owns the current entry and its draft. Today's saved notes remain separate, read-only context;
 * they are never appended to the entry being written.
 */
class EntryEditorViewModel(
    observeEditorData: ObserveEditorDataUseCase,
    internal val saveEntryUseCase: SaveEntryUseCase,
    internal val draftManager: DraftManager,
    internal val contentLoader: ContentLoader,
    /**
     * The fallback finalizer consulted for a block whose own `AudioBlockEditor` hasn't
     * composed/bound a finalizer yet (see [audioBlockFinalizers]).
     *
     * There is no live audio state to consult without a bound, block-scoped
     * `AudioViewModel`, so an already-[AudioCaptureState.Ready] block passes through
     * unchanged and anything still in flight (Recording/Stopping) fails clearly rather
     * than being silently dropped by the save mapper's null return. In practice a real
     * recording's own `AudioBlockEditor` always binds via [bindAudioBlockFinalizer] before
     * Save is reachable, so this default is a safety net, not the common path.
     */
    internal val defaultAudioBlockFinalizer: AudioBlockFinalizer =
        AudioBlockFinalizer { _, currentState ->
            when (currentState) {
                is AudioCaptureState.Recording, is AudioCaptureState.Stopping -> {
                    AudioCaptureState.Failed(Res.string.editor_error_recording_not_finalized)
                }

                else -> {
                    currentState
                }
            }
        },
    internal val pendingAudioRecoverer: PendingAudioRecoverer? = null,
    internal val captureUsage: CaptureUsageReporter = CaptureUsageReporter.None,
    internal val audioDurationResolver: AudioDurationResolver? = null,
    internal val preferences: LogdatePreferencesDataSource? = null,
    internal val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    observeCapturePlace: ObserveCapturePlaceUseCase? = null,
) : ViewModel() {
    /** Draft identity restored before launch-mode effects, so process recreation cannot start another take. */
    val recoverableDraftId: Uuid? get() = savedStateHandle.get<String>("editor.draftId")?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    val defaultEntryMode: StateFlow<EntryStartMode> =
        (preferences?.observeEntryStartMode() ?: flowOf(EntryStartMode.TEXT))
            .catch { error ->
                Napier.e("Could not read default entry mode", error)
                emit(EntryStartMode.TEXT)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, EntryStartMode.TEXT)

    internal var preferredEntryInitialized = false

    fun initializePreferredEntryMode(mode: EntryStartMode? = null) = initializePreferredEntryModeImpl(mode)

    /**
     * Per-block finalizers bound by each block's own `AudioBlockEditor` composable, keyed by
     * block id.
     *
     * Each `AudioBlockEditor` now resolves its own, block-scoped
     * [AudioViewModel][app.logdate.feature.editor.ui.audio.AudioViewModel] instance (Compose's
     * `koinViewModel(key = ...)`, keyed by block id) instead of sharing one instance across every
     * audio block on the editor screen. A single mutable finalizer field could therefore only ever
     * reflect the last block to compose, silently disconnecting every other block's finalizer.
     * This map lets [finalizePendingAudio] look up the correct, live finalizer per block.
     */
    internal val audioBlockFinalizers = mutableMapOf<Uuid, AudioBlockFinalizer>()

    internal val textInputRuntimeId = Uuid.random()

    // Internal mutable state that can be modified by UI
    internal val mutableEditorState =
        MutableStateFlow(
            EditorState(
                isLoading = false,
                disableEmptyBlockCreation = false,
            ),
        )

    internal val draftPersistenceMutex = Mutex()

    // Navigation effects are replayed when an Activity is recreated. Keep the source that has
    // already initialized this ViewModel so a replay cannot overwrite edits that survived the
    // configuration change. A failed load clears the request and remains retryable.
    internal var activeLoadRequest: LoadRequest? = null
    internal var loadGeneration = 0L

    private var detachedMarkContext: String? = null

    fun initializeMarkContext(markId: String?) {
        if (markId != null && markId != detachedMarkContext) {
            mutateEditorContent { it.copy(markId = markId) }
        }
    }

    suspend fun openMarkedMoment(
        markId: String,
        caption: String,
    ) {
        if (markId == detachedMarkContext) return
        initializeMarkContext(markId)
        val loaded = editorState.first { !it.isLoading && !it.isLoadingDrafts }
        val current = mutableEditorState.value
        if (current.blocks.isNotEmpty() || current.draftState is DraftState.Active) return
        val draft = loaded.availableDrafts.filter { it.markId == markId }.maxByOrNull { it.updatedAt }
        if (draft != null) loadDraft(draft.id) else setInitialTextContent(caption)
    }

    fun continueWithoutMarkedMoment() {
        val previousMark = mutableEditorState.value.markId ?: return
        if (mutateEditorContent { it.copy(markId = null, errorMessage = null, isModified = true) }) {
            detachedMarkContext = previousMark
            autoSaveLatestEntry()
        }
    }

    internal var initializedPhotoPromptUri: String? = null

    init {
        viewModelScope.launch {
            try {
                val deleted = draftManager.cleanupExpired()
                if (deleted > 0) {
                    Napier.d("Cleaned up $deleted expired draft(s)")
                }
            } catch (e: Exception) {
                Napier.e("Failed to clean up expired drafts: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            val defaults = contentLoader.loadDefaultJournals()
            if (defaults.isNotEmpty()) {
                mutableEditorState.update { state ->
                    if (!state.isEditingLocked &&
                        !state.shouldExit &&
                        state.selectedJournalIds.isEmpty() &&
                        !state.hasJournalSelectionChanges
                    ) {
                        state.copy(selectedJournalIds = defaults)
                    } else {
                        state
                    }
                }
            }
        }
    }

    // A cold database can take over a second to answer. The editor opens on what it already holds
    // and fills in journals, drafts and today's notes when they arrive.
    private val editorData: StateFlow<EditorData?> =
        observeEditorData().map<EditorData, EditorData?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val editorState: StateFlow<EditorState> = PresentedEditorState(mutableEditorState, editorData, ::presentEditorState)

    private val cameraPlaceSession =
        CameraPlaceSession(viewModelScope, mutableEditorState, observeCapturePlace?.let { use -> { use() } })

    /** The named place the live camera shows; shots are saved with it while [cameraPlaceTagging] is on. */
    val cameraPlace: StateFlow<CapturePlace?> get() = cameraPlaceSession.place

    /** Whether shots taken now are saved with [cameraPlace]. */
    val cameraPlaceTagging: StateFlow<Boolean> get() = cameraPlaceSession.tagging

    /** Chooses whether shots taken while the camera stays open are saved with [cameraPlace]. */
    fun setCameraPlaceTagging(tag: Boolean) = cameraPlaceSession.setTagging(tag)

    private fun presentEditorState(
        currentState: EditorState,
        data: EditorData?,
    ): EditorState {
        if (data == null) return currentState.copy(isLoadingDrafts = true)
        val todayBlockIds =
            data.todayNotes
                .map { it.toDomainBlock().id }
                .toSet()

        val blocks = currentState.blocks
        val readOnlyMap = blocks.associate { it.id to (it.id in todayBlockIds && it.id != currentState.entryEditBase?.targetId) }

        val selectedJournalIds =
            if (
                currentState.selectedJournalIds.isEmpty() &&
                !currentState.hasJournalSelectionChanges &&
                currentState.draftState == DraftState.None &&
                data.journals.isNotEmpty()
            ) {
                listOf(data.journals.first().id)
            } else {
                currentState.selectedJournalIds
            }

        return currentState.copy(
            blocks = blocks,
            readOnlyBlocks = readOnlyMap,
            availableJournals = data.journals,
            selectedJournalIds = selectedJournalIds,
            availableDrafts = data.allDrafts,
            todayNotes = data.todayNotes.filter { it.uid != currentState.entryEditBase?.targetId },
            isLoadingDrafts = false,
            isLoading = currentState.isLoading,
        )
    }

    private var hasUserSelectedJournals = false

    fun setSelectedJournals(journalIds: List<Uuid>) {
        hasUserSelectedJournals = true
        setSelectedJournalsImpl(journalIds)
    }

    fun initializeVisitContext(context: VisitMemoryContext?) = initializeVisitContextImpl(context)

    fun initializeSelectedJournals(journalIds: List<Uuid>) {
        if (!hasUserSelectedJournals) initializeSelectedJournalsImpl(journalIds)
    }

    fun createNewBlock(
        type: BlockType,
        id: Uuid = Uuid.random(),
    ): EntryBlockUiState = createNewBlockImpl(type, id)

    fun updateBlock(
        updatedBlock: EntryBlockUiState,
        baseBlock: EntryBlockUiState? = null,
    ) = updateBlockImpl(tagCameraPlace(updatedBlock), baseBlock)

    internal fun updateBlockFromInput(
        updatedBlock: EntryBlockUiState,
        baseBlock: EntryBlockUiState?,
        inputToken: Uuid?,
    ) = updateBlockImpl(tagCameraPlace(updatedBlock), baseBlock, inputToken, fromInput = true)

    private fun tagCameraPlace(updated: EntryBlockUiState): EntryBlockUiState =
        cameraPlaceSession.tag(mutableEditorState.value.blocks.firstOrNull { it.id == updated.id }, updated)

    fun chooseCreationType(
        targetId: Uuid?,
        type: BlockType,
    ) = chooseCreationTypeImpl(targetId, type)

    fun beginBlockCreation(type: BlockType) = beginBlockCreationImpl(type)

    fun appendTextBlock(text: String) = appendTextBlockImpl(text)

    fun removeBlock(blockId: Uuid) = removeBlockImpl(blockId)

    fun autoSaveEntry(state: EditorState) = autoSaveEntryImpl(state)

    fun autoSaveLatestEntry() = autoSaveLatestEntryImpl()

    fun draftIdForHandoff(): Uuid? = draftIdForHandoffImpl()

    suspend fun persistDraft(state: EditorState): Uuid? = persistDraftImpl(state)

    suspend fun persistAudioRecordingTarget(
        blockId: Uuid,
        filePath: String,
        fallbackFilePath: String? = null,
    ): Boolean = persistAudioRecordingTargetImpl(blockId, filePath, fallbackFilePath)

    suspend fun persistCameraCaptureTarget(
        blockId: Uuid,
        filePath: String,
        mediaType: CapturedMediaType,
    ): Boolean = persistCameraCaptureTargetImpl(blockId, filePath, mediaType)

    suspend fun saveAsDraft(state: EditorState): Boolean = saveAsDraftImpl(state)

    suspend fun discardAndExit(): Boolean = discardAndExitImpl()

    fun saveEntry(state: EditorState) = saveEntryImpl(state)

    fun saveConflictingVersionSeparately() = saveConflictingVersionSeparatelyImpl()

    fun dismissEntryEditConflict() {
        mutableEditorState.update { it.copy(entryEditConflict = false) }
    }

    fun setExpandedBlockId(blockId: Uuid?) = setExpandedBlockIdImpl(blockId)

    fun bindAudioBlockFinalizer(
        blockId: Uuid,
        finalizer: AudioBlockFinalizer,
    ) = bindAudioBlockFinalizerImpl(blockId, finalizer)

    fun clearSingleEmptyBlock(): Boolean = clearSingleEmptyBlockImpl()

    fun dismissExpandedBlockOrClearSingleEmpty(): Boolean = dismissExpandedBlockOrClearSingleEmptyImpl()

    fun resumeFirstEntryDraft() = resumeFirstEntryDraftImpl()

    fun loadDraft(draftId: Uuid) = loadDraftImpl(draftId)

    fun deleteDraft(draftId: Uuid) = deleteDraftImpl(draftId)

    fun deleteAllDrafts() = deleteAllDraftsImpl()

    fun setInitialTextContent(content: String) = setInitialTextContentImpl(content)

    fun initializePhotoPrompt(
        photoUri: String,
        record: Boolean,
    ) = initializePhotoPromptImpl(photoUri, record)

    fun initializeLaunchMode(mode: EditorLaunchMode) = initializeLaunchModeImpl(mode)

    fun setInitialAttachments(attachmentUris: List<String>) = setInitialAttachmentsImpl(attachmentUris)

    fun loadExistingEntry(
        entryId: Uuid,
        journalId: Uuid? = null,
    ) = loadExistingEntryImpl(entryId, journalId)

    internal companion object {
        /**
         * MediaRecorder.stop() typically completes in well under a second; the bound is
         * generous so a slow flush on an aging device doesn't cost the user their entry.
         */
        const val AUDIO_FINALIZE_TIMEOUT_MS: Long = 5_000L
    }

    internal sealed interface LoadRequest {
        data class Draft(
            val id: Uuid,
        ) : LoadRequest

        data class Existing(
            val id: Uuid,
            val journalId: Uuid?,
        ) : LoadRequest
    }
}
