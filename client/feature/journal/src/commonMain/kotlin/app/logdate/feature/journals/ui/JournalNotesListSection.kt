@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.journals.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.audio.AudioLabelResolver
import app.logdate.feature.editor.audio.formatAudioLabel
import app.logdate.ui.common.MarkdownPreviewText
import app.logdate.ui.common.MarkdownText
import app.logdate.ui.handwriting.HandwritingPreview
import app.logdate.util.toReadableDateShort
import app.logdate.util.toReadableDateTimeShort
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.*
import logdate.client.feature.journal.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * A section that displays a list of notes associated with a journal.
 */
@Composable
fun JournalNotesListSection(
    notes: List<JournalNote>,
    onNoteClick: (JournalNote) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.journal_entries),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (notes.isEmpty()) {
                Text(
                    text = stringResource(Res.string.no_entries_in_this_journal_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(notes) { note ->
                        JournalNoteItem(
                            note = note,
                            onClick = { onNoteClick(note) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalNoteItem(
    note: JournalNote,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Display date and time above the card
        Text(
            text = note.creationTimestamp.toReadableDateTimeShort(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                when (note) {
                    is JournalNote.Handwriting -> {
                        HandwritingPreview(
                            note.document,
                            Modifier.fillMaxWidth().height(240.dp),
                            unreadable =
                                note.unreadableContent != null,
                        )
                    }

                    is JournalNote.Text -> {
                        AnimatedVisibility(
                            visible = !expanded,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            MarkdownPreviewText(
                                content = note.content,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            MarkdownText(
                                content = note.content,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    is JournalNote.Image -> {
                        Text(
                            text = stringResource(Res.string.image_note),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        AsyncImage(
                            model = note.mediaRef,
                            contentDescription =
                                stringResource(
                                    Res.string.cd_journal_photo_from_date,
                                    note.creationTimestamp.toReadableDateShort(),
                                ),
                            modifier = if (expanded) Modifier.fillMaxWidth() else Modifier,
                        )
                    }

                    is JournalNote.Video -> {
                        Text(
                            text = stringResource(Res.string.video_note),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is JournalNote.Audio -> {
                        val labelResolver = remember { AudioLabelResolver() }
                        val labelResult =
                            remember(note.creationTimestamp, note.location) {
                                labelResolver.resolve(
                                    createdAt = note.creationTimestamp,
                                    locationName = note.location?.displayName,
                                    latitude = note.location?.effectiveLatitude,
                                    longitude = note.location?.effectiveLongitude,
                                )
                            }
                        Text(
                            text = formatAudioLabel(labelResult),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
