package app.logdate.wear.v2.moments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.R
import app.logdate.wear.v2.playback.PlaybackFailure
import app.logdate.wear.v2.playback.PlaybackState
import kotlinx.datetime.TimeZone

object MomentRowTags {
    const val ROW = "moment_row"
    const val PLAY_PAUSE = "moment_row_play_pause"
    const val FAILURE = "moment_row_failure"
}

/** How a voice note's row shows and controls its playback, and why it did not play when it failed. */
data class MomentRowPlayback(
    val isPlaying: Boolean,
    val fraction: Float?,
    val onPlayPause: () -> Unit,
    val failure: PlaybackFailure? = null,
)

/**
 * The playback [note]'s row shows while the player is in [playback], or null for anything but a
 * voice note, and for any row when there is no [onPlayPause].
 */
fun rowPlayback(
    note: JournalNote,
    playback: PlaybackState,
    onPlayPause: ((JournalNote.Audio) -> Unit)?,
): MomentRowPlayback? {
    if (note !is JournalNote.Audio || onPlayPause == null) return null
    val active = (playback as? PlaybackState.Active)?.takeIf { it.noteId == note.uid }
    val failed = (playback as? PlaybackState.Failed)?.takeIf { it.noteId == note.uid }
    return MomentRowPlayback(
        isPlaying = active?.isPaused == false,
        fraction = active?.fraction,
        onPlayPause = { onPlayPause(note) },
        failure = failed?.reason,
    )
}

/**
 * A moment in a list: when it was, and what it is in a few words. With [onOpen] the row opens it;
 * with [playback] a voice note plays from the row, with a progress line while it plays.
 */
@Composable
fun MomentRow(
    note: JournalNote,
    timeZone: TimeZone,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playback: MomentRowPlayback? = null,
) {
    if (playback == null) {
        MomentSummary(note, timeZone, onOpen, modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                MomentSummary(note, timeZone, onOpen, Modifier)
                playback.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                }
            }
            IconButton(onClick = playback.onPlayPause, modifier = Modifier.testTag(MomentRowTags.PLAY_PAUSE)) {
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playback.isPlaying) R.string.wear_moment_pause else R.string.wear_moment_play),
                )
            }
        }
        playback.failure?.let { failure -> RowFailure(failure) }
    }
}

@Composable
private fun RowFailure(failure: PlaybackFailure) {
    Text(
        text = failureMessage(failure),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(MomentRowTags.FAILURE),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        maxLines = FAILURE_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MomentSummary(
    note: JournalNote,
    timeZone: TimeZone,
    onOpen: (() -> Unit)?,
    modifier: Modifier,
) {
    val openLabel = stringResource(R.string.wear_moment_open)
    val opens = if (onOpen == null) Modifier else Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .then(opens)
                .padding(vertical = 4.dp)
                .testTag(MomentRowTags.ROW),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = momentDayAndTime(note.creationTimestamp, timeZone),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MomentGlance(note)
    }
}

private const val FAILURE_LINES = 4
