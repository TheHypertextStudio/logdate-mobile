@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.audio.AudioBlockEditor
import app.logdate.feature.editor.ui.blocks.EntryContextPreview
import app.logdate.feature.editor.ui.blocks.MemoryBlockContent
import app.logdate.feature.editor.ui.blocks.MemoryBlockSurface
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import app.logdate.feature.editor.ui.image.ImageBlockPreview
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import app.logdate.feature.editor.ui.layout.FocusedBlockLayout
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.feature.editor.ui.layout.LocalEntryContentInsets
import app.logdate.feature.editor.ui.layout.LocalEntryCreationReserve
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import kotlin.uuid.Uuid

internal val LocalRetainedEditorSurface = compositionLocalOf { false }
internal val LocalEditorBlockContinuity = compositionLocalOf<EditorBlockContinuity?> { null }

internal data class EditorBlockContinuity(
    val host: RetainedEditorBlocks,
    val focusedId: Uuid?,
    val progress: Map<Uuid, Float>,
)

internal data class BlockPlacement(
    val block: EntryBlockUiState,
    val expanded: Boolean,
    val modifier: Modifier,
    val onSelect: () -> Unit,
)

internal class RetainedBlockMeasure {
    var inlineSize = IntSize.Zero
    val width = RetainedEditorDimension()
    val height = RetainedEditorDimension()
    val x = RetainedEditorDimension()
    val y = RetainedEditorDimension()
}

/** One permanent document owns each surface and input. Focus changes their geometry, never their parent. */
internal class RetainedEditorBlocks(
    private val ui: State<BlocksUiState>,
    private val resolver: State<(Uuid, PendingAudioResolver) -> Unit>,
) {
    private val measurements = mutableMapOf<Uuid, RetainedBlockMeasure>()

    fun measure(id: Uuid) = measurements.getOrPut(id) { RetainedBlockMeasure() }

    fun retainOnly(ids: Set<Uuid>) {
        measurements.keys.retainAll(ids)
    }

    @Composable
    fun Content(
        block: EntryBlockUiState,
        expanded: Boolean,
        progress: Float,
        modifier: Modifier,
        onSelect: () -> Unit,
        hidden: Boolean = false,
    ) {
        val state = ui.value
        // Back may change its visual target while this media input still owns logical focus.
        val inputExpanded =
            expanded || ((block is ImageBlockUiState || block is CameraBlockUiState) && state.expandedBlockId == block.id)
        val placement = BlockPlacement(block, inputExpanded, modifier, onSelect)
        CompositionLocalProvider(
            LocalRetainedEditorSurface provides true,
            LocalBlockFocusId provides block.id,
            LocalBlockFocusProgress provides progress,
        ) {
            Box(
                modifier = modifier.then(if (inputExpanded) Modifier.testTag("focused_block_${block.id}") else Modifier),
                propagateMinConstraints = true,
            ) {
                MemoryBlockSurface(
                    block = block,
                    isSelected = inputExpanded,
                    onSelect = onSelect,
                    onUpdate = state.onUpdateBlock,
                    onRemove = { state.onDeleteBlock(block.id) },
                    modifier =
                        Modifier.then(editorOwnerAttachmentProbe(block.id)).then(
                            if (hidden) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                        ),
                    customPresentation = block is HandwritingBlockUiState,
                    focusTransitionProgress = progress,
                    morphSurface = true,
                    expanded = inputExpanded,
                    retainedSurface = true,
                ) {
                    if (block is TextBlockUiState || block.id in state.blockPresentations) {
                        val photo =
                            if (block is TextBlockUiState) {
                                state.blocks.filterIsInstance<ImageBlockUiState>().firstOrNull {
                                    it.uri != null
                                }
                            } else {
                                null
                            }
                        val showContext = rememberFoldableLayoutInfo().hinge?.isSeparating == true
                        val preview: (@Composable () -> Unit)? =
                            when {
                                !expanded -> null
                                photo != null -> ({ ImageBlockPreview(photo, modifier = Modifier.fillMaxSize()) })
                                showContext -> (
                                    {
                                        EntryContextPreview(
                                            state.blocks.filter { it.id != block.id },
                                            Modifier.padding(top = 80.dp),
                                        )
                                    }
                                )
                                else -> null
                            }
                        FocusedBlockLayout(
                            preview = preview,
                            textEditor = true,
                            contentPadding = 0.dp,
                            expanded = expanded,
                            editor = { RetainedBlockInput(block, placement, state, resolver.value) },
                        )
                    } else {
                        RetainedBlockInput(block, placement, state, resolver.value)
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberRetainedEditorBlocks(
    uiState: BlocksUiState,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit,
): RetainedEditorBlocks {
    val ui = rememberUpdatedState(uiState)
    val resolver = rememberUpdatedState(onAudioResolverReady)
    return remember { RetainedEditorBlocks(ui, resolver) }
}

@Composable
private fun RetainedBlockInput(
    block: EntryBlockUiState,
    placement: BlockPlacement,
    state: BlocksUiState,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit,
) {
    val custom = state.blockPresentations[block.id]
    val progress = LocalBlockFocusProgress.current ?: if (placement.expanded) 1f else 0f
    val visualExpanded = placement.expanded || progress > 0f
    val focusedPadding =
        (if (visualExpanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).retainedContentPadding(
            top = if (block is TextBlockUiState || block is AudioBlockUiState) LocalEntryChromeHeight.current else 0.dp,
            bottom = if (block is TextBlockUiState) LocalEntryCreationReserve.current else 0.dp,
            expanded = placement.expanded,
            progress = progress,
        )
    when {
        custom != null -> if (placement.expanded) custom.focused() else custom.inline(placement.onSelect)
        block is TextBlockUiState ->
            TextBlockContent(
                block,
                isExpanded = visualExpanded,
                requestEditingFocus = placement.expanded,
                onTextChanged = { state.onUpdateBlock(block.copy(content = it)) },
                onFocused = placement.onSelect,
                modifier = focusedPadding,
            )
        block is AudioBlockUiState ->
            AudioBlockEditor(
                block,
                onBlockUpdated = state.onUpdateBlock,
                onDeleteRequested = { state.onDeleteBlock(block.id) },
                onResolverReady = onAudioResolverReady,
                onPersistRecordingTarget = state.onPersistAudioRecordingTarget,
                modifier =
                    if (visualExpanded) {
                        focusedPadding
                    } else {
                        Modifier.fillMaxWidth().heightIn(
                            min = EditorBlockMinHeight,
                            max = 420.dp,
                        )
                    },
                inline = !placement.expanded,
                selected = placement.expanded,
            )
        placement.expanded ->
            BlockContentInner(
                block,
                true,
                state.onBlockFocused,
                state.onUpdateBlock,
                state.onDeleteBlock,
                onAudioResolverReady,
                state.onPersistAudioRecordingTarget,
                state.onPersistCameraCaptureTarget,
                Modifier.fillMaxSize(),
            )
        else ->
            Box {
                MemoryBlockContent(
                    block,
                    false,
                    false,
                    0,
                    placement.onSelect,
                    state.onUpdateBlock,
                    { state.onDeleteBlock(block.id) },
                    onAudioResolverReady,
                    state.onPersistAudioRecordingTarget,
                )
            }
    }
}

/** The window remains painted continuously; only the inner content approaches its safe spacing. */
@Composable
private fun Modifier.retainedContentPadding(
    top: androidx.compose.ui.unit.Dp,
    bottom: androidx.compose.ui.unit.Dp,
    expanded: Boolean,
    progress: Float,
): Modifier {
    val insets = LocalEntryContentInsets.current.union(WindowInsets.ime)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return editorFocusPadding(
        focused =
            EditorFocusInsets(
                left = insets.getLeft(density, direction),
                top = insets.getTop(density) + with(density) { top.roundToPx() },
                right = insets.getRight(density, direction),
                bottom = insets.getBottom(density) + with(density) { bottom.roundToPx() },
            ),
        expanded = expanded,
        progress = progress,
    )
}
