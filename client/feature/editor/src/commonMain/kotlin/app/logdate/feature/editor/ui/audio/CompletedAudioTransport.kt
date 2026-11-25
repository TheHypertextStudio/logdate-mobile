@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.formatMediaDuration
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.pause
import logdate.client.feature.editor.generated.resources.play
import logdate.client.feature.editor.generated.resources.playback_position
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CompletedAudioTransport(
    durationMs: Long,
    isPlaying: Boolean,
    progress: Float,
    amplitudes: List<Float>,
    onPlayPauseClicked: () -> Unit,
    onProgressChanged: ((Float) -> Unit)?,
    playbackModifier: Modifier,
    waveformModifier: Modifier,
    compact: Boolean = false,
    trailingActionInset: Dp = 0.dp,
) {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val shape = IconButtonDefaults.mediumSquareShape
    val containerSize = IconButtonDefaults.mediumContainerSize()
    val timingStyle = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr)
    val controlSize = containerSize.height
    val elapsed = formatMediaDuration((durationMs * safeProgress).toLong(), true)
    val total = formatMediaDuration(durationMs, true)
    val playbackPositionDescription = stringResource(Res.string.playback_position)
    val playPauseDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(end = trailingActionInset),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!compact) Text("$elapsed /", style = timingStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        total,
                        style = timingStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("audio_block_duration"),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(end = trailingActionInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(
                onClick = onPlayPauseClicked,
                modifier =
                    Modifier
                        .size(containerSize)
                        .then(playbackModifier)
                        .semantics { contentDescription = playPauseDescription },
                shapes = IconButtonDefaults.shapes(shape = shape, pressedShape = IconButtonDefaults.mediumPressedShape),
            ) {
                MorphingPlayPauseIcon(
                    isPlaying = isPlaying,
                    size = IconButtonDefaults.mediumIconSize,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(controlSize)
                    .then(waveformModifier)
                    .testTag("completed_audio_waveform_surface")
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                if (onProgressChanged != null) {
                    Slider(
                        value = safeProgress,
                        onValueChange = onProgressChanged,
                        enabled = durationMs > 0,
                        modifier =
                            Modifier.fillMaxSize().testTag("completed_audio_waveform").semantics {
                                contentDescription = playbackPositionDescription
                                stateDescription = "$elapsed / $total"
                            },
                        thumb = {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(controlSize - 8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        },
                        track = { state -> CompletedWaveform(amplitudes, state.value, Modifier.fillMaxWidth().height(controlSize)) },
                    )
                } else {
                    CompletedWaveform(amplitudes, safeProgress, Modifier.fillMaxSize().testTag("completed_audio_waveform"))
                }
            }
        }
    }
}

@Composable
private fun CompletedWaveform(
    amplitudes: List<Float>,
    progress: Float,
    modifier: Modifier,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .45f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
            val barCount = (size.width / 6.dp.toPx()).toInt().coerceIn(1, 80)
            val spacing = size.width / barCount
            val stroke = 3.dp.toPx()
            repeat(barCount) { index ->
                val start = index * amplitudes.size / barCount
                val end = ((index + 1) * amplitudes.size / barCount).coerceAtLeast(start + 1).coerceAtMost(amplitudes.size)
                var amplitude = 0f
                for (sample in start until end) {
                    val level = amplitudes[sample]
                    if (level.isFinite()) amplitude = maxOf(amplitude, level.coerceIn(0f, 1f))
                }
                val barHeight = (amplitude * size.height).coerceAtLeast(stroke)
                val fraction = (index + .5f) / barCount
                val x = if (rtl) size.width - (index + .5f) * spacing else (index + .5f) * spacing
                drawLine(
                    color = if (fraction <= progress) playedColor else unplayedColor,
                    start = Offset(x, (size.height - barHeight) / 2),
                    end = Offset(x, (size.height + barHeight) / 2),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
