@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.firstlaunch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * One piece of paper lying on a table, seen from above and lit from directly overhead.
 *
 * This is the object every first-launch and LogDate Cloud illustration is made of, so they read as
 * one family. A piece is flat: it only shifts, turns a few degrees and casts a soft shadow straight
 * down. Positions are in dp from the centre of the box it is placed in.
 *
 * @param lift How far the piece sits above the table, from 0 to 1. A higher piece casts a wider,
 *   softer shadow; every piece keeps a thin dark line where it meets the surface.
 * @param look How far, in dp, turning the device has carried the piece. Its shadow leans the other
 *   way.
 *
 * [modifier] is applied to the piece where it lies, which is where a gesture that picks it up belongs.
 */
@Composable
fun BoxScope.PaperPiece(
    x: Float,
    y: Float,
    width: Dp,
    height: Dp,
    shape: Shape,
    color: Color,
    rotation: Float = 0f,
    scale: Float = 1f,
    lift: Float = 0.6f,
    look: Offset = Offset.Zero,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shade = paperShade()
    Box(
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(width, height)
                .graphicsLayer {
                    translationX = (x + look.x).dp.toPx()
                    translationY = (y + look.y).dp.toPx()
                    rotationZ = rotation
                    scaleX = scale
                    scaleY = scale
                }
                // After the layer, so a gesture on the piece follows it wherever it has moved.
                .then(modifier)
                .dropShadow(
                    shape,
                    Shadow(
                        radius = (10f + 22f * lift).dp,
                        color = shade,
                        offset = DpOffset((-look.x * 0.5f).dp, (4f + 12f * lift - look.y * 0.5f).dp),
                        alpha = 0.14f,
                    ),
                ).dropShadow(shape, Shadow(radius = 2.dp, color = shade, offset = DpOffset(0.dp, 1.dp), alpha = 0.10f))
                .clip(shape)
                .background(color),
        content = content,
    )
}

/**
 * A piece of paper that moves: one that is dealt, picked up, thrown or filed.
 *
 * It looks the same as the piece above. What differs is when it is told where it is. [place] and
 * [lift] are read while the piece is being drawn, so a piece in motion is redrawn every frame
 * without being recomposed, which is what keeps a table of twenty of them smooth.
 *
 * @param place Positions, turns and scales the piece.
 * @param lift How far the piece sits above the table, from 0 to 1 and a little over while it is held.
 * @param modifier Applied to the piece before it is placed: its alignment and drawing order.
 * @param gesture Applied to the piece where it lies, so a touch lands on the piece as it is drawn.
 */
@Composable
fun PaperPiece(
    width: Dp,
    height: Dp,
    shape: Shape,
    color: Color,
    place: GraphicsLayerScope.() -> Unit,
    lift: () -> Float,
    modifier: Modifier = Modifier,
    gesture: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shade = paperShade()
    Box(
        modifier =
            modifier
                .size(width, height)
                .graphicsLayer(place)
                .then(gesture)
                .dropShadow(shape) {
                    val height = lift()
                    radius = (10f + 22f * height).dp.toPx()
                    this.color = shade
                    offset = Offset(0f, (4f + 12f * height).dp.toPx())
                    alpha = 0.14f
                }.dropShadow(shape) {
                    radius = 2.dp.toPx()
                    this.color = shade
                    offset = Offset(0f, 1.dp.toPx())
                    alpha = 0.10f
                }.clip(shape)
                .background(color),
        content = content,
    )
}

/** The colour of a piece's shadow: black in the dark, and a warm brown on light paper. */
@Composable
private fun paperShade(): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.Black else Color(0xFF3B2530)
