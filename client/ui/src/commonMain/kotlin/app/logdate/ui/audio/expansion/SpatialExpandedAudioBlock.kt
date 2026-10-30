package app.logdate.ui.audio.expansion

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.ui.audio.AudioTranscriptStatus
import app.logdate.ui.audio.formatAudioDuration
import app.logdate.ui.audio.model.AudioPalette
import app.logdate.ui.audio.model.AudioSegment
import app.logdate.ui.audio.waveform.BezierAudioWaveform
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.playback_pause
import logdate.client.ui.generated.resources.playback_play
import logdate.client.ui.generated.resources.search_type_label_voice_note
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * First expansion state for audio entries.
 *
 * Displays the full waveform with time-of-day palette colors,
 * basic playback controls, and contextual information.
 *
 * Playback controls and the output selector handle their own taps.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SpatialExpandedAudioBlock(
    amplitudes: List<Float>,
    progress: Float,
    isPlaying: Boolean,
    palette: AudioPalette,
    durationMs: Long,
    createdAt: Instant,
    modifier: Modifier = Modifier,
    segments: List<AudioSegment> = emptyList(),
    transcript: String? = null,
    isTranscribing: Boolean = false,
    onPlayPause: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    transcriptionStatus: TranscriptionStatus? = null,
    onRetryTranscription: (() -> Unit)? = null,
    playbackActions: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.padding(Spacing.md)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // The words come first: expanding a recording is how you read all of it.
                Text(
                    text = transcript ?: stringResource(Res.string.search_type_label_voice_note),
                    style = MaterialTheme.typography.bodyLarge,
                )
                AudioTranscriptStatus(transcriptionStatus, transcript != null, isTranscribing, onRetryTranscription)

                // Waveform
                BezierAudioWaveform(
                    amplitudes = amplitudes,
                    progress = progress,
                    palette = palette,
                    segments = segments,
                    durationMs = durationMs,
                    onSeek = onSeek,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clip(RoundedCornerShape(8.dp)),
                )

                playbackActions()

                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Play/pause button
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            painter = if (isPlaying) PlatformIcons.pause() else PlatformIcons.play(),
                            contentDescription =
                                if (isPlaying) {
                                    stringResource(Res.string.playback_pause)
                                } else {
                                    stringResource(Res.string.playback_play)
                                },
                            tint = Color(palette.accentColor),
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    // Duration and time info
                    Column(
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = formatAudioDuration(durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatTime(createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour =
        if (local.hour == 0) {
            12
        } else if (local.hour > 12) {
            local.hour - 12
        } else {
            local.hour
        }
    val amPm = if (local.hour < 12) "AM" else "PM"
    return "$hour:${local.minute.toString().padStart(2, '0')} $amPm"
}
