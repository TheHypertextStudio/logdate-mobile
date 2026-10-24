package app.logdate.feature.postcards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import app.logdate.feature.postcards.model.ShapeKind
import kotlin.math.abs
import kotlin.math.min

/**
 * Data class representing a shape being drawn by the user.
 */
data class ShapeDraft(
    val kind: ShapeKind,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
) {
    val x: Float get() = min(startX, endX)
    val y: Float get() = min(startY, endY)
    val width: Float get() = abs(endX - startX)
    val height: Float get() = abs(endY - startY)
}

/**
 * Overlay that captures shape drawing gestures.
 *
 * When the shape tool is active, the user drags to define the bounds of the shape.
 * A preview is rendered during the drag, and [onShapeComplete] is called on release.
 *
 * @param shapeKind The type of shape to draw.
 * @param color The shape stroke color as a hex string.
 * @param strokeWidth The stroke width.
 * @param onShapeComplete Called when the user releases, with the shape draft.
 */
@Composable
fun ShapeCaptureOverlay(
    shapeKind: ShapeKind,
    color: String,
    strokeWidth: Float,
    onShapeComplete: (ShapeDraft) -> Unit,
) {
    var draft by remember { mutableStateOf<ShapeDraft?>(null) }
    val parsedColor = parseColor(color)

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(shapeKind, color, strokeWidth) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull() ?: continue
                            if (down.pressed) {
                                val start = down.position
                                draft =
                                    ShapeDraft(
                                        kind = shapeKind,
                                        startX = start.x,
                                        startY = start.y,
                                        endX = start.x,
                                        endY = start.y,
                                    )
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        draft?.let { d ->
                                            if (d.width > MIN_SHAPE_DIMENSION || d.height > MIN_SHAPE_DIMENSION) {
                                                onShapeComplete(d)
                                            }
                                        }
                                        draft = null
                                        break
                                    }
                                    draft =
                                        draft?.copy(
                                            endX = change.position.x,
                                            endY = change.position.y,
                                        )
                                    change.consume()
                                }
                            }
                        }
                    }
                },
    ) {
        // Render shape preview
        val d = draft ?: return@Canvas
        val topLeft = Offset(d.x, d.y)
        val shapeSize = Size(d.width, d.height)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        when (d.kind) {
            ShapeKind.RECTANGLE -> {
                drawRect(
                    color = parsedColor,
                    topLeft = topLeft,
                    size = shapeSize,
                    style = stroke,
                )
            }

            ShapeKind.CIRCLE -> {
                val radius = min(d.width, d.height) / 2
                val center = Offset(d.x + d.width / 2, d.y + d.height / 2)
                drawCircle(
                    color = parsedColor,
                    radius = radius,
                    center = center,
                    style = stroke,
                )
            }

            ShapeKind.LINE -> {
                drawLine(
                    color = parsedColor,
                    start = Offset(d.startX, d.startY),
                    end = Offset(d.endX, d.endY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            ShapeKind.ARROW -> {
                drawLine(
                    color = parsedColor,
                    start = Offset(d.startX, d.startY),
                    end = Offset(d.endX, d.endY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                val arrowSize = strokeWidth * ARROW_HEAD_SCALE
                val path =
                    Path().apply {
                        moveTo(d.endX, d.endY)
                        lineTo(d.endX - arrowSize, d.endY - arrowSize / 2)
                        lineTo(d.endX - arrowSize / 2, d.endY - arrowSize)
                        close()
                    }
                drawPath(path = path, color = parsedColor, style = Fill)
            }
        }
    }
}

/** Minimum size for a shape to be considered intentional (not an accidental tap). */
private const val MIN_SHAPE_DIMENSION = 5f

/** Arrowhead size relative to stroke width. */
private const val ARROW_HEAD_SCALE = 4
