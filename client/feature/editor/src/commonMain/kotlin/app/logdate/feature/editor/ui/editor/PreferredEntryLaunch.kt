package app.logdate.feature.editor.ui.editor

import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.EntryStartMode
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun EntryEditorViewModel.initializePreferredEntryModeImpl(mode: EntryStartMode?) {
    if (preferredEntryInitialized) return
    preferredEntryInitialized = true
    viewModelScope.launch {
        val preferred =
            mode ?: try {
                preferences?.observeEntryStartMode()?.first() ?: EntryStartMode.TEXT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not read preferred entry mode", error)
                EntryStartMode.TEXT
            }
        val current = editorState.first { !it.isLoading }
        if (activeLoadRequest != null ||
            current.draftState is DraftState.Active ||
            current.entryEditBase != null ||
            current.blocks.isNotEmpty() ||
            current.isSaving ||
            current.isEditingLocked ||
            current.shouldExit
        ) {
            return@launch
        }
        val type =
            when (preferred) {
                EntryStartMode.TEXT -> BlockType.TEXT
                EntryStartMode.HANDWRITING -> BlockType.HANDWRITING
                EntryStartMode.CAMERA -> BlockType.CAMERA
                EntryStartMode.AUDIO -> BlockType.AUDIO
                EntryStartMode.GALLERY -> BlockType.IMAGE
            }
        val block = createNewBlock(type)
        setExpandedBlockId(block.id)
    }
}
