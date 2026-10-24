@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.events.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.text.asString
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateTimeShort
import coil3.compose.AsyncImage
import logdate.client.feature.events.generated.resources.Res
import logdate.client.feature.events.generated.resources.cd_event_linked_note_detach
import logdate.client.feature.events.generated.resources.cd_event_linked_photo
import logdate.client.feature.events.generated.resources.event_attach_capture_title
import logdate.client.feature.events.generated.resources.event_detail_attached_heading
import logdate.client.feature.events.generated.resources.event_detail_attached_heading_count
import logdate.client.feature.events.generated.resources.event_detail_no_attached_captures
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * "Attached items" section of the event detail editor.
 *
 * Renders the journal notes currently linked to this event as a vertical list of cards. Each
 * card shows a small preview of the note's content (text snippet, image thumb, or media icon)
 * plus a close button that detaches the note from the event. A "+ Attach a capture" button at
 * the bottom opens the [AttachNoteToEventSheet].
 *
 * Empty state is a single helper line and the attach button — never a placeholder card.
 *
 * @param notes the notes currently linked to the event.
 * @param onUnlink invoked with the note id when the user taps its close button.
 * @param onAddCapture invoked when the user taps the "Attach a capture" button.
 */
@Composable
internal fun EventLinkedNotesSection(
    notes: List<JournalNote>,
    onUnlink: (Uuid) -> Unit,
    onAddCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text =
                if (notes.isEmpty()) {
                    stringResource(Res.string.event_detail_attached_heading)
                } else {
                    stringResource(Res.string.event_detail_attached_heading_count, notes.size)
                },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (notes.isEmpty()) {
            Text(
                text = stringResource(Res.string.event_detail_no_attached_captures),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            notes.forEach { note ->
                LinkedNoteCard(
                    note = note,
                    onUnlink = { onUnlink(note.uid) },
                )
            }
        }

        FilledTonalButton(onClick = onAddCapture) {
            Icon(painter = PlatformIcons.add(), contentDescription = null)
            Text(text = stringResource(Res.string.event_attach_capture_title), modifier = Modifier.padding(start = Spacing.sm))
        }
    }
}

@Composable
private fun LinkedNoteCard(
    note: JournalNote,
    onUnlink: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(Spacing.md),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            NotePreview(note)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.headline().asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                Text(
                    text = note.creationTimestamp.toReadableDateTimeShort(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUnlink) {
                Icon(painter = PlatformIcons.close(), contentDescription = stringResource(Res.string.cd_event_linked_note_detach))
            }
        }
    }
}

@Composable
private fun NotePreview(note: JournalNote) {
    val previewSize = 56.dp
    val shape = RoundedCornerShape(Spacing.sm)
    when (note) {
        is JournalNote.Handwriting -> {
            app.logdate.ui.handwriting
                .HandwritingPreview(note.document, Modifier.size(previewSize).clip(shape), unreadable = note.unreadableContent != null)
        }

        is JournalNote.Image -> {
            AsyncImage(
                model = note.mediaRef,
                contentDescription = stringResource(Res.string.cd_event_linked_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(previewSize).clip(shape),
            )
        }

        is JournalNote.Video -> {
            Box(
                modifier =
                    Modifier
                        .size(previewSize)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = PlatformIcons.playCircle(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is JournalNote.Audio -> {
            Box(
                modifier =
                    Modifier
                        .size(previewSize)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = PlatformIcons.audioFile(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is JournalNote.Text -> {
            Box(
                modifier =
                    Modifier
                        .size(previewSize)
                        .aspectRatio(1f)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}
