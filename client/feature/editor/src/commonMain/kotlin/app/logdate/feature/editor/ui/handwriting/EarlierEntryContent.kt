@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.audio.AudioPlaybackComponent
import app.logdate.feature.editor.ui.video.VideoPlayerContent
import app.logdate.ui.notes.NoteMediaKind
import app.logdate.ui.notes.ProportionalNoteMedia
import app.logdate.ui.notes.mediaDescription
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_page_handwriting_newer_version
import logdate.client.feature.editor.generated.resources.entry_page_handwritten_entry
import org.jetbrains.compose.resources.stringResource

/**
 * An earlier note from the same day, rendered as itself: the photo, the video, the ink, the
 * recording or the words. Never a label naming the note's type in place of its content.
 *
 * One renderer serves every place the editor shows earlier notes. [expanded] is the reading
 * destination (the day browser): full text, larger media and an inline video player. Collapsed,
 * as on the start screen and above the draft, text is clipped and a video shows its frame so a
 * list of notes doesn't start several players.
 */
@Composable
internal fun EarlierEntryContent(
    note: JournalNote,
    expanded: Boolean = true,
) {
    val mediaMaxHeight = if (expanded) 400.dp else 240.dp
    when (note) {
        is JournalNote.Text -> {
            Text(
                note.content,
                style = if (expanded) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is JournalNote.Handwriting -> {
            if (note.unreadableContent != null) {
                Text(stringResource(Res.string.entry_page_handwriting_newer_version))
            } else {
                HandwritingCanvas(
                    note.document.toPage(),
                    Modifier.fillMaxWidth().heightIn(min = if (expanded) 208.dp else 120.dp, max = if (expanded) 320.dp else 200.dp),
                    description = stringResource(Res.string.entry_page_handwritten_entry),
                    fitPreviewToWidth = !expanded,
                    adaptInkToPaper = true,
                    paperColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
            }
        }

        is JournalNote.Image -> {
            EarlierNoteMedia(note.mediaRef, NoteMediaKind.Photo, note.mediaDescription(), mediaMaxHeight)
            Caption(note.caption)
        }

        is JournalNote.Video -> {
            if (expanded) {
                VideoPlayerContent(note.mediaRef, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = mediaMaxHeight))
            } else {
                EarlierNoteMedia(note.mediaRef, NoteMediaKind.Video, note.mediaDescription(), mediaMaxHeight)
            }
            Caption(note.caption)
        }

        is JournalNote.Audio -> {
            AudioPlaybackComponent(
                note.mediaRef,
                Modifier.fillMaxWidth(),
                knownDurationMs = note.durationMs,
                contentPadding = if (expanded) 16.dp else 0.dp,
            )
            Caption(note.caption)
        }
    }
}

@Composable
private fun EarlierNoteMedia(
    mediaRef: String,
    kind: NoteMediaKind,
    description: String,
    maxHeight: Dp,
) {
    ProportionalNoteMedia(mediaRef, kind, description, Modifier.clip(RoundedCornerShape(8.dp)), maxHeight = maxHeight)
}

@Composable
private fun Caption(caption: String) {
    if (caption.isNotBlank()) Text(caption)
}
