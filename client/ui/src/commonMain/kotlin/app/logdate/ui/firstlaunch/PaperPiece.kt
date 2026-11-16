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
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shade = if (colors.surface.luminance() < 0.5f) Color.Black else Color(0xFF3B2530)
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
                }.dropShadow(
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
