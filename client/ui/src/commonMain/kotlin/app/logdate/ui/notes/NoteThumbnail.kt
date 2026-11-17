@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.audio.AudioWaveformComponent
import app.logdate.ui.audio.formatAudioDuration
import app.logdate.ui.audio.rememberWaveform
import app.logdate.ui.common.parseMarkdownDocument
import app.logdate.ui.handwriting.HandwritingPreview
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.handwriting_unreadable
import logdate.client.ui.generated.resources.note_block_photo
import logdate.client.ui.generated.resources.note_block_video
import logdate.client.ui.generated.resources.note_block_voice_note_length
import org.jetbrains.compose.resources.stringResource

/**
 * A note's own words: its text, its caption, or its transcript, as plain text on one line with
 * Markdown syntax removed. Null when it has none, so callers show the note itself (a
 * [NoteThumbnail]) instead of naming its type.
 */
fun JournalNote.noteWords(): String? = readableWords()?.replace(WHITESPACE_RUN, " ")?.takeIf(String::isNotEmpty)

/** The first non-blank line of a note's [noteWords], for compact rows that show one line. */
fun JournalNote.noteFirstLine(): String? =
    readableWords()
        ?.lineSequence()
        ?.map { it.trim().replace(WHITESPACE_RUN, " ") }
        ?.firstOrNull(String::isNotEmpty)

private fun JournalNote.readableWords(): String? =
    when (this) {
        is JournalNote.Text -> content.asPlainText()
        is JournalNote.Image -> caption.asPlainText()
        is JournalNote.Video -> caption.asPlainText()
        is JournalNote.Audio -> transcript?.plainText?.takeIf(String::isNotBlank) ?: transcription.ifBlank { caption.asPlainText() }
        is JournalNote.Handwriting -> null
    }?.trim()?.takeIf(String::isNotEmpty)

private fun String.asPlainText(): String = if (isBlank()) "" else parseMarkdownDocument(this).plainText

private val WHITESPACE_RUN = Regex("\\s+")

/** The words a photo or video note's media is described by for accessibility. */
@Composable
fun JournalNote.mediaDescription(): String =
    remember(this) { noteWords() } ?: stringResource(if (this is JournalNote.Video) Res.string.note_block_video else Res.string.note_block_photo)

/**
 * A small square of a note's real content, for list rows and pickers.
 *
 * Distinct block types stay distinct: a photo shows the photo, a video its frame, handwriting its
 * ink, a voice note its waveform and length, and a text note its opening words. Media loading
 * and failure states come from [NoteMediaImage].
 */
@Composable
fun NoteThumbnail(
    note: JournalNote,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val tile = modifier.size(size).clip(shape)
    when (note) {
        is JournalNote.Image -> {
            NoteMediaImage(note.mediaRef, NoteMediaKind.Photo, note.mediaDescription(), tile, compact = true)
        }

        is JournalNote.Video -> {
            NoteMediaImage(note.mediaRef, NoteMediaKind.Video, note.mediaDescription(), tile, compact = true)
        }

        is JournalNote.Handwriting -> {
            if (note.unreadableContent != null) {
                Box(tile.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Gesture,
                        contentDescription = stringResource(Res.string.handwriting_unreadable),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                HandwritingPreview(note.document, tile)
            }
        }

        is JournalNote.Audio -> {
            NoteAudioThumbnail(note.mediaRef, note.durationMs, tile)
        }

        is JournalNote.Text -> {
            // Decorative: rows beside the tile already speak the note's words, so the tile
            // shouldn't announce them a second time.
            val excerpt = remember(note.content) { note.noteWords().orEmpty() }
            Box(tile.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(6.dp).clearAndSetSemantics {}) {
                Text(
                    excerpt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A voice note at thumbnail size: its waveform and its length. The waveform fills in once the
 * recording's amplitudes are read; until then the length alone is shown, never a generic label.
 */
@Composable
fun NoteAudioThumbnail(
    mediaRef: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val waveform = rememberWaveform(mediaRef, durationMs)
    val length = remember(durationMs) { formatAudioDuration(durationMs) }
    val description = contentDescription ?: stringResource(Res.string.note_block_voice_note_length, length)
    Column(
        modifier
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(6.dp)
            .clearAndSetSemantics { this.contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val amplitudes = remember(waveform) { waveform.peaks(THUMBNAIL_BARS) }
            if (amplitudes.isNotEmpty()) {
                AudioWaveformComponent(
                    audioLevels = amplitudes,
                    modifier = Modifier.fillMaxSize(),
                    waveformColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    strokeWidth = 2.dp,
                    maxBars = THUMBNAIL_BARS,
                    minHeight = 4.dp,
                )
            }
        }
        Text(length, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

private const val THUMBNAIL_BARS = 12

/** The loudest sample in each of [count] equal runs, so a whole recording fits a few bars. */
internal fun List<Float>.peaks(count: Int): List<Float> {
    if (isEmpty() || count <= 0) return emptyList()
    if (size <= count) return this
    return List(count) { bucket ->
        val start = bucket * size / count
        val end = ((bucket + 1) * size / count).coerceAtLeast(start + 1)
        subList(start, end).max()
    }
}
