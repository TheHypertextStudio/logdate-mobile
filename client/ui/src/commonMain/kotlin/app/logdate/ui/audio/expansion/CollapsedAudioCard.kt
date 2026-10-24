@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.audio.expansion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.ui.audio.formatAudioDuration
import app.logdate.ui.audio.model.AudioPalette
import app.logdate.ui.audio.waveform.BezierAudioWaveform
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.audio_transcribing
import logdate.client.ui.generated.resources.search_type_label_voice_note
import org.jetbrains.compose.resources.stringResource

private val COLLAPSED_WAVEFORM_HEIGHT = 40.dp

/**
 * [AudioExpansionState.COLLAPSED] — the compact state the expansion model always described as
 * the timeline's, but which had no renderer of its own until now.
 *
 * The transcript is the content. A recording the user cannot read is one they must play to
 * know anything about, so the words lead and the waveform sits underneath as the transport:
 * it is the play target and the progress readout at once, drawn from the recording's own
 * amplitudes rather than a decorative stand-in.
 *
 * The card body is deliberately not clickable. It sits inside a day that opens on tap, and a
 * card that swallowed that tap would leave a day whose only content is a recording with no way
 * to open it at all. Expansion is driven by playback instead, which is what
 * [AudioExpansionController]'s `expandOnPlayback` is for.
 *
 * @param transcriptExcerpt Sentence-bounded preview, or null while transcription is pending.
 * @param onPlayPause Toggles playback for this recording.
 */
@Composable
fun CollapsedAudioCard(
    amplitudes: List<Float>,
    progress: Float,
    isPlaying: Boolean,
    palette: AudioPalette,
    durationMs: Long,
    modifier: Modifier = Modifier,
    transcriptExcerpt: String? = null,
    isTranscribing: Boolean = false,
    onPlayPause: () -> Unit = {},
    playbackActions: @Composable () -> Unit = {},
) {
    val accentColor = remember(palette) { Color(palette.accentColor) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(Spacing.md),
        ) {
            when {
                transcriptExcerpt != null -> {
                    Text(
                        text = transcriptExcerpt,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                else -> {
                    Text(
                        text = stringResource(Res.string.search_type_label_voice_note),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (transcriptExcerpt == null && isTranscribing) {
                Text(
                    text = stringResource(Res.string.audio_transcribing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            playbackActions()

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f))
                            .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter = if (isPlaying) PlatformIcons.pause() else PlatformIcons.play(),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(COLLAPSED_WAVEFORM_HEIGHT),
                ) {
                    BezierAudioWaveform(
                        amplitudes = amplitudes,
                        progress = progress,
                        palette = palette,
                    )
                }

                Text(
                    text = formatAudioDuration(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
