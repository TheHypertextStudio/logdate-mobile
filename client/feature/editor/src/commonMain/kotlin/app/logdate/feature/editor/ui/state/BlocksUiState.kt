package app.logdate.feature.editor.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.EditorDraftReveal
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.shared.model.Journal
import kotlin.uuid.Uuid

/**
 * Composite state holder that bridges EditorState (ViewModel) to composables.
 * Holds the block list, journal selection data, and all editor callbacks in one place.
 */
@Stable
class BlocksUiState(
    val blocks: List<EntryBlockUiState> = listOf(),
    val expandedBlockId: Uuid? = null,
    val availableJournals: List<Journal>,
    val selectedJournalIds: List<Uuid>,
    val onBlockFocused: (Uuid) -> Unit,
    val onJournalSelectionChanged: (List<Uuid>) -> Unit,
    val onUpdateBlock: (EntryBlockUiState) -> Unit,
    val onCreateBlock: (BlockType, Uuid) -> EntryBlockUiState,
    val onDeleteBlock: (Uuid) -> Unit,
    val onPersistAudioRecordingTarget: suspend (Uuid, String, String?) -> Boolean = { _, _, _ -> true },
    val onPersistCameraCaptureTarget: suspend (Uuid, String, CapturedMediaType) -> Boolean = { _, _, _ -> false },
    val allowBlockCreation: Boolean = true,
    val allowHandwritingCreation: Boolean = false,
    val blockPresentations: Map<Uuid, EditorBlockPresentation> = emptyMap(),
    val draftReveal: EditorDraftReveal? = null,
    /** True while today's earlier notes, shown above the draft, are still loading. */
    val draftRevealContextLoading: Boolean = false,
    val onConsumeDraftReveal: (EditorDraftReveal) -> Boolean = { false },
    val onCancelDraftReveal: () -> Unit = {},
) {
    val hasContent get() = blocks.any { it.hasContent() }
}

@Composable
fun rememberBlocksUiState(
    editorState: EditorState,
    onUpdateBlock: (EntryBlockUiState) -> Unit,
    onFocusBlock: (Uuid) -> Unit,
    onCreateBlock: (BlockType, Uuid) -> EntryBlockUiState,
    onDeleteBlock: (Uuid) -> Unit,
    onUpdateJournalSelection: (List<Uuid>) -> Unit,
    onPersistAudioRecordingTarget: suspend (Uuid, String, String?) -> Boolean = { _, _, _ -> true },
    onPersistCameraCaptureTarget: suspend (Uuid, String, CapturedMediaType) -> Boolean = { _, _, _ -> false },
    blockPresentations: Map<Uuid, EditorBlockPresentation> = emptyMap(),
    allowHandwritingCreation: Boolean = false,
    onConsumeDraftReveal: (EditorDraftReveal) -> Boolean = { false },
    onCancelDraftReveal: () -> Unit = {},
): BlocksUiState {
    val hasContent by remember(editorState.blocks) {
        derivedStateOf { editorState.blocks.isNotEmpty() }
    }

    return remember(
        editorState,
        hasContent,
        blockPresentations,
        allowHandwritingCreation,
        onPersistAudioRecordingTarget,
        onPersistCameraCaptureTarget,
        onConsumeDraftReveal,
        onCancelDraftReveal,
    ) {
        BlocksUiState(
            blocks = editorState.blocks,
            draftReveal = editorState.pendingDraftReveal,
            draftRevealContextLoading = editorState.isLoadingDrafts,
            onConsumeDraftReveal = onConsumeDraftReveal,
            onCancelDraftReveal = onCancelDraftReveal,
            blockPresentations = blockPresentations,
            allowHandwritingCreation = allowHandwritingCreation,
            expandedBlockId = editorState.expandedBlockId,
            availableJournals = editorState.availableJournals,
            selectedJournalIds = editorState.selectedJournalIds,
            onBlockFocused = onFocusBlock,
            onJournalSelectionChanged = onUpdateJournalSelection,
            onUpdateBlock = onUpdateBlock,
            onCreateBlock = onCreateBlock,
            onDeleteBlock = onDeleteBlock,
            onPersistAudioRecordingTarget = onPersistAudioRecordingTarget,
            onPersistCameraCaptureTarget = onPersistCameraCaptureTarget,
        )
    }
}
