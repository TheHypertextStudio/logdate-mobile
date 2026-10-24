package app.logdate.feature.postcards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.feature.postcards.model.CanvasBackground
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.InkTool
import app.logdate.feature.postcards.model.PostcardDocument
import app.logdate.feature.postcards.model.ShapeKind
import coil3.compose.AsyncImage
import logdate.client.feature.postcards.generated.resources.Res
import logdate.client.feature.postcards.generated.resources.caveat_regular
import logdate.client.feature.postcards.generated.resources.dancing_script_regular
import logdate.client.feature.postcards.generated.resources.patrick_hand_regular
import org.jetbrains.compose.resources.Font
import kotlin.uuid.Uuid

/**
 * Renders all elements of a [PostcardDocument] onto a Compose Canvas.
 *
 * Elements are drawn in [CanvasElement.zIndex] order. Each element is positioned,
 * rotated, and scaled according to its [CanvasElement.transform].
 *
 * @param document The Postcard document to render.
 * @param modifier Modifier for the renderer container.
 * @param selectedElementId The ID of the currently selected element, or null.
 * @param onElementTap Callback when an element is tapped (for selection).
 * @param onPhotoTap Callback when a photo element is tapped (for intertextuality navigation).
 */
@Composable
fun CanvasRenderer(
    document: PostcardDocument,
    modifier: Modifier = Modifier,
    selectedElementId: Uuid? = null,
    stickerUriMap: Map<Uuid, String> = emptyMap(),
    viewportOffsetX: Float = 0f,
    viewportOffsetY: Float = 0f,
    onElementTap: ((Uuid) -> Unit)? = null,
    onPhotoTap: ((CanvasElement.Photo) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Background
        CanvasBackgroundLayer(document.background)

        // Elements sorted by z-index
        val sortedElements = document.elements.sortedBy { it.zIndex }
        for (element in sortedElements) {
            val isSelected = element.id == selectedElementId
            ElementWrapper(
                element = element,
                isSelected = isSelected,
                onTap = { onElementTap?.invoke(element.id) },
            ) {
                when (element) {
                    is CanvasElement.Photo -> {
                        PhotoElementRenderer(
                            element,
                            viewportOffsetX,
                            viewportOffsetY,
                        )
                    }

                    is CanvasElement.Text -> {
                        TextElementRenderer(element)
                    }

                    is CanvasElement.Ink -> {
                        InkElementRenderer(element)
                    }

                    is CanvasElement.Shape -> {
                        ShapeElementRenderer(element)
                    }

                    is CanvasElement.Sticker -> {
                        StickerElementRenderer(element, stickerUriMap)
                    }
                }
            }
        }
    }
}

/**
 * Wraps a rendered element with tap detection and selection chrome.
 */
@Composable
private fun ElementWrapper(
    element: CanvasElement,
    isSelected: Boolean,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clickable(onClick = onTap),
    ) {
        content()
        if (isSelected) {
            SelectionChrome(element)
        }
    }
}

/**
 * Draws selection indicators: a dashed border with corner and edge resize handles.
 * Corner handles indicate pinch-to-resize; edge handles reinforce the affordance.
 */
@Composable
private fun SelectionChrome(element: CanvasElement) {
    val accentColor = MaterialTheme.colorScheme.primary
    val transform = element.transform
    val (widthDp, heightDp) = elementSizeDp(element)

    Box(
        modifier =
            Modifier
                .offset(x = transform.x.dp, y = transform.y.dp)
                .graphicsLayer {
                    rotationZ = transform.rotation
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                }.size(widthDp.dp, heightDp.dp)
                .drawBehind {
                    drawRect(
                        color = accentColor,
                        style =
                            Stroke(
                                width = 2.dp.toPx(),
                                pathEffect =
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(8.dp.toPx(), 4.dp.toPx()),
                                    ),
                            ),
                    )
                },
    ) {
        val cornerSize = 12.dp
        val edgeSize = 8.dp

        // Corner resize handles (filled accent circles)
        for (alignment in listOf(
            Alignment.TopStart,
            Alignment.TopEnd,
            Alignment.BottomStart,
            Alignment.BottomEnd,
        )) {
            Box(
                modifier =
                    Modifier
                        .align(alignment)
                        .offset(
                            x =
                                when (alignment) {
                                    Alignment.TopStart, Alignment.BottomStart -> -(cornerSize / 2)
                                    else -> cornerSize / 2
                                },
                            y =
                                when (alignment) {
                                    Alignment.TopStart, Alignment.TopEnd -> -(cornerSize / 2)
                                    else -> cornerSize / 2
                                },
                        ).size(cornerSize)
                        .drawBehind {
                            drawCircle(color = Color.White, style = Fill)
                            drawCircle(color = accentColor, style = Fill, radius = size.minDimension / 3f)
                            drawCircle(color = accentColor, style = Stroke(width = 2.dp.toPx()))
                        },
            )
        }

        // Edge midpoint handles (smaller accent squares)
        for (alignment in listOf(
            Alignment.TopCenter,
            Alignment.BottomCenter,
            Alignment.CenterStart,
            Alignment.CenterEnd,
        )) {
            Box(
                modifier =
                    Modifier
                        .align(alignment)
                        .offset(
                            x =
                                when (alignment) {
                                    Alignment.CenterStart -> -(edgeSize / 2)
                                    Alignment.CenterEnd -> edgeSize / 2
                                    else -> 0.dp
                                },
                            y =
                                when (alignment) {
                                    Alignment.TopCenter -> -(edgeSize / 2)
                                    Alignment.BottomCenter -> edgeSize / 2
                                    else -> 0.dp
                                },
                        ).size(edgeSize)
                        .background(Color.White, MaterialTheme.shapes.extraSmall)
                        .border(1.5.dp, accentColor, MaterialTheme.shapes.extraSmall),
            )
        }
    }
}

/**
 * Returns the rendered size (width, height) in dp for a given element.
 */
internal fun elementSizeDp(element: CanvasElement): Pair<Float, Float> =
    when (element) {
        is CanvasElement.Photo -> {
            PHOTO_RENDER_SIZE to PHOTO_RENDER_SIZE
        }

        is CanvasElement.Text -> {
            TEXT_APPROX_WIDTH to TEXT_APPROX_HEIGHT
        }

        is CanvasElement.Ink -> {
            if (element.points.size < MIN_STROKE_POINTS) {
                0f to 0f
            } else {
                val minX = element.points.minOf { it.x }
                val maxX = element.points.maxOf { it.x }
                val minY = element.points.minOf { it.y }
                val maxY = element.points.maxOf { it.y }
                (maxX - minX).coerceAtLeast(MIN_INK_BOUNDS) to
                    (maxY - minY).coerceAtLeast(MIN_INK_BOUNDS)
            }
        }

        is CanvasElement.Shape -> {
            element.width to element.height
        }

        is CanvasElement.Sticker -> {
            STICKER_RENDER_SIZE to STICKER_RENDER_SIZE
        }
    }

@Composable
private fun CanvasBackgroundLayer(background: CanvasBackground) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        when (background) {
            is CanvasBackground.SolidColor -> {
                drawRect(color = parseColor(background.value))
            }

            is CanvasBackground.Gradient -> {
                val colors = background.stops.map { parseColor(it.color) }
                if (colors.size >= 2) {
                    drawRect(
                        brush =
                            Brush.verticalGradient(colors),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoElementRenderer(
    element: CanvasElement.Photo,
    viewportOffsetX: Float = 0f,
    viewportOffsetY: Float = 0f,
) {
    val transform = element.transform
    val depth = element.parallaxDepth
    AsyncImage(
        model = element.mediaUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .offset(x = transform.x.dp, y = transform.y.dp)
                .graphicsLayer {
                    rotationZ = transform.rotation
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                    translationX = viewportOffsetX * depth
                    translationY = viewportOffsetY * depth
                }.size(PHOTO_RENDER_SIZE.dp, PHOTO_RENDER_SIZE.dp),
    )
}

@Composable
private fun TextElementRenderer(element: CanvasElement.Text) {
    val transform = element.transform
    Text(
        text = element.content,
        style =
            TextStyle(
                color = parseColor(element.color),
                fontSize = element.fontSize.sp,
                fontFamily = resolveFontFamily(element.fontFamily),
            ),
        modifier =
            Modifier
                .offset(x = transform.x.dp, y = transform.y.dp)
                .graphicsLayer {
                    rotationZ = transform.rotation
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                },
    )
}

@Composable
private fun InkElementRenderer(element: CanvasElement.Ink) {
    val transform = element.transform
    val points = element.points
    if (points.size < 2) return

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .offset(x = transform.x.dp, y = transform.y.dp)
                .graphicsLayer {
                    rotationZ = transform.rotation
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                },
    ) {
        val alpha =
            when (element.tool) {
                InkTool.HIGHLIGHTER -> HIGHLIGHTER_ALPHA
                else -> 1f
            }
        val strokeColor = parseColor(element.color).copy(alpha = alpha)

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val segmentWidth = element.strokeWidth * ((prev.pressure + curr.pressure) / 2f)
            drawLine(
                color = strokeColor,
                start = Offset(prev.x, prev.y),
                end = Offset(curr.x, curr.y),
                strokeWidth = segmentWidth.coerceAtLeast(MIN_VISIBLE_STROKE_WIDTH),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ShapeElementRenderer(element: CanvasElement.Shape) {
    val transform = element.transform
    Canvas(
        modifier =
            Modifier
                .offset(x = transform.x.dp, y = transform.y.dp)
                .size(element.width.dp, element.height.dp)
                .graphicsLayer {
                    rotationZ = transform.rotation
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                },
    ) {
        val strokeStyle = Stroke(width = element.strokeWidth)
        val strokeColor = parseColor(element.color)
        val fill = element.fillColor?.let { parseColor(it) }

        when (element.shapeKind) {
            ShapeKind.RECTANGLE -> {
                if (fill != null) {
                    drawRoundRect(color = fill, style = Fill, cornerRadius = CornerRadius(SHAPE_CORNER_RADIUS, SHAPE_CORNER_RADIUS))
                }
                drawRoundRect(
                    color = strokeColor,
                    style = strokeStyle,
                    cornerRadius = CornerRadius(SHAPE_CORNER_RADIUS, SHAPE_CORNER_RADIUS),
                )
            }

            ShapeKind.CIRCLE -> {
                val radius = minOf(size.width, size.height) / 2
                if (fill != null) {
                    drawCircle(color = fill, radius = radius, style = Fill)
                }
                drawCircle(color = strokeColor, radius = radius, style = strokeStyle)
            }

            ShapeKind.LINE -> {
                drawLine(
                    color = strokeColor,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                    strokeWidth = element.strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            ShapeKind.ARROW -> {
                drawLine(
                    color = strokeColor,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                    strokeWidth = element.strokeWidth,
                    cap = StrokeCap.Round,
                )
                // Arrowhead
                val arrowSize = element.strokeWidth * ARROW_HEAD_SCALE
                val endX = size.width
                val endY = size.height
                val path =
                    Path().apply {
                        moveTo(endX, endY)
                        lineTo(endX - arrowSize, endY - arrowSize / 2)
                        lineTo(endX - arrowSize / 2, endY - arrowSize)
                        close()
                    }
                drawPath(path = path, color = strokeColor, style = Fill)
            }
        }
    }
}

@Composable
private fun StickerElementRenderer(
    element: CanvasElement.Sticker,
    stickerUriMap: Map<Uuid, String>,
) {
    val transform = element.transform
    val imageUri = stickerUriMap[element.stickerRef]

    if (imageUri != null) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .offset(x = transform.x.dp, y = transform.y.dp)
                    .size(STICKER_RENDER_SIZE.dp)
                    .graphicsLayer {
                        rotationZ = transform.rotation
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                    },
        )
    } else {
        Box(
            modifier =
                Modifier
                    .offset(x = transform.x.dp, y = transform.y.dp)
                    .size(STICKER_RENDER_SIZE.dp)
                    .graphicsLayer {
                        rotationZ = transform.rotation
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                    },
        )
    }
}

/**
 * Parses a hex color string (e.g., "#FF6B6B") into a Compose [Color].
 */
internal fun parseColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return when (cleaned.length) {
        6 -> Color(("FF$cleaned").toLong(16))
        8 -> Color(cleaned.toLong(16))
        else -> Color.Black
    }
}

/**
 * Resolves a font family name from the document model to a Compose [FontFamily]
 * using bundled font resources.
 */
@Composable
internal fun resolveFontFamily(name: String): FontFamily =
    when (name) {
        "caveat" -> FontFamily(Font(Res.font.caveat_regular))
        "dancing-script" -> FontFamily(Font(Res.font.dancing_script_regular))
        "patrick-hand" -> FontFamily(Font(Res.font.patrick_hand_regular))
        else -> FontFamily.Default
    }

/** Render size for photo elements in canvas dp units. */
private const val PHOTO_RENDER_SIZE = 200f

/** Render size for sticker elements in canvas dp units. */
private const val STICKER_RENDER_SIZE = 80f

/** Approximate selection chrome bounds for text elements. */
private const val TEXT_APPROX_WIDTH = 150f
private const val TEXT_APPROX_HEIGHT = 40f

/** Minimum bounding box for very small ink strokes. */
private const val MIN_INK_BOUNDS = 20f

/** Minimum number of points for a valid ink stroke. */
private const val MIN_STROKE_POINTS = 2

/** Corner radius for rectangle shapes. */
private const val SHAPE_CORNER_RADIUS = 4f

/** Arrowhead size relative to stroke width. */
private const val ARROW_HEAD_SCALE = 4

/** Highlighter tool opacity. */
private const val HIGHLIGHTER_ALPHA = 0.4f

/** Prevents strokes from becoming invisible at zero pressure. */
private const val MIN_VISIBLE_STROKE_WIDTH = 0.5f
