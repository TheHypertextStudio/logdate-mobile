@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)

package app.logdate.feature.editor.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.logdate.client.text.resolve
import app.logdate.feature.editor.ui.content.blockType
import app.logdate.feature.editor.ui.content.entryDayContext
import app.logdate.feature.editor.ui.dialog.DraftsBottomSheet
import app.logdate.feature.editor.ui.dialog.alert.ConfirmEntryExitDialog
import app.logdate.feature.editor.ui.drop.EntryKeyboardContentReceiver
import app.logdate.feature.editor.ui.drop.entryDropTarget
import app.logdate.feature.editor.ui.drop.entryPasteTarget
import app.logdate.feature.editor.ui.editor.EditorAudioCaptureStartGate
import app.logdate.feature.editor.ui.editor.EditorBackgroundSaveEffect
import app.logdate.feature.editor.ui.editor.EditorExitReason
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel
import app.logdate.feature.editor.ui.editor.LocalAudioCaptureStartGate
import app.logdate.feature.editor.ui.editor.LocalEditorInputTokens
import app.logdate.feature.editor.ui.editor.cancelDraftReveal
import app.logdate.feature.editor.ui.editor.consumeDraftReveal
import app.logdate.feature.editor.ui.editor.delegate.DefaultAudioBlockFinalizer
import app.logdate.feature.editor.ui.editor.dismissOwnedEditorFocus
import app.logdate.feature.editor.ui.editor.rememberEditorAutoSave
import app.logdate.feature.editor.ui.handwriting.EntryPageOverview
import app.logdate.feature.editor.ui.handwriting.StoryCaptureMode
import app.logdate.feature.editor.ui.handwriting.blockType
import app.logdate.feature.editor.ui.handwriting.rememberEntryPageState
import app.logdate.feature.editor.ui.handwriting.rememberHandwritingBlockPresentations
import app.logdate.feature.editor.ui.layout.EditorFocus
import app.logdate.feature.editor.ui.layout.LocalEditorContextControlsVisible
import app.logdate.feature.editor.ui.layout.editorFocus
import app.logdate.feature.editor.ui.state.rememberBlocksUiState
import app.logdate.feature.editor.ui.text.LocalEditorTextInputSession
import app.logdate.feature.editor.ui.text.editorTextInputSession
import app.logdate.ui.platform.rememberLogDateHaptics
import app.logdate.ui.provenance.CapturedOnWatchBanner
import app.logdate.ui.provenance.showsWatchProvenance
import kotlinx.coroutines.launch
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.draft_deleted
import logdate.client.feature.editor.generated.resources.undo
import org.jetbrains.compose.resources.getString
import org.koin.compose.viewmodel.koinViewModel

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Suppress("ktlint:standard:function-naming")
@Composable
fun EntryEditorContent(
    onNavigateBack: () -> Unit,
    onEntrySaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryEditorViewModel = koinViewModel(),
    handwritingEnabled: Boolean = false,
    blockFocusEnabled: Boolean = false,
) {
    val editorState by viewModel.editorState.collectAsState()
    val renderedInputTokens = editorState.creationInputTokens
    val audioCaptureStartGate =
        remember(viewModel, renderedInputTokens) {
            EditorAudioCaptureStartGate(viewModel, renderedInputTokens)
        }
    val defaultStartMode by viewModel.defaultEntryMode.collectAsState()
    val focus = editorState.editorFocus()
    val page = rememberEntryPageState()
    val snackbarHostState = remember { SnackbarHostState() }
    val shouldReturnToPickerOnBack = editorState.shouldReturnToPickerOnBack()
    var journalSelectorExpanded by remember { mutableStateOf(false) }
    val haptics = rememberLogDateHaptics()

    var handwritingProgress by remember { mutableStateOf(0f) }
    var handwritingTarget by remember { mutableStateOf(false) }
    var contextAvailable by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val updateBlock = blockUpdateCallback(editorState.blocks, renderedInputTokens, viewModel::updateBlockFromInput)
    val bindAudioResolver =
        audioResolverCallback(renderedInputTokens, { viewModel.mutableEditorState.value }) { id, resolver ->
            viewModel.bindAudioBlockFinalizer(id, DefaultAudioBlockFinalizer(resolver))
        }
    val handwritingPresentations =
        rememberHandwritingBlockPresentations(
            editorState,
            updateBlock,
            handwritingProgress,
            contextAvailable,
            adaptToTheme = blockFocusEnabled,
            keepInkBelowControls = blockFocusEnabled,
        )
    val activeHandwriting = focus is EditorFocus.Drawing
    LaunchedEffect(activeHandwriting) {
        if (activeHandwriting) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val uiState =
        rememberBlocksUiState(
            editorState = editorState,
            onUpdateBlock = updateBlock,
            blockPresentations = handwritingPresentations,
            allowHandwritingCreation = handwritingEnabled,
            onFocusBlock = { id ->
                journalSelectorExpanded = false
                viewModel.setExpandedBlockId(id)
            },
            onCreateBlock = { type, id ->
                journalSelectorExpanded = false
                viewModel.createNewBlock(type, id)
            },
            onConsumeDraftReveal = { viewModel.consumeDraftReveal(it) },
            onCancelDraftReveal = { viewModel.cancelDraftReveal() },
            onDeleteBlock = viewModel::removeBlock,
            onUpdateJournalSelection = viewModel::setSelectedJournals,
            onPersistAudioRecordingTarget = viewModel::persistAudioRecordingTarget,
            onPersistCameraCaptureTarget = viewModel::persistCameraCaptureTarget,
        )

    // Only live camera capture takes over the entry chrome.
    val activeCamera = focus is EditorFocus.Camera
    val handwritingMotion = activeHandwriting || handwritingTarget
    val isImmersiveBlockActive = activeCamera || handwritingTarget
    LaunchedEffect(editorState.isRecordingAudio) {
        if (editorState.isRecordingAudio) journalSelectorExpanded = false
    }

    // Single float that drives all immersive chrome interpolation (0 = fully immersive, 1 = normal).
    // During a predictive back gesture it's scrubbed in real-time via snapTo; on non-gesture
    // transitions (capture completed, hardware back) it animates with tween(300).
    val scope = rememberCoroutineScope()
    val chromeProgress = remember { Animatable(if (isImmersiveBlockActive) 0f else 1f) }

    LaunchedEffect(isImmersiveBlockActive) {
        if (isImmersiveBlockActive) {
            chromeProgress.snapTo(0f)
        } else {
            chromeProgress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    var showExitConfirmation by remember { mutableStateOf(false) }
    var showDraftsDialog by remember { mutableStateOf(false) }
    var isExitDraftSaving by remember { mutableStateOf(false) }

    val handleEditorBack: () -> Unit = {
        audioCaptureStartGate.runWhenIdle {
            when {
                editorState.isSaving || editorState.shouldExit -> {
                    Unit
                }

                activeCamera || activeHandwriting || (blockFocusEnabled && editorState.expandedBlockId != null) -> {
                    viewModel.dismissExpandedBlockOrClearSingleEmpty()
                    Unit
                }

                shouldReturnToPickerOnBack -> {
                    viewModel.clearSingleEmptyBlock()
                    Unit
                }

                !editorState.canExitWithoutSaving -> {
                    showExitConfirmation = true
                }

                else -> {
                    onNavigateBack()
                }
            }
        }
    }

    val exitBackModifier =
        rememberEntryEditorExitBack(
            enabled =
                !activeCamera &&
                    !activeHandwriting &&
                    !(blockFocusEnabled && editorState.expandedBlockId != null) &&
                    !page.earlierExpanded,
            onCommit = handleEditorBack,
        )

    EditorBackgroundSaveEffect { viewModel.autoSaveLatestEntry() }

    val autoSaveState =
        rememberEditorAutoSave(
            editorState = editorState,
            onAutoSave = { state -> viewModel.persistDraft(state) != null },
            enabled = !editorState.isSaving && !editorState.shouldExit,
        )

    EntryEditConflictFeedback(
        editorState.entryEditConflict,
        snackbarHostState,
        viewModel::saveConflictingVersionSeparately,
        viewModel::dismissEntryEditConflict,
    )
    LaunchedEffect(editorState.errorMessage) {
        editorState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error.resolve())
        }
    }

    LaunchedEffect(editorState.shouldExit, editorState.exitReason) {
        if (editorState.shouldExit) {
            when (editorState.exitReason) {
                EditorExitReason.DISCARDED -> {
                    onNavigateBack()
                }

                EditorExitReason.ENTRY_SAVED -> {
                    haptics.saveSucceeded()
                    onEntrySaved()
                }

                null -> {
                    onEntrySaved()
                }
            }
        }
    }

    if (showExitConfirmation) {
        ConfirmEntryExitDialog(
            onCancel = {
                if (!isExitDraftSaving) showExitConfirmation = false
            },
            onConfirm = {
                if (!isExitDraftSaving) {
                    isExitDraftSaving = true
                    scope.launch {
                        val discarded = viewModel.discardAndExit()
                        isExitDraftSaving = false
                        if (discarded) {
                            showExitConfirmation = false
                        }
                    }
                }
            },
            onSaveAsDraft = {
                if (!isExitDraftSaving) {
                    isExitDraftSaving = true
                    scope.launch {
                        // Read the latest state at click time. The dialog can remain composed
                        // while the text field is dispatching its final edit, so the captured
                        // composition value may otherwise persist stale content.
                        val saved = viewModel.saveAsDraft(viewModel.editorState.value)
                        isExitDraftSaving = false
                        if (saved) {
                            showExitConfirmation = false
                            onNavigateBack()
                        }
                    }
                }
            },
            actionsEnabled = !isExitDraftSaving && !editorState.isSaving,
        )
    }

    if (showDraftsDialog) {
        DraftsBottomSheet(
            drafts = editorState.availableDrafts,
            isLoading = editorState.isLoadingDrafts,
            onDismiss = { showDraftsDialog = false },
            onDraftSelected = { draft ->
                viewModel.loadDraft(draft.id)
                showDraftsDialog = false
            },
            onDraftDeleted = { draftId ->
                viewModel.deleteDraft(draftId)
                scope.launch {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = getString(Res.string.draft_deleted),
                            actionLabel = getString(Res.string.undo),
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.loadDraft(draftId)
                    }
                }
            },
            onDeleteAllDrafts = viewModel::deleteAllDrafts,
        )
    }

    var dropActive by remember { mutableStateOf(false) }
    val canSave = editorState.canSaveFocusedEntry(focus)
    val onMediaImport = entryMediaImportHandler(viewModel, snackbarHostState, scope)
    val focusedBlock = editorState.blocks.firstOrNull { it.id == editorState.expandedBlockId }
    val returnToEntry =
        editorReturnToEntry(
            { viewModel.editorState.value },
            page,
            audioCaptureStartGate,
            focusManager,
            keyboard,
            dismiss = focusDismissCallback(focusedBlock?.id, renderedInputTokens, viewModel::dismissOwnedEditorFocus),
        )
    val chooseCapture: (StoryCaptureMode) -> Unit = { mode ->
        viewModel.chooseCreationType(focusedBlock?.id, mode.blockType())
    }
    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalAudioCaptureStartGate provides audioCaptureStartGate,
            LocalEditorInputTokens provides renderedInputTokens,
            LocalEditorTextInputSession provides editorTextInputSession(viewModel, editorState.contentRevision),
        ) {
            EntryKeyboardContentReceiver(onMediaImported = onMediaImport) {
                EntryEditorFrame(
                    state = editorState,
                    page = page,
                    defaultMode = defaultStartMode,
                    blockFocusEnabled = blockFocusEnabled,
                    focusProgress = handwritingProgress,
                    onReturn = { returnToEntry() },
                    onExit = handleEditorBack,
                    onCapture = chooseCapture,
                    onAddMemory = { viewModel.beginBlockCreation(defaultStartMode.blockType()) },
                    modifier =
                        modifier
                            .then(exitBackModifier)
                            .onSizeChanged { contextAvailable = with(density) { it.width.toDp() >= 840.dp } }
                            .entryDropTarget(
                                onText = { viewModel.appendTextBlock(it) },
                                onMediaImported = onMediaImport,
                                onActiveChanged = { dropActive = it },
                            ).entryPasteTarget(onMediaImported = onMediaImport)
                            .then(
                                if (dropActive) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                                } else {
                                    Modifier
                                },
                            ).entryEditorShortcuts(canSave, handleEditorBack) {
                                viewModel.saveEntry(viewModel.editorState.value)
                            },
                    isImmersiveBlockActive = isImmersiveBlockActive,
                    isAudioRecordingActive = editorState.isRecordingAudio,
                    focusState =
                        if (handwritingTarget) {
                            focus
                        } else if (activeHandwriting) {
                            EditorFocus.Entry
                        } else {
                            focus
                        },
                    animateContextControls = !handwritingMotion && !blockFocusEnabled,
                    drawingBackdrop = if (blockFocusEnabled) MaterialTheme.colorScheme.surfaceDim else Color.White,
                    bottomContentTopPadding = if (blockFocusEnabled) 0.dp else 24.dp,
                    hasJournalSelection = uiState.availableJournals.any { it.id in uiState.selectedJournalIds },
                    entryLocked = editorState.isLoading || editorState.isSaving || editorState.shouldExit || editorState.isEditingLocked,
                    immersiveExitProgress =
                        if (handwritingMotion) {
                            if (handwritingTarget) 0f else 1f
                        } else {
                            chromeProgress.value
                        },
                    topBarContent = {
                        EntryEditorTopBar(
                            editorState,
                            blockFocusEnabled,
                            if (blockFocusEnabled) editorState.expandedBlockId != null else isImmersiveBlockActive,
                            autoSaveState.status,
                            floating = blockFocusEnabled,
                            onBack = handleEditorBack,
                            onReturnToEntry = { returnToEntry() },
                            onShowDrafts = { showDraftsDialog = true },
                            onSave = {
                                viewModel.saveEntry(viewModel.editorState.value)
                            },
                            onCaptureMode = chooseCapture,
                            onJournalSelection = viewModel::setSelectedJournals,
                        )
                    },
                    editorContent = {
                        Column(Modifier.fillMaxSize()) {
                            if (!isImmersiveBlockActive) {
                                editorState.markId?.let {
                                    MarkContextBanner(it, editorState.errorMessage, viewModel::continueWithoutMarkedMoment)
                                }
                                editorState.visitContext?.let { VisitMemoryContextBanner(it) }
                                if (showsWatchProvenance(editorState.capturedOnWatch)) {
                                    CapturedOnWatchBanner()
                                }
                            }
                            CompositionLocalProvider(LocalEditorContextControlsVisible provides !blockFocusEnabled) {
                                MainEditorContent(
                                    floatingChrome = blockFocusEnabled,
                                    overviewContent =
                                        if (blockFocusEnabled) {
                                            (
                                                { blocks ->
                                                    EntryPageOverview(
                                                        blocks,
                                                        editorState.todayNotes,
                                                        page,
                                                        defaultStartMode,
                                                        onAudioResolverReady = bindAudioResolver,
                                                    )
                                                }
                                            )
                                        } else {
                                            null
                                        },
                                    expandAnySelectedBlock = blockFocusEnabled,
                                    defaultStartMode = defaultStartMode,
                                    todayNotes = editorState.todayNotes,
                                    entryContext = entryDayContext(editorState, blockFocusEnabled),
                                    onOpenEntry = { viewModel.loadExistingEntry(it) },
                                    modifier = Modifier.weight(1f),
                                    uiState = uiState,
                                    focusState = focus,
                                    shouldReturnToPickerOnBack = shouldReturnToPickerOnBack,
                                    onDismissExpanded = { returnToEntry() },
                                    onDismissFocusedInput = returnToEntry,
                                    onBlockFocusTransitionProgress = { handwritingProgress = it },
                                    onBlockFocusTargetChanged = { expanded -> handwritingTarget = expanded && activeHandwriting },
                                    onBackProgress = { p ->
                                        if (isImmersiveBlockActive) scope.launch { chromeProgress.snapTo(p) }
                                    },
                                    onBackCommit = {
                                        if (isImmersiveBlockActive) {
                                            scope.launch {
                                                chromeProgress.animateTo(
                                                    1f,
                                                    tween(300, easing = FastOutSlowInEasing),
                                                )
                                            }
                                        }
                                    },
                                    onBackCancel = {
                                        if (isImmersiveBlockActive) {
                                            scope.launch {
                                                chromeProgress.animateTo(
                                                    0f,
                                                    tween(300, easing = FastOutSlowInEasing),
                                                )
                                            }
                                        }
                                    },
                                    onAudioResolverReady = bindAudioResolver,
                                )
                            }
                        }
                    },
                    bottomContent = {
                        EntryEditorCaptureControls(
                            editorState,
                            blockFocusEnabled,
                            journalSelectorExpanded,
                            onExpandedChange = { journalSelectorExpanded = it },
                            onCaptureMode = chooseCapture,
                            onJournalSelection = viewModel::setSelectedJournals,
                        )
                    },
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data -> Snackbar(snackbarData = data, actionOnNewLine = true) }
    }
}
