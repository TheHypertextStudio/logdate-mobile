@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.merge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.repository.journals.JournalMergePreview

/** Let a fast local commit finish its cover transition; restored commits open directly. */
@Composable
internal fun JournalMergePresentation(
    state: JournalMergeUiState,
    onJournalMerged: (JournalMergeOperation) -> Unit,
    content: @Composable (JournalMergeUiState, () -> Unit) -> Unit,
) {
    var lastPreview by remember { mutableStateOf<JournalMergePreview?>(null) }
    var settled by remember { mutableStateOf(false) }
    val stage = state.stage
    SideEffect {
        when (stage) {
            is JournalMergeStage.Review -> {
                lastPreview = stage.preview
                settled = false
            }

            is JournalMergeStage.Merging -> {
                lastPreview = stage.preview
            }

            else -> {
                Unit
            }
        }
    }
    val complete = stage as? JournalMergeStage.Complete
    LaunchedEffect(complete, settled) {
        if (complete != null && (lastPreview == null || settled)) onJournalMerged(complete.operation)
    }
    val presented =
        if (complete != null) {
            lastPreview?.let { state.copy(stage = JournalMergeStage.Merging(it)) } ?: state
        } else {
            state
        }
    content(presented) { settled = true }
}
