@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.prototype

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.feature.editor.ui.EntryEditorContent
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Debug entry point for exercising real drafts and saved notes through the production editor. */
@Composable
internal fun HandwritingRealEditorContent(
    resumeDraft: Boolean,
    resumeSaved: Boolean,
    onExit: () -> Unit,
) {
    val editor = koinViewModel<EntryEditorViewModel>()
    val notes = koinInject<JournalNotesRepository>()
    LaunchedEffect(resumeDraft, resumeSaved) {
        when {
            resumeDraft -> {
                editor.editorState
                    .first { it.availableDrafts.isNotEmpty() }
                    .availableDrafts
                    .maxByOrNull { it.updatedAt }
                    ?.let { editor.loadDraft(it.id) }
            }

            resumeSaved -> {
                notes.allNotesObserved
                    .first()
                    .filterIsInstance<JournalNote.Handwriting>()
                    .maxByOrNull { it.creationTimestamp }
                    ?.let { editor.loadExistingEntry(it.uid) }
            }

            else -> {
                editor.initializePreferredEntryMode()
            }
        }
    }
    EntryEditorContent(
        onNavigateBack = onExit,
        onEntrySaved = onExit,
        viewModel = editor,
        handwritingEnabled = true,
        blockFocusEnabled = true,
    )
}
