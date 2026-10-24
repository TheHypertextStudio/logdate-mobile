package app.logdate.feature.editor.ui.editor

import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.VisitMemoryContext
import kotlinx.coroutines.flow.update
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_error_block_saved_cannot_change
import logdate.client.feature.editor.generated.resources.editor_error_block_saved_cannot_remove
import logdate.client.feature.editor.generated.resources.editor_error_finish_recording_before_removing
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun EntryEditorViewModel.mutateEditorContent(transform: (EditorState) -> EditorState): Boolean {
    while (true) {
        val currentState = mutableEditorState.value
        if (currentState.isEditingLocked || currentState.shouldExit) return false
        val updatedState = transform(currentState)
        if (updatedState == currentState) return false
        if (
            mutableEditorState.compareAndSet(
                currentState,
                updatedState.copy(contentRevision = currentState.contentRevision + 1),
            )
        ) {
            return true
        }
    }
}

/**
 * Sets the journals that this entry is associated with.
 */
internal fun EntryEditorViewModel.setSelectedJournalsImpl(journalIds: List<Uuid>) {
    val internalState = mutableEditorState.value
    val effectiveSelection = authoritativeSelectedJournalIds(internalState, editorState.value)
    mutateEditorContent { currentState ->
        if (effectiveSelection == journalIds) {
            if (currentState.selectedJournalIds == journalIds) {
                currentState
            } else {
                currentState.copy(selectedJournalIds = journalIds)
            }
        } else {
            currentState.copy(
                selectedJournalIds = journalIds,
                hasJournalSelectionChanges = true,
                isModified = true,
            )
        }
    }
}

internal fun EntryEditorViewModel.initializeVisitContextImpl(context: VisitMemoryContext?) {
    mutateEditorContent { current ->
        if (current.visitContext == context) current else current.copy(visitContext = context)
    }
}

/** Applies navigation-provided journal context without treating it as a user edit. */
internal fun EntryEditorViewModel.initializeSelectedJournalsImpl(journalIds: List<Uuid>) {
    mutateEditorContent { currentState ->
        if (currentState.selectedJournalIds == journalIds) {
            currentState
        } else {
            currentState.copy(selectedJournalIds = journalIds)
        }
    }
}

/**
 * Adds a new block to the editor.
 *
 * Picker tiles pre-generate the block id so the tile can morph into the block. A
 * second tap on the same tile (while that morph is still animating, or from a picker
 * that stays on screen) arrives with the same id. Adding it again would give the block
 * list two entries with one key, which crashes the lazy list, so an id that already
 * exists focuses the block it created instead.
 */
internal fun EntryEditorViewModel.createNewBlockImpl(
    type: BlockType,
    id: Uuid = Uuid.random(),
): EntryBlockUiState {
    val existing = mutableEditorState.value.blocks.find { it.id == id }
    if (existing != null) {
        if (type != BlockType.TEXT) setExpandedBlockId(existing.id)
        return existing
    }
    val newBlock = createEditorBlock(type, id)

    mutateEditorContent { currentState ->
        if (currentState.blocks.any { it.id == id }) {
            currentState
        } else {
            currentState.copy(
                blocks = currentState.blocks + newBlock,
                expandedBlockId = if (type != BlockType.TEXT) newBlock.id else currentState.expandedBlockId,
                isModified = true,
            )
        }
    }
    return newBlock
}

private fun createEditorBlock(
    type: BlockType,
    id: Uuid,
): EntryBlockUiState {
    val location = null
    val timestamp = Clock.System.now()

    return when (type) {
        BlockType.HANDWRITING -> {
            HandwritingBlockUiState(id = id, timestamp = timestamp, location = location)
        }

        BlockType.TEXT -> {
            TextBlockUiState(
                id = id,
                timestamp = timestamp,
                location = location,
            )
        }

        BlockType.IMAGE -> {
            ImageBlockUiState(
                id = id,
                timestamp = timestamp,
                location = location,
            )
        }

        BlockType.VIDEO -> {
            VideoBlockUiState(
                id = id,
                timestamp = timestamp,
                location = location,
            )
        }

        BlockType.AUDIO -> {
            AudioBlockUiState(
                id = id,
                timestamp = timestamp,
                location = location,
            )
        }

        BlockType.CAMERA -> {
            CameraBlockUiState(
                id = id,
                timestamp = timestamp,
                location = location,
            )
        }
    }
}

/**
 * Updates an existing block in the editor. When [baseBlock] is supplied, only fields
 * changed from that rendered snapshot are applied to the latest stored block.
 */
internal fun EntryEditorViewModel.updateBlockImpl(
    updatedBlock: EntryBlockUiState,
    baseBlock: EntryBlockUiState? = null,
    inputToken: Uuid? = null,
    fromInput: Boolean = false,
) {
    if (editorState.value.isReadOnly(updatedBlock.id)) {
        mutableEditorState.update { it.copy(errorMessage = Res.string.editor_error_block_saved_cannot_change.toUiText()) }
        return
    }
    mutateEditorContent { currentState ->
        if (fromInput && currentState.creationInputTokens[updatedBlock.id] != inputToken) return@mutateEditorContent currentState
        if (currentState.isReadOnly(updatedBlock.id)) {
            currentState
        } else {
            val existingBlock =
                currentState.blocks.find { it.id == updatedBlock.id }
                    ?: return@mutateEditorContent currentState
            if (existingBlock::class != updatedBlock::class || existingBlock == updatedBlock) {
                return@mutateEditorContent currentState
            }

            if (baseBlock != null && (baseBlock.id != updatedBlock.id || baseBlock::class != updatedBlock::class)) {
                return@mutateEditorContent currentState
            }
            val patchedBlock = if (baseBlock == null) updatedBlock else applyBlockChanges(existingBlock, updatedBlock, baseBlock)
            val effectiveBlock =
                mergeCompletedMedia(existingBlock, patchedBlock, baseBlock == null)
                    ?: return@mutateEditorContent currentState
            if (effectiveBlock == existingBlock) return@mutateEditorContent currentState
            currentState.copy(
                blocks =
                    currentState.blocks.map {
                        if (it.id == updatedBlock.id) effectiveBlock else it
                    },
                isModified = true,
            )
        }
    }
}

internal fun EntryEditorViewModel.applyBlockChanges(
    existing: EntryBlockUiState,
    updated: EntryBlockUiState,
    base: EntryBlockUiState,
): EntryBlockUiState =
    when (updated) {
        is HandwritingBlockUiState -> {
            existing as HandwritingBlockUiState
            base as HandwritingBlockUiState
            existing.copy(page = changed(existing.page, updated.page, base.page))
        }

        is TextBlockUiState -> {
            applyTextChanges(existing as TextBlockUiState, updated, base as TextBlockUiState)
        }

        is ImageBlockUiState -> {
            existing as ImageBlockUiState
            base as ImageBlockUiState
            existing.copy(
                timestamp = changed(existing.timestamp, updated.timestamp, base.timestamp),
                location = changed(existing.location, updated.location, base.location),
                timeZoneId = changed(existing.timeZoneId, updated.timeZoneId, base.timeZoneId),
                uri = changed(existing.uri, updated.uri, base.uri),
                caption = changed(existing.caption, updated.caption, base.caption),
                presentation = changed(existing.presentation, updated.presentation, base.presentation),
            )
        }

        is VideoBlockUiState -> {
            existing as VideoBlockUiState
            base as VideoBlockUiState
            existing.copy(
                timestamp = changed(existing.timestamp, updated.timestamp, base.timestamp),
                location = changed(existing.location, updated.location, base.location),
                timeZoneId = changed(existing.timeZoneId, updated.timeZoneId, base.timeZoneId),
                uri = changed(existing.uri, updated.uri, base.uri),
                caption = changed(existing.caption, updated.caption, base.caption),
                durationMs = changed(existing.durationMs, updated.durationMs, base.durationMs),
            )
        }

        is CameraBlockUiState -> {
            existing as CameraBlockUiState
            base as CameraBlockUiState
            existing.copy(
                timestamp = changed(existing.timestamp, updated.timestamp, base.timestamp),
                location = changed(existing.location, updated.location, base.location),
                timeZoneId = changed(existing.timeZoneId, updated.timeZoneId, base.timeZoneId),
                uri = changed(existing.uri, updated.uri, base.uri),
                pendingFilePath = changed(existing.pendingFilePath, updated.pendingFilePath, base.pendingFilePath),
                caption = changed(existing.caption, updated.caption, base.caption),
                mediaType = changed(existing.mediaType, updated.mediaType, base.mediaType),
                durationMs = changed(existing.durationMs, updated.durationMs, base.durationMs),
            )
        }

        is AudioBlockUiState -> {
            existing as AudioBlockUiState
            base as AudioBlockUiState
            existing.copy(
                timestamp = changed(existing.timestamp, updated.timestamp, base.timestamp),
                location = changed(existing.location, updated.location, base.location),
                timeZoneId = changed(existing.timeZoneId, updated.timeZoneId, base.timeZoneId),
                captureState = changed(existing.captureState, updated.captureState, base.captureState),
                caption = changed(existing.caption, updated.caption, base.caption),
                transcription = changed(existing.transcription, updated.transcription, base.transcription),
            )
        }
    }

internal fun <T> EntryEditorViewModel.changed(
    current: T,
    next: T,
    original: T,
): T = if (next != original) next else current

internal fun EntryEditorViewModel.applyTextChanges(
    existing: TextBlockUiState,
    updated: TextBlockUiState,
    base: TextBlockUiState,
): TextBlockUiState =
    existing.copy(
        timestamp = changed(existing.timestamp, updated.timestamp, base.timestamp),
        location = changed(existing.location, updated.location, base.location),
        timeZoneId = changed(existing.timeZoneId, updated.timeZoneId, base.timeZoneId),
        content = changed(existing.content, updated.content, base.content),
    )

internal fun EntryEditorViewModel.mergeCompletedMedia(
    existing: EntryBlockUiState,
    updated: EntryBlockUiState,
    preserveLegacyMetadata: Boolean,
): EntryBlockUiState? {
    if (existing is AudioBlockUiState && updated is AudioBlockUiState) {
        if ((
                existing.captureState is AudioCaptureState.Ready &&
                    (updated.captureState is AudioCaptureState.Recording || updated.captureState is AudioCaptureState.Stopping)
            ) ||
            (existing.captureState is AudioCaptureState.Stopping && updated.captureState is AudioCaptureState.Recording)
        ) {
            return null
        }
        if (preserveLegacyMetadata && existing.captureState != updated.captureState) {
            return updated.copy(caption = existing.caption, transcription = updated.transcription.ifBlank { existing.transcription })
        }
    }
    if (preserveLegacyMetadata &&
        existing is MediaBlockUiState &&
        updated is MediaBlockUiState &&
        existing.uri == null &&
        updated.uri != null
    ) {
        return when (updated) {
            is ImageBlockUiState -> updated.copy(caption = existing.caption)
            is VideoBlockUiState -> updated.copy(caption = existing.caption)
            is CameraBlockUiState -> updated.copy(caption = existing.caption)
            is AudioBlockUiState -> updated
        }
    }
    return updated
}

/**
 * Appends a new text block populated with [text] to the current entry.
 *
 * Unlike [setInitialTextContent], this works regardless of whether the editor
 * already has content, making it suitable for drag-and-drop text drops.
 */
internal fun EntryEditorViewModel.appendTextBlockImpl(text: String) {
    if (text.isBlank()) return
    val newBlock = TextBlockUiState(content = text)
    mutateEditorContent { state ->
        state.copy(
            blocks = state.blocks + newBlock,
            isModified = true,
        )
    }
}

/**
 * Removes a block from the entry.
 * Also clears the expanded block ID if the deleted block was currently expanded.
 */
internal fun EntryEditorViewModel.removeBlockImpl(blockId: Uuid) {
    if (editorState.value.isReadOnly(blockId)) {
        mutableEditorState.update { it.copy(errorMessage = Res.string.editor_error_block_saved_cannot_remove.toUiText()) }
        return
    }
    val block = mutableEditorState.value.blocks.find { it.id == blockId } ?: return
    if (block is AudioBlockUiState &&
        (block.captureState is AudioCaptureState.Recording || block.captureState is AudioCaptureState.Stopping)
    ) {
        mutableEditorState.update { it.copy(errorMessage = Res.string.editor_error_finish_recording_before_removing.toUiText()) }
        return
    }
    val removed =
        mutateEditorContent { currentState ->
            val currentBlock = currentState.blocks.firstOrNull { it.id == blockId } ?: return@mutateEditorContent currentState
            val captureActive =
                currentBlock is AudioBlockUiState &&
                    (currentBlock.captureState is AudioCaptureState.Recording || currentBlock.captureState is AudioCaptureState.Stopping)
            if (currentState.audioCaptureStartReservations.containsKey(blockId) ||
                currentState.isReadOnly(blockId) ||
                captureActive
            ) {
                return@mutateEditorContent currentState
            }
            val shouldClearExpanded = currentState.expandedBlockId == blockId
            val filteredBlocks = currentState.blocks.filterNot { it.id == blockId }

            currentState.copy(
                blocks = filteredBlocks,
                expandedBlockId = if (shouldClearExpanded) null else currentState.expandedBlockId,
                isModified = true,
            )
        }
    if (removed) audioBlockFinalizers.remove(blockId)
}
