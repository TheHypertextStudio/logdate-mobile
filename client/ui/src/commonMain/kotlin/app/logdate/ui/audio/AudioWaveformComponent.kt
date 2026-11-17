package app.logdate.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Default minimum height for the waveform visualization
private val DEFAULT_WAVEFORM_MIN_HEIGHT = 80.dp
private val DEFAULT_WAVEFORM_STROKE_WIDTH = 2.dp

/**
 * A component that renders a waveform visualization of audio data.
 * Always scales to fit its parent container without getting cut off.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AudioWaveformComponent(
    audioLevels: List<Float>,
    modifier: Modifier = Modifier,
    waveformColor: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = DEFAULT_WAVEFORM_STROKE_WIDTH,
    maxBars: Int = 50,
    minHeight: Dp = DEFAULT_WAVEFORM_MIN_HEIGHT,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }

    // Always use fillMaxSize to ensure the waveform scales with its container
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .padding(horizontal = 4.dp) // Reduced padding to avoid edges getting cut off
                // Apply minimum height constraint
                .then(Modifier.heightIn(min = minHeight)),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize(), // Fill the entire container
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val centerY = canvasHeight / 2f

            if (audioLevels.isNotEmpty()) {
                // Fixed bar width based on maxBars so bars don't spread when count < maxBars
                val barWidth = canvasWidth / maxBars.coerceAtLeast(1)

                // Ensure bars don't exceed 80% of available height
                val maxBarHeight = canvasHeight * 0.8f

                audioLevels.take(maxBars).forEachIndexed { index, level ->
                    val x = index * barWidth + barWidth / 2f
                    // Scale the level to the available height
                    val barHeight = level * maxBarHeight

                    drawLine(
                        color = waveformColor,
                        start = Offset(x, centerY - barHeight / 2f),
                        end = Offset(x, centerY + barHeight / 2f),
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                // Draw a flat line when no audio
                drawLine(
                    color = waveformColor.copy(alpha = 0.3f),
                    start = Offset(0f, centerY),
                    end = Offset(canvasWidth, centerY),
                    strokeWidth = strokeWidthPx / 2f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * State class for managing audio waveform data
 */
data class AudioWaveformState(
    val levels: List<Float> = emptyList(),
    val isRecording: Boolean = false,
    val maxLevel: Float = 1.0f,
) {
    /**
     * Clears all waveform data
     */
    fun clear(): AudioWaveformState = copy(levels = emptyList())
}

/**
 * Remember function for creating and managing AudioWaveformState
 */
@Composable
fun rememberAudioWaveformState(
    initialLevels: List<Float> = emptyList(),
    isRecording: Boolean = false,
): AudioWaveformState =
    remember {
        AudioWaveformState(
            levels = initialLevels,
            isRecording = isRecording,
        )
    }
