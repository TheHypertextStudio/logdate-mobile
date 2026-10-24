package app.logdate.wear.presentation.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.R
import app.logdate.wear.playback.AudioOutputState
import app.logdate.wear.v2.playon.OutputPicker
import app.logdate.wear.v2.playon.OutputPickerRoute
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

internal object WearDayDetailTags {
    const val OUTPUT_SUMMARY = "wear_day_detail_output_summary"

    fun audioNote(noteId: kotlin.uuid.Uuid): String = "wear_day_detail_audio_note_$noteId"
}

@Composable
fun WearDayDetailScreen(
    date: LocalDate,
    viewModel: WearTimelineViewModel,
    onOpenTextEntry: (kotlin.uuid.Uuid) -> Unit,
) {
    val outputPicker: OutputPicker = koinInject()
    LaunchedEffect(date) {
        viewModel.selectDay(date)
    }
    val detail by viewModel.selectedDayState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val outputState by viewModel.audioOutputState.collectAsState()
    val dayDetail = detail ?: return

    WearDayDetailContent(
        detail = dayDetail,
        playbackState = playbackState,
        audioOutputState = outputState,
        onToggleNote = viewModel::toggleNote,
        onOpenTextEntry = onOpenTextEntry,
        onChooseOutput = { outputPicker.open() },
        canChooseOutput = outputPicker.route != OutputPickerRoute.NONE,
    )
}

@Composable
internal fun WearDayDetailContent(
    detail: WearDayDetailUiState,
    playbackState: WearPlaybackUiState,
    audioOutputState: AudioOutputState,
    onToggleNote: (JournalNote.Audio) -> Unit,
    onOpenTextEntry: (kotlin.uuid.Uuid) -> Unit = {},
    onChooseOutput: () -> Unit = {},
    canChooseOutput: Boolean = true,
    timeSource: TimeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()),
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        timeText = { TimeText(timeSource = timeSource) },
        scrollState = listState,
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
        ) {
            item(key = "header") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDetailHeader(detail.date),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                    )
                    if (detail.entries.any { it is JournalNote.Audio } && canChooseOutput) {
                        OutputRouteSummaryCard(onClick = onChooseOutput)
                    }
                }
            }

            if (detail.entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.wear_day_detail_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(
                    items = detail.entries,
                    key = { it.uid.toString() },
                ) { note ->
                    NoteEntryCard(
                        note = note,
                        playbackState = playbackState,
                        audioOutputState = audioOutputState,
                        onToggleNote = onToggleNote,
                        onOpenTextEntry = onOpenTextEntry,
                        onChooseOutput = onChooseOutput,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputRouteSummaryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(WearDayDetailTags.OUTPUT_SUMMARY).padding(top = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Column {
                Text(text = stringResource(R.string.wear_audio_output), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = stringResource(R.string.wear_audio_output_action),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoteEntryCard(
    note: JournalNote,
    playbackState: WearPlaybackUiState,
    audioOutputState: AudioOutputState,
    onToggleNote: (JournalNote.Audio) -> Unit,
    onOpenTextEntry: (kotlin.uuid.Uuid) -> Unit,
    onChooseOutput: () -> Unit,
) {
    val timezone = TimeZone.currentSystemDefault()
    val time = note.creationTimestamp.toLocalDateTime(timezone)
    val timeLabel =
        "%d:%02d %s".format(
            if (time.hour % 12 == 0) 12 else time.hour % 12,
            time.minute,
            if (time.hour < 12) "am" else "pm",
        )

    when (note) {
        is JournalNote.Audio -> {
            AudioNoteCard(
                note = note,
                timeLabel = timeLabel,
                playbackState = playbackState,
                audioOutputState = audioOutputState,
                onToggle = { onToggleNote(note) },
                onChooseOutput = onChooseOutput,
            )
        }

        is JournalNote.Text -> {
            Card(
                onClick = { onOpenTextEntry(note.uid) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.TextFields,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = note.content.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is JournalNote.Image -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.wear_day_detail_photo),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (note.caption.isNotBlank()) {
                    Text(text = note.caption, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is JournalNote.Handwriting -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(20.dp),
                        ).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(12.dp),
            ) {
                Text(text = stringResource(R.string.wear_moment_handwriting), style = MaterialTheme.typography.bodySmall)
                Text(text = timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        is JournalNote.Video -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.wear_day_detail_video),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (note.caption.isNotBlank()) {
                    Text(text = note.caption, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Resolved display state for an audio note card.
 */
private sealed interface AudioCardState {
    data object NoOutput : AudioCardState

    data object Preparing : AudioCardState

    data class Playing(
        val progress: Float,
        val durationMs: Long,
    ) : AudioCardState

    data object Error : AudioCardState

    data object Idle : AudioCardState
}

private fun resolveAudioCardState(
    noteId: kotlin.uuid.Uuid,
    playbackState: WearPlaybackUiState,
    audioOutputState: AudioOutputState,
): AudioCardState {
    if (audioOutputState is AudioOutputState.Unavailable) return AudioCardState.NoOutput
    return when (playbackState) {
        is WearPlaybackUiState.Active -> {
            if (playbackState.noteId == noteId) {
                AudioCardState.Playing(
                    progress = playbackState.progress,
                    durationMs = playbackState.durationMs,
                )
            } else {
                AudioCardState.Idle
            }
        }

        is WearPlaybackUiState.BlockedOutput -> {
            if (playbackState.noteId == noteId) AudioCardState.NoOutput else AudioCardState.Idle
        }

        is WearPlaybackUiState.Error -> {
            if (playbackState.noteId == noteId) AudioCardState.Error else AudioCardState.Idle
        }

        is WearPlaybackUiState.Idle -> {
            AudioCardState.Idle
        }

        is WearPlaybackUiState.Preparing -> {
            if (playbackState.noteId == noteId) AudioCardState.Preparing else AudioCardState.Idle
        }
    }
}

@Composable
private fun AudioNoteCard(
    note: JournalNote.Audio,
    timeLabel: String,
    playbackState: WearPlaybackUiState,
    audioOutputState: AudioOutputState,
    onToggle: () -> Unit,
    onChooseOutput: () -> Unit,
) {
    val cardState = resolveAudioCardState(note.uid, playbackState, audioOutputState)

    Card(
        onClick = {
            when (cardState) {
                is AudioCardState.NoOutput -> onChooseOutput()
                is AudioCardState.Preparing -> onToggle()
                is AudioCardState.Playing -> onToggle()
                is AudioCardState.Error -> onToggle()
                is AudioCardState.Idle -> onToggle()
            }
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(WearDayDetailTags.audioNote(note.uid)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (cardState) {
                is AudioCardState.NoOutput -> {
                    Icon(
                        Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.wear_playback_choose_output),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is AudioCardState.Preparing -> {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.wear_playback_preparing_audio),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }

                is AudioCardState.Playing -> {
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier.size(24.dp),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.wear_playback_stop),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    val elapsed = (cardState.progress * cardState.durationMs / 1000).toLong()
                    Text(
                        text = "%d:%02d".format(elapsed / 60, elapsed % 60),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }

                is AudioCardState.Idle -> {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.wear_playback_play),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    val seconds = note.durationMs / 1000
                    val durationFormatted = "%d:%02d".format(seconds / 60, seconds % 60)
                    Text(
                        text = stringResource(R.string.wear_day_detail_voice_note, durationFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }

                is AudioCardState.Error -> {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.wear_playback_retry_download),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        when (cardState) {
            is AudioCardState.Playing -> {
                LinearProgressIndicator(
                    progress = { cardState.progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }

            is AudioCardState.NoOutput,
            is AudioCardState.Preparing,
            is AudioCardState.Error,
            is AudioCardState.Idle,
            -> {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun formatDetailHeader(date: LocalDate): String {
    val monthName =
        date.month.name
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    return stringResource(R.string.wear_day_detail_header, monthName, date.day, date.year)
}
