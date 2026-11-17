@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.handwriting.EarlierEntryContent
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import app.logdate.feature.editor.ui.layout.clipEntryContentToSafeWindow
import app.logdate.util.localTime
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_start_earlier_today
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

internal fun entryDayContext(state: EditorState): (@Composable () -> Unit)? =
    if (state.blocks.isEmpty() || state.todayNotes.isEmpty()) null else ({ EarlierEntryContext(state.todayNotes) })

@Composable
internal fun EarlierEntryContext(
    notes: List<JournalNote>,
    expanded: Boolean = false,
    showHeading: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().testTag("earlier_today"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) Text(stringResource(Res.string.entry_start_earlier_today), style = MaterialTheme.typography.titleSmall)
        notes.forEach { note ->
            key(note.uid) {
                EarlierNoteCard(note, expanded = expanded)
            }
        }
    }
}

/** One earlier note as a card: its time, then the note itself. Opens the entry when [onOpen] is set. */
@Composable
internal fun EarlierNoteCard(
    note: JournalNote,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onOpen: ((Uuid) -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clipEntryContentToSafeWindow(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                note.creationTimestamp.localTime,
                Modifier.clipEntryContentToSafeWindow(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            EarlierEntryContent(note, expanded = expanded)
        }
    }
    val cardModifier = modifier.testTag("earlier_note_${note.uid}").fillMaxWidth().heightIn(min = EditorBlockMinHeight)
    val shape = RoundedCornerShape(12.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (onOpen != null) {
        Surface(onClick = { onOpen(note.uid) }, modifier = cardModifier, shape = shape, color = color) { content() }
    } else {
        Surface(modifier = cardModifier, shape = shape, color = color) { content() }
    }
}
