@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui

import androidx.compose.runtime.Composable
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.handwriting.EntryFocusPrototypeHeader
import app.logdate.feature.editor.ui.handwriting.StoryCaptureMode
import app.logdate.feature.editor.ui.layout.EditorFocus
import app.logdate.feature.editor.ui.layout.editorFocus
import kotlin.uuid.Uuid

@Composable
internal fun EntryEditorTopBar(
    state: EditorState,
    immersive: Boolean,
    autoSaveStatus: AutoSaveStatus,
    onBack: () -> Unit,
    onReturnToEntry: () -> Unit,
    onShowDrafts: () -> Unit,
    onSave: () -> Unit,
    onCaptureMode: (StoryCaptureMode) -> Unit,
    onJournalSelection: (List<Uuid>) -> Unit,
) {
    val recording = state.editorFocus() is EditorFocus.Recording
    val enabled = !state.isSaving && !state.shouldExit && state.audioCaptureStartReservations.isEmpty()
    val selectedJournal = state.availableJournals.firstOrNull { it.id in state.selectedJournalIds }
    EntryFocusPrototypeHeader(
        block = state.blocks.firstOrNull { it.id == state.expandedBlockId },
        canvasOnly = immersive,
        recording = state.isRecordingAudio || state.audioCaptureStartReservations.isNotEmpty(),
        onReturnToEntry = onReturnToEntry,
        onExit = onBack,
        journal = selectedJournal,
        journalSelected = selectedJournal != null,
        onJournalSelection = {},
        onCaptureMode = onCaptureMode,
        onSave = onSave,
        saveInProgress = state.isSaving,
        saveEnabled = !recording && !state.isEditingLocked && enabled && state.blocks.isNotEmpty(),
        availableJournals = state.availableJournals,
        onSelectJournal = { onJournalSelection(listOf(it.id)) },
        onShowDrafts = onShowDrafts,
        floating = true,
        draftCount = state.availableDrafts.size,
        autoSaveStatus = autoSaveStatus,
    )
}
