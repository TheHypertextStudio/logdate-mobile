@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)

package app.logdate.feature.editor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.blocks.EntryMemorySequence
import app.logdate.feature.editor.ui.content.EditorStartContextLayout
import app.logdate.feature.editor.ui.content.EmptyEditorStateContent
import app.logdate.feature.editor.ui.content.EntryStartContent
import app.logdate.feature.editor.ui.content.matchingPickerTileIdsFor
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.LocalEditorInputTokens
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import app.logdate.feature.editor.ui.layout.EditorFocus
import app.logdate.feature.editor.ui.layout.editorFocus
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import kotlin.uuid.Uuid

/**
 * The main rendering container for the entry editor.
 *
 * Three display states — empty, expanded block, scrollable list — are driven by a
 * [SeekableTransitionState] so that the predictive back gesture can scrub the
 * shared-bounds morph in real time before committing or cancelling.
 *
 * @param onDismissExpanded Called when the expanded block should be dismissed (back committed)
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MainEditorContent(
    uiState: BlocksUiState,
    shouldReturnToPickerOnBack: Boolean,
    onDismissExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onBackProgress: (Float) -> Unit = {},
    onBackCommit: () -> Unit = {},
    onBackCancel: () -> Unit = {},
    onBlockFocusTransitionProgress: (Float) -> Unit = {},
    onBlockFocusTargetChanged: (Boolean) -> Unit = {},
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit = { _, _ -> },
    focusState: EditorFocus = editorFocus(uiState.blocks, uiState.expandedBlockId),
    entryContext: (@Composable () -> Unit)? = null,
    expandAnySelectedBlock: Boolean = false,
    todayNotes: List<app.logdate.client.repository.journals.JournalNote> = emptyList(),
    onOpenEntry: (Uuid) -> Unit = {},
    defaultStartMode: EntryStartMode = EntryStartMode.TEXT,
    showBlockFocusActions: Boolean = expandAnySelectedBlock,
    overviewContent: (@Composable (BlocksUiState) -> Unit)? = null,
    floatingChrome: Boolean = false,
    onDismissFocusedInput: (() -> Boolean)? = null,
) {
    var lastContent by remember { mutableStateOf(uiState) }
    SideEffect { if (uiState.blocks.isNotEmpty()) lastContent = uiState }
    val renderedContent = if (uiState.blocks.isEmpty()) lastContent else uiState

    val expandedBlock =
        remember(uiState.expandedBlockId, uiState.blocks) {
            uiState.blocks.find { it.id == uiState.expandedBlockId }
        }

    val displayState: EditorDisplay =
        when {
            uiState.blocks.isEmpty() -> {
                EditorDisplay.Empty
            }

            expandAnySelectedBlock && expandedBlock != null -> {
                EditorDisplay.Expanded(expandedBlock)
            }

            focusState is EditorFocus.Drawing &&
                expandedBlock != null &&
                uiState.blockPresentations[expandedBlock.id]?.immersive == true -> {
                EditorDisplay.Expanded(expandedBlock)
            }

            focusState is EditorFocus.Camera && expandedBlock is CameraBlockUiState && expandedBlock.uri == null -> {
                EditorDisplay.Expanded(
                    expandedBlock,
                )
            }

            else -> {
                EditorDisplay.List
            }
        }

    val reduceMotion = rememberSystemReduceMotion().value
    val retainedMode = floatingChrome && overviewContent != null
    val retainedBlocks = rememberRetainedEditorBlocks(uiState, onAudioResolverReady)

    // SeekableTransitionState lets the predictive back gesture scrub the transition
    val transitionState = remember { SeekableTransitionState(displayState) }

    // Capture completion changes the display mode without changing the block ID.
    LaunchedEffect(retainedMode, displayState::class, expandedBlock?.id, reduceMotion) {
        if (!retainedMode) {
            if (reduceMotion) transitionState.snapTo(displayState) else transitionState.animateTo(displayState)
        }
    }

    val backTarget: EditorDisplay =
        if (shouldReturnToPickerOnBack) {
            EditorDisplay.Empty
        } else {
            EditorDisplay.List
        }
    val pickerTileIds =
        remember(expandedBlock, shouldReturnToPickerOnBack, uiState.blocks.isEmpty(), lastContent) {
            if (shouldReturnToPickerOnBack || uiState.blocks.isEmpty()) {
                matchingPickerTileIdsFor(expandedBlock ?: renderedContent.blocks.singleOrNull())
            } else {
                matchingPickerTileIdsFor(null)
            }
        }

    val transition = rememberTransition(transitionState, label = "editorDisplay")
    val focusBookmarks = LocalEditorInputFocusBookmarks.current
    val retainedMotion =
        if (retainedMode) {
            rememberRetainedEditorFocusMotion(
                (displayState as? EditorDisplay.Expanded)?.block?.id,
                uiState.blocks.map { it.id }.toSet(),
                reduceMotion,
                onDismissFocusedInput ?: {
                    onDismissExpanded()
                    true
                },
                { expandedBlock?.id?.let { focusBookmarks?.capture(it) } },
                onBackProgress,
                onBackCommit,
                onBackCancel,
                inputGeneration = LocalEditorInputTokens.current[(displayState as? EditorDisplay.Expanded)?.block?.id],
            )
        } else {
            null
        }
    val legacyProgress =
        if (!retainedMode) {
            val value by transition.animateFloat(
                transitionSpec = { if (reduceMotion) snap() else blockFocusSpring() },
                label = "editorSurfaceProgress",
            ) { if (it is EditorDisplay.Expanded) 1f else 0f }
            rememberEditorDisplayBack(
                transitionState,
                displayState,
                backTarget,
                onDismissExpanded,
                { value },
                onBackProgress,
                onBackCommit,
                onBackCancel,
                onPrepareToLeave = { expandedBlock?.id?.let { focusBookmarks?.capture(it) } },
            )
            value
        } else {
            0f
        }
    val focusProgress = retainedMotion?.surfaceProgress ?: legacyProgress
    val focusTarget = if (retainedMotion != null) retainedMotion.targetId != null else transition.targetState is EditorDisplay.Expanded
    val progressById = retainedMotion?.progress ?: emptyMap()
    val continuity = retainedMotion?.let { EditorBlockContinuity(retainedBlocks, it.targetId, progressById) }
    SideEffect {
        onBlockFocusTransitionProgress(focusProgress)
        onBlockFocusTargetChanged(focusTarget)
        if ((retainedMode && progressById.values.all { it == 0f || it == 1f }) ||
            (!retainedMode && transition.currentState == transition.targetState)
        ) {
            retainedBlocks.retainOnly(uiState.blocks.map { it.id }.toSet())
        }
    }

    EditorSharedTransitionContainer(modifier = modifier.fillMaxSize().semantics { this[BlockFocusProgressKey] = focusProgress }) {
        val sts = this
        CompositionLocalProvider(
            LocalSharedTransitionScope provides sts,
            LocalEditorBlockContinuity provides continuity,
            LocalBlockFocusProgress provides focusProgress,
            LocalBlockFocusId provides
                (
                    retainedMotion?.targetId ?: (transition.targetState as? EditorDisplay.Expanded)?.block?.id
                        ?: (transition.currentState as? EditorDisplay.Expanded)?.block?.id
                ),
        ) {
            if (continuity != null) {
                // The live document and all native inputs stay in one scrolling ancestry.
                overviewContent?.invoke(uiState)
            } else {
                transition.AnimatedContent(
                    contentKey = { it::class },
                    transitionSpec = {
                        EnterTransition.None togetherWith fadeOut(tween(220))
                    },
                ) { target ->
                    val avs = this
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides avs) {
                        when (target) {
                            EditorDisplay.Empty -> {
                                if (overviewContent !=
                                    null
                                ) {
                                    overviewContent(uiState)
                                } else {
                                    EditorStartContextLayout(entryContext) { pickerModifier ->
                                        if (expandAnySelectedBlock) {
                                            EntryStartContent(
                                                notes = todayNotes,
                                                onCreateBlock = { type, id -> uiState.onCreateBlock(type, id) },
                                                onOpenEntry = onOpenEntry,
                                                tileIds = pickerTileIds,
                                                handwritingEnabled = uiState.allowHandwritingCreation,
                                                defaultMode = defaultStartMode,
                                                modifier = pickerModifier,
                                            )
                                        } else {
                                            EmptyEditorStateContent(
                                                onStartTextBlock = { id -> uiState.onCreateBlock(BlockType.TEXT, id) },
                                                onStartPhotoBlock = { id -> uiState.onCreateBlock(BlockType.IMAGE, id) },
                                                onStartAudioBlock = { id -> uiState.onCreateBlock(BlockType.AUDIO, id) },
                                                onStartCameraBlock = { id -> uiState.onCreateBlock(BlockType.CAMERA, id) },
                                                onStartHandwritingBlock =
                                                    if (uiState.allowHandwritingCreation) {
                                                        { id -> uiState.onCreateBlock(BlockType.HANDWRITING, id) }
                                                    } else {
                                                        null
                                                    },
                                                retainConsumedTileIds = true,
                                                textTileId = pickerTileIds.textId,
                                                photoTileId = pickerTileIds.photoId,
                                                audioTileId = pickerTileIds.audioId,
                                                cameraTileId = pickerTileIds.cameraId,
                                                modifier = pickerModifier.padding(start = Spacing.sm, top = Spacing.sm, end = Spacing.sm),
                                            )
                                        }
                                    }
                                }
                            }

                            is EditorDisplay.Expanded -> {
                                // Read the live block from uiState rather than the
                                // transition's target snapshot so that edits (typing)
                                // are reflected immediately without waiting for the
                                // SeekableTransitionState to update.
                                val liveBlock =
                                    uiState.blocks.find { it.id == target.block.id }
                                        ?: target.block
                                FocusedEditorBlockContent(liveBlock, uiState, focusProgress, floatingChrome, sts, avs, onAudioResolverReady)
                            }

                            EditorDisplay.List -> {
                                if (overviewContent != null) {
                                    overviewContent(renderedContent)
                                } else {
                                    EntryMemorySequence(
                                        uiState = renderedContent,
                                        entryContext = entryContext,
                                        autoFocusInitialText = !expandAnySelectedBlock,
                                        showFocusActions = showBlockFocusActions,
                                        listState = listState,
                                        onAudioResolverReady = onAudioResolverReady,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun EditorSharedTransitionContainer(
    modifier: Modifier,
    content: @Composable SharedTransitionScope.() -> Unit,
) {
    val providedScope = LocalSharedTransitionScope.current
    if (providedScope == null) {
        SharedTransitionLayout(modifier, content = content)
    } else {
        Box(modifier) { content(providedScope) }
    }
}
