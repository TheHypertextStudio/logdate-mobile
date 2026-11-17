@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.EntryEditorTopBar
import app.logdate.feature.editor.ui.LocalSharedTransitionScope
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.common.NoteEditorToolbar
import app.logdate.feature.editor.ui.content.EditorBottomContent
import app.logdate.feature.editor.ui.content.blockType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.editor.beginEmptyCreationBlock
import app.logdate.feature.editor.ui.editor.replaceEmptyCreationBlock
import app.logdate.feature.editor.ui.layout.EditorFocus
import app.logdate.feature.editor.ui.layout.ImmersiveEditorLayout
import app.logdate.feature.editor.ui.layout.LocalEditorContextControlsVisible
import app.logdate.feature.editor.ui.layout.editorFocus
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.feature.editor.ui.state.EditorBlockPresentation
import app.logdate.feature.editor.ui.state.rememberBlocksUiState
import app.logdate.shared.model.Journal
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_page_sample_journal
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/** Local preview state in the original editor components; saving belongs to the real editor host. */
@Composable
fun EntryStartPrototype(
    initialPage: HandwritingPage? = null,
    initiallyFocused: Boolean = false,
    initialText: String = "",
    onExit: () -> Unit = {},
    showEarlierEntries: Boolean = true,
    initialBlocks: List<EntryBlockUiState>? = null,
    onBlocksChanged: (List<EntryBlockUiState>) -> Unit = {},
    focusAnyBlock: Boolean = false,
    initialFocusedBlockId: Uuid? = null,
    previewPresentations: Map<Uuid, EditorBlockPresentation> = emptyMap(),
    continuousOverview: Boolean = false,
    entryContextOverride: (@Composable () -> Unit)? = null,
    overviewContent: (@Composable (BlocksUiState) -> Unit)? = null,
    decorateHandwritingFocus: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    overviewStudyState: EntryPageOverviewState? = null,
    studyDefaultMode: EntryStartMode = EntryStartMode.TEXT,
    pageTopBar: Boolean = false,
) {
    val journalTitle = stringResource(Res.string.entry_page_sample_journal)
    val journal = remember(journalTitle) { Journal(title = journalTitle) }
    var blocks by remember {
        mutableStateOf(
            initialBlocks ?: buildList<EntryBlockUiState> {
                if (initialText.isNotEmpty()) add(TextBlockUiState(content = initialText))
                if (initialPage != null) add(HandwritingBlockUiState(page = initialPage))
            },
        )
    }
    LaunchedEffect(blocks) { onBlocksChanged(blocks) }
    var selected by remember { mutableStateOf(initialFocusedBlockId ?: if (initiallyFocused) blocks.lastOrNull()?.id else null) }
    var selectedJournals by remember { mutableStateOf(listOf(journal.id)) }
    var journalExpanded by remember { mutableStateOf(false) }
    var focusProgress by remember { mutableStateOf(if (initiallyFocused) 1f else 0f) }
    var immersiveTarget by remember { mutableStateOf(initiallyFocused) }
    var contextAvailable by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val state =
        EditorState(
            blocks = blocks,
            expandedBlockId = selected,
            availableJournals = listOf(journal),
            selectedJournalIds = selectedJournals,
        )
    val focus = state.editorFocus()
    LaunchedEffect(focus is EditorFocus.Drawing) {
        if (focus is EditorFocus.Drawing) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val update: (EntryBlockUiState) -> Unit = { next -> blocks = blocks.map { if (it.id == next.id) next else it } }
    val presentations =
        rememberHandwritingBlockPresentations(
            state,
            update,
            focusProgress,
            contextAvailable,
            adaptToTheme = focusAnyBlock,
            keepInkBelowControls = focusAnyBlock,
        ).mapValues { (_, presentation) ->
            EditorBlockPresentation(
                inline = presentation.inline,
                focused = { decorateHandwritingFocus(presentation.focused) },
                immersive = presentation.immersive,
                focusTransitionProgress = presentation.focusTransitionProgress,
            )
        } +
            previewPresentations
    val uiState =
        rememberBlocksUiState(
            state,
            onUpdateBlock = update,
            onFocusBlock = {
                selected = it
                journalExpanded = false
            },
            onCreateBlock = { type, id ->
                val block =
                    when (type) {
                        BlockType.TEXT -> TextBlockUiState(id = id)
                        BlockType.HANDWRITING -> HandwritingBlockUiState(id = id)
                        BlockType.IMAGE -> ImageBlockUiState(id = id)
                        BlockType.VIDEO -> VideoBlockUiState(id = id)
                        BlockType.AUDIO -> AudioBlockUiState(id = id)
                        BlockType.CAMERA -> CameraBlockUiState(id = id)
                    }
                blocks = blocks + block
                selected = id
                block
            },
            onDeleteBlock = { id ->
                blocks = blocks.filter { it.id != id }
                if (selected == id) selected = null
            },
            onUpdateJournalSelection = { selectedJournals = it },
            blockPresentations = presentations,
            allowHandwritingCreation = true,
        )
    val focusedBlock = blocks.firstOrNull { it.id == selected }
    val chooseCapture: (StoryCaptureMode) -> Unit = { mode ->
        val current = EditorState(blocks = blocks, expandedBlockId = selected)
        val next =
            selected?.let { current.replaceEmptyCreationBlock(it, mode.blockType(), Uuid.random()) }
                ?: current.beginEmptyCreationBlock(mode.blockType(), Uuid.random(), onlyEmptyEntry = true)
        blocks = next.blocks
        selected = next.expandedBlockId
    }
    if (continuousOverview && overviewStudyState != null) {
        val returnToOverview: () -> Unit = {
            selected?.let { id ->
                overviewStudyState.focusedAnchor =
                    EntryPageScrollAnchor(
                        id,
                        overviewStudyState.blockPositions[id] ?: overviewStudyState.viewportTop + 88f,
                        overviewStudyState.viewportWidth,
                    )
            }
            selected = null
        }
        EntryPageChrome(
            state,
            overviewStudyState,
            studyDefaultMode,
            returnToOverview,
            onExit,
            chooseCapture,
            header =
                if (pageTopBar) {
                    {
                        EntryEditorTopBar(
                            state,
                            immersive = selected != null,
                            autoSaveStatus = AutoSaveStatus.IDLE,
                            onBack = onExit,
                            onReturnToEntry = returnToOverview,
                            onShowDrafts = {},
                            onSave = {},
                            onCaptureMode = chooseCapture,
                            onJournalSelection = { selectedJournals = it },
                        )
                    }
                } else {
                    null
                },
            focusProgress = focusProgress,
            onAddMemory = {
                val next =
                    EditorState(
                        blocks = blocks,
                        expandedBlockId = selected,
                    ).beginEmptyCreationBlock(studyDefaultMode.blockType(), Uuid.random())
                blocks = next.blocks
                selected = next.expandedBlockId
            },
        ) {
            MainEditorContent(
                uiState,
                shouldReturnToPickerOnBack = false,
                onDismissExpanded = returnToOverview,
                onBlockFocusTransitionProgress = { focusProgress = it },
                focusState = focus,
                expandAnySelectedBlock = true,
                showBlockFocusActions = false,
                overviewContent = overviewContent,
                floatingChrome = true,
            )
        }
        return
    }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            Box(
                Modifier.fillMaxSize().testTag(if (focusAnyBlock) "stories_editor" else "handwriting_editor").onSizeChanged {
                    contextAvailable = with(density) { it.width.toDp() >= 840.dp }
                },
            ) {
                ImmersiveEditorLayout(
                    isImmersiveBlockActive = immersiveTarget,
                    immersiveExitProgress = if (immersiveTarget) 0f else 1f,
                    animateContextControls = false,
                    focusState =
                        if (immersiveTarget) {
                            focus
                        } else if (focus is EditorFocus.Drawing) {
                            EditorFocus.Entry
                        } else {
                            focus
                        },
                    drawingBackdrop = if (focusAnyBlock) MaterialTheme.colorScheme.surfaceDim else Color.White,
                    bottomContentTopPadding = if (focusAnyBlock) 0.dp else 24.dp,
                    topBarContent = {
                        if (focusAnyBlock) {
                            EntryFocusPrototypeHeader(
                                block = focusedBlock,
                                canvasOnly = immersiveTarget,
                                recording = state.isRecordingAudio,
                                onReturnToEntry = { selected = null },
                                onExit = onExit,
                                journal = journal,
                                onJournalSelection = {
                                    selectedJournals =
                                        if (selectedJournals.isEmpty()) listOf(journal.id) else emptyList()
                                },
                                journalSelected = selectedJournals.isNotEmpty(),
                                onCaptureMode = chooseCapture,
                            )
                        } else {
                            NoteEditorToolbar(
                                onBack = { if (selected != null) selected = null else onExit() },
                                onSave = {},
                                onShowDrafts = {},
                                actionsVisible = !immersiveTarget,
                                optionsVisible = false,
                                saveEnabled = false,
                            )
                        }
                    },
                    editorContent = {
                        CompositionLocalProvider(LocalEditorContextControlsVisible provides (continuousOverview || !focusAnyBlock)) {
                            MainEditorContent(
                                uiState,
                                entryContext =
                                    entryContextOverride ?: if (showEarlierEntries) {
                                        { if (focusAnyBlock) EarlierTodayCaptureContext() else EarlierTodayPrototypeContent() }
                                    } else {
                                        null
                                    },
                                shouldReturnToPickerOnBack = false,
                                onDismissExpanded = { selected = null },
                                onBlockFocusTransitionProgress = { focusProgress = it },
                                onBlockFocusTargetChanged = {
                                    immersiveTarget =
                                        it &&
                                        (!focusAnyBlock || focus is EditorFocus.Drawing || focus is EditorFocus.Camera)
                                },
                                focusState = focus,
                                listState = listState,
                                expandAnySelectedBlock = focusAnyBlock,
                                showBlockFocusActions = focusAnyBlock && !continuousOverview,
                                overviewContent = overviewContent,
                            )
                        }
                    },
                    bottomContent = {
                        if (focusAnyBlock) {
                            if (blocks.isNotEmpty() && !state.isRecordingAudio && (!continuousOverview || selected != null)) {
                                EntryFocusPrototypeTools(
                                    mode = captureModeForBlock(focusedBlock),
                                    onMode = chooseCapture,
                                )
                            }
                        } else {
                            EditorBottomContent(
                                listOf(journal),
                                selectedJournals,
                                { selectedJournals = it },
                                journalExpanded,
                                { journalExpanded = it },
                            )
                        }
                    },
                )
            }
        }
    }
}
