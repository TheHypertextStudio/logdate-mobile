package app.logdate.feature.editor.ui.editor

import androidx.lifecycle.viewModelScope
import app.logdate.client.text.toUiText
import app.logdate.feature.editor.navigation.EditorLaunchMode
import app.logdate.feature.editor.ui.camera.CaptureMode
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel.LoadRequest
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_error_load_entry_failed
import kotlin.uuid.Uuid

/**
 * Sets the initial text content for a new note.
 * This creates a text block with the given content if there are no blocks yet.
 *
 * @param content The initial text content.
 */

internal fun EntryEditorViewModel.setInitialTextContentImpl(content: String) {
    if (content.isBlank()) return

    viewModelScope.launch {
        try {
            val currentState = mutableEditorState.value

            if (currentState.blocks.isEmpty()) {
                val textBlock = createNewBlock(BlockType.TEXT) as TextBlockUiState
                updateBlock(textBlock.copy(content = content))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.e("Failed to set initial text content: ${e.message}", e)
        }
    }
}

/** Places the suggested photo first, followed by the chosen entry input. */
internal fun EntryEditorViewModel.initializePhotoPromptImpl(
    photoUri: String,
    record: Boolean,
) {
    if (photoUri.isBlank() || initializedPhotoPromptUri == photoUri) return
    initializedPhotoPromptUri = photoUri
    if (mutableEditorState.value.blocks.none { (it as? MediaBlockUiState)?.uri == photoUri }) {
        val photo = createNewBlock(BlockType.IMAGE) as ImageBlockUiState
        updateBlock(photo.copy(uri = photoUri))
    }
    createNewBlock(if (record) BlockType.AUDIO else BlockType.TEXT)
}

/**
 * Prepares a new entry for [mode]: a recording for voice, the camera for photos or video, and
 * nothing extra for text. Safe to call again when the screen recomposes; it never adds a second
 * capture block.
 */
internal fun EntryEditorViewModel.initializeLaunchModeImpl(mode: EditorLaunchMode) {
    val blocks = mutableEditorState.value.blocks
    when (mode) {
        EditorLaunchMode.Default, EditorLaunchMode.Text -> {
            Unit
        }

        EditorLaunchMode.Voice -> {
            if (blocks.any { it is AudioBlockUiState }) return
            val audio = createNewBlock(BlockType.AUDIO) as AudioBlockUiState
            updateBlock(audio.copy(startOnOpen = true))
        }

        EditorLaunchMode.Camera, EditorLaunchMode.Video -> {
            if (blocks.any { it is CameraBlockUiState }) return
            val camera = createNewBlock(BlockType.CAMERA) as CameraBlockUiState
            if (mode == EditorLaunchMode.Video) updateBlock(camera.copy(initialCaptureMode = CaptureMode.VIDEO))
        }
    }
}

/**
 * Creates blocks for attachment URIs: those supplied when opening the editor, and media dropped,
 * pasted, or sent by the keyboard, already copied into LogDate's store.
 */
internal fun EntryEditorViewModel.setInitialAttachmentsImpl(attachmentUris: List<String>) {
    if (attachmentUris.isEmpty()) return

    viewModelScope.launch {
        try {
            attachmentUris.forEach { uri ->
                // NoteEditorScreen replays initial intent inputs after an Activity
                // recreation. The ViewModel survives configuration changes, so don't append
                // another block for a URI that is already attached. LogDate's store names a file
                // by its contents, so the same photo pasted twice is also attached once.
                val alreadyInitialized =
                    mutableEditorState.value.blocks.any { block ->
                        (block as? MediaBlockUiState)?.uri == uri
                    }
                if (alreadyInitialized) return@forEach

                val blockType = attachmentBlockType(uri)

                when (blockType) {
                    BlockType.IMAGE -> {
                        val block = createNewBlock(BlockType.IMAGE) as ImageBlockUiState
                        updateBlock(block.copy(uri = uri))
                    }

                    BlockType.VIDEO -> {
                        val block = createNewBlock(BlockType.VIDEO) as VideoBlockUiState
                        updateBlock(block.copy(uri = uri))
                    }

                    BlockType.AUDIO -> {
                        val block = createNewBlock(BlockType.AUDIO) as AudioBlockUiState
                        val attachment = AudioCaptureState.Ready(uri = uri, durationMs = 0L)
                        updateBlock(block.copy(captureState = attachment))
                        viewModelScope.launch {
                            val resolved = resolveAudioDuration(attachment)
                            val current = mutableEditorState.value
                            val attached = current.blocks.filterIsInstance<AudioBlockUiState>().find { it.id == block.id }
                            if (!current.isEditingLocked && !current.shouldExit && attached?.captureState == attachment) {
                                when (resolved) {
                                    is AudioCaptureState.Ready -> {
                                        updateBlock(attached.copy(captureState = resolved))
                                    }

                                    is AudioCaptureState.Failed -> {
                                        mutableEditorState.update { it.copy(errorMessage = resolved.reason.toUiText()) }
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Napier.w("Unhandled attachment type for URI: $uri")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.e("Failed to add initial attachments: ${e.message}", e)
        }
    }
}

/**
 * Loads an existing entry for editing.
 *
 * Fetches the entry by ID and populates the editor state with its content. If provided,
 * journalId is used to set the selected journal context.
 *
 * @param entryId The unique identifier of the entry to load and display for editing
 * @param journalId Optional journal ID to set as the selected journal context when loading the entry
 */
internal fun EntryEditorViewModel.loadExistingEntryImpl(
    entryId: Uuid,
    journalId: Uuid? = null,
) {
    val load = beginEditorContentLoad(LoadRequest.Existing(entryId, journalId)) ?: return
    viewModelScope.launch {
        var accepted = false
        try {
            contentLoader.loadEntry(entryId).fold(
                onSuccess = { loaded ->
                    accepted =
                        acceptEditorContentLoad(load) { current ->
                            current.copy(
                                expandedBlockId = null,
                                blocks = listOf(loaded.block),
                                entryEditBase = loaded.editBase,
                                capturedOnWatch = loaded.capturedOnWatch,
                                selectedJournalIds = journalId?.let(::listOf) ?: current.selectedJournalIds,
                                hasJournalSelectionChanges = false,
                                isModified = false,
                                errorMessage = null,
                            )
                        }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Napier.e("Failed to load existing entry: $entryId", error)
                    finishRejectedEditorContentLoad(load, Res.string.editor_error_load_entry_failed.toUiText(error.message.toString()))
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!accepted) finishRejectedEditorContentLoad(load)
        }
    }
}
