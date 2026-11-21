@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.logdate.ui.platform.rememberSystemReduceMotion
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where the first word starts so the selected word sits in the middle of the row.
 *
 * Words before or after it may run past either edge; the row fades them out there.
 */
internal fun captureWordRowStart(
    widths: List<Int>,
    selected: Int,
    gap: Int,
    containerWidth: Int,
): Int {
    val before = widths.take(selected).sum() + gap * selected
    return containerWidth / 2 - before - widths[selected] / 2
}

/**
 * The creation dock as it reads in Camera mode: the same modes, as words under the shutter.
 *
 * Tapping a word switches modes; swipes come from the dock around it. The selected word stays in
 * the middle, so the camera reads as one mode of the entry rather than a separate screen.
 */
@Composable
internal fun StoriesCaptureWordRow(
    mode: StoryCaptureMode,
    onMode: (StoryCaptureMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberSystemReduceMotion().value
    val style = MaterialTheme.typography.labelLarge
    // The row slides by animating which word is in the middle, so switching modes glides between them.
    val centered by animateFloatAsState(
        mode.ordinal.toFloat(),
        if (reduceMotion) snap() else spring(dampingRatio = 0.8f, stiffness = 450f),
        label = "captureWordRowCenter",
    )
    Layout(
        content = {
            StoryCaptureMode.entries.forEach { item ->
                val active = item == mode
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .selectable(selected = active, enabled = enabled, role = Role.RadioButton) { onMode(item) }
                        .testTag("entry_capture_word_${item.name}")
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        captureChoiceLabel(item),
                        style = style,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        },
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("entry_capture_word_row")
                .selectableGroup()
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Words that run past the edges fade out instead of being cut off.
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.18f to Color.Black,
                            0.82f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(minHeight = constraints.minHeight)) }
        val height = placeables.maxOf { it.height }.coerceAtLeast(constraints.minHeight)
        val gap = 4.dp.roundToPx()
        val widths = placeables.map { it.width }
        val from = floor(centered).toInt().coerceIn(widths.indices)
        val to = (from + 1).coerceAtMost(widths.lastIndex)
        val fromStart = captureWordRowStart(widths, from, gap, constraints.maxWidth)
        val toStart = captureWordRowStart(widths, to, gap, constraints.maxWidth)
        val start = fromStart + ((toStart - fromStart) * (centered - from)).roundToInt()
        layout(constraints.maxWidth, height) {
            var x = start
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}
