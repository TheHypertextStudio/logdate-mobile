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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.audio.AudioPlaybackComponent
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import app.logdate.feature.editor.ui.layout.clipEntryContentToSafeWindow
import app.logdate.util.localTime
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_start_earlier_today
import logdate.client.feature.editor.generated.resources.ink_title
import logdate.client.feature.editor.generated.resources.memory_photo
import logdate.client.feature.editor.generated.resources.memory_video
import org.jetbrains.compose.resources.stringResource

internal fun entryDayContext(
    state: EditorState,
    enabled: Boolean,
): (@Composable () -> Unit)? =
    if (!enabled || state.blocks.isEmpty() || state.todayNotes.isEmpty()) null else ({ EarlierEntryContext(state.todayNotes) })

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
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = EditorBlockMinHeight).testTag("earlier_note_${note.uid}"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
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
                        if (note is JournalNote.Audio) {
                            AudioPlaybackComponent(
                                note.mediaRef,
                                modifier = Modifier.clipEntryContentToSafeWindow(),
                                knownDurationMs = note.durationMs,
                                contentPadding = 0.dp,
                            )
                            if (note.caption.isNotBlank()) Text(note.caption, Modifier.clipEntryContentToSafeWindow())
                        } else {
                            val preview =
                                when (note) {
                                    is JournalNote.Text -> note.content
                                    is JournalNote.Handwriting -> stringResource(Res.string.ink_title)
                                    is JournalNote.Image -> note.caption.ifBlank { stringResource(Res.string.memory_photo) }
                                    is JournalNote.Video -> note.caption.ifBlank { stringResource(Res.string.memory_video) }
                                    is JournalNote.Audio -> error("Audio has its own presentation")
                                }
                            Text(
                                preview,
                                Modifier.clipEntryContentToSafeWindow(),
                                style = if (expanded) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
