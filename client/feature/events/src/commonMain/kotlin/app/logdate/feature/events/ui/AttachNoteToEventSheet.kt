@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.events.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.notes.NoteThumbnail
import app.logdate.ui.platform.PlatformSheet
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateTimeShort
import logdate.client.feature.events.generated.resources.Res
import logdate.client.feature.events.generated.resources.event_attach_capture_empty
import logdate.client.feature.events.generated.resources.event_attach_capture_title
import logdate.client.ui.generated.resources.common_done
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Adaptive sheet/dialog for picking a note to attach to an event.
 *
 * The sheet shows the candidate notes the ViewModel computed (notes within the event's time
 * window that aren't already linked). Selecting a note immediately attaches it via [onAttach]
 * and the sheet stays open so the user can attach several captures in a row. The "Done" button
 * closes the sheet.
 *
 * @param attachableNotes candidate notes to display.
 * @param onAttach invoked with the picked note id when the user taps a row.
 * @param onDismiss invoked when the user dismisses the sheet (back press, scrim tap, or "Done").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachNoteToEventSheet(
    attachableNotes: List<JournalNote>,
    onAttach: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val isExpanded =
        currentWindowAdaptiveInfoV2()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)

    if (isExpanded) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.event_attach_capture_title)) },
            text = {
                AttachNoteList(
                    attachableNotes = attachableNotes,
                    onAttach = onAttach,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_done)) }
            },
        )
    } else {
        val sheetState = rememberModalBottomSheetState()
        PlatformSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = Spacing.lg)) {
                Text(
                    text = stringResource(Res.string.event_attach_capture_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                )
                AttachNoteList(
                    attachableNotes = attachableNotes,
                    onAttach = onAttach,
                )
            }
        }
    }
}

@Composable
private fun AttachNoteList(
    attachableNotes: List<JournalNote>,
    onAttach: (Uuid) -> Unit,
) {
    if (attachableNotes.isEmpty()) {
        Text(
            text = stringResource(Res.string.event_attach_capture_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.lg),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(attachableNotes, key = { it.uid }) { note ->
            val date = note.creationTimestamp.toReadableDateTimeShort()
            val headline = note.headline()
            ListItem(
                leadingContent = { NoteThumbnail(note, size = 48.dp) },
                headlineContent = { Text(headline ?: date, maxLines = 2) },
                supportingContent = headline?.let { { Text(date) } },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAttach(note.uid) },
            )
        }
    }
}
