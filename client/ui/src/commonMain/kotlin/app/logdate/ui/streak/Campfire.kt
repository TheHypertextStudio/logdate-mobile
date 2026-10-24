@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.streak

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.logdate.ui.platform.rememberSystemReduceMotion
import kotlin.math.PI
import kotlin.math.sin

/**
 * Whether campfires animate. Screenshot scenes turn this off so the flicker stops long enough to
 * capture a stable frame.
 */
val LocalCampfireAnimationEnabled = staticCompositionLocalOf { true }

/**
 * A sticker-style campfire that shows where the user's journaling streak stands.
 *
 * Every shape is a flat fill with a chunky dark outline and a white die-cut border, like a sticker
 * on a page. A burning fire sways above two crossed logs and grows taller with [size]. Embers sit on
 * the logs with a spark rising off them. A fire that went out leaves grey logs and a curl of smoke,
 * and an unlit fire is just the logs. Motion stops when the system asks for reduced motion.
 *
 * @param waitingForToday Draws a lower flame for a fire that nothing has been added to today.
 * @param contentDescription Spoken description of the fire, or `null` when a parent describes it.
 */
@Composable
fun Campfire(
    phase: CampfirePhase,
    size: CampfireSize?,
    modifier: Modifier = Modifier,
    waitingForToday: Boolean = false,
    contentDescription: String? = null,
) {
    val reduceMotion by rememberSystemReduceMotion()
    val animationEnabled = LocalCampfireAnimationEnabled.current && !LocalInspectionMode.current
    val progress = rememberCampfireProgress(animate = animationEnabled && !reduceMotion && phase != CampfirePhase.UNLIT)
    val semanticsModifier =
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        drawCampfire(
            phase = phase,
            fireSize = size,
            waitingForToday = waitingForToday,
            progress = progress.value,
        )
    }
}

/** A 0..1 loop that drives the motion, or a constant 0 when the fire should not move. */
@Composable
private fun rememberCampfireProgress(animate: Boolean): State<Float> {
    if (!animate) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "campfire")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(FLICKER_PERIOD_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "campfireFlicker",
    )
}

/**
 * Maps the 64-unit design grid the campfire is drawn on onto the canvas, centered and scaled to fit.
 */
private class StickerGrid(
    canvasWidth: Float,
    canvasHeight: Float,
) {
    val scale = minOf(canvasWidth, canvasHeight) / GRID_SIZE
    private val originX = (canvasWidth - GRID_SIZE * scale) / 2f
    private val originY = (canvasHeight - GRID_SIZE * scale) / 2f

    fun x(gridX: Float) = originX + gridX * scale

    fun y(gridY: Float) = originY + gridY * scale

    fun point(
        gridX: Float,
        gridY: Float,
    ) = Offset(x(gridX), y(gridY))
}

private data class StickerShape(
    val path: Path,
    val fill: Color,
    val outlineWidth: Float,
)

private fun DrawScope.drawCampfire(
    phase: CampfirePhase,
    fireSize: CampfireSize?,
    waitingForToday: Boolean,
    progress: Float,
) {
    val grid = StickerGrid(size.width, size.height)
    val outline = OUTLINE_WIDTH * grid.scale
    val angle = progress * 2f * PI.toFloat()
    val charred = phase == CampfirePhase.OUT

    val shapes = mutableListOf<StickerShape>()
    shapes += logShape(grid, degrees = 14f, fill = if (charred) CharredLogColor else LogColor, outline = outline)
    shapes += logShape(grid, degrees = -14f, fill = if (charred) CharredLogShadeColor else LogShadeColor, outline = outline)

    when (phase) {
        CampfirePhase.BURNING -> {
            val scale = flameScale(fireSize) * if (waitingForToday) WAITING_FLAME_SCALE else 1f
            val sway = sin(angle) * 1.6f
            val stretch = 1f + 0.04f * sin(2f * angle + 1f)
            shapes += StickerShape(flamePath(grid, OuterFlame, scale, sway, stretch), OuterFlameColor, outline)
            shapes += StickerShape(flamePath(grid, InnerFlame, scale, sway * 0.6f, stretch), InnerFlameColor, outline * 0.8f)
        }

        CampfirePhase.EMBERS -> {
            EMBERS.forEach { ember ->
                shapes += StickerShape(circlePath(grid, ember.x, ember.y, ember.radius), ember.color, outline * 0.8f)
            }
        }

        CampfirePhase.OUT, CampfirePhase.UNLIT -> {
            Unit
        }
    }

    val backing = Stroke(width = outline * DIE_CUT_SCALE, join = StrokeJoin.Round, cap = StrokeCap.Round)
    shapes.forEach { drawPath(it.path, DieCutColor, style = backing) }
    if (phase == CampfirePhase.OUT) {
        drawSmoke(grid, angle, backingWidth = outline * SMOKE_DIE_CUT_SCALE)
    }
    shapes.forEach { shape ->
        drawPath(shape.path, shape.fill)
        drawPath(
            shape.path,
            OutlineColor,
            style = Stroke(width = shape.outlineWidth, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
    }
    if (phase == CampfirePhase.EMBERS) {
        drawRisingSpark(grid, progress, outline)
    }
}

private fun flameScale(fireSize: CampfireSize?): Float =
    when (fireSize) {
        null, CampfireSize.SPARK -> 0.62f
        CampfireSize.SMALL -> 0.76f
        CampfireSize.CAMPFIRE -> 0.88f
        CampfireSize.BONFIRE -> 0.98f
        CampfireSize.BEACON -> 1.08f
    }

private fun logShape(
    grid: StickerGrid,
    degrees: Float,
    fill: Color,
    outline: Float,
): StickerShape {
    val rect = Rect(grid.x(12f), grid.y(47f), grid.x(52f), grid.y(56f))
    val radius = 4.5f * grid.scale
    val pivot = grid.point(32f, 52f)
    val path =
        Path().apply {
            addRoundRect(RoundRect(rect, CornerRadius(radius)))
            transform(
                Matrix().apply {
                    translate(pivot.x, pivot.y)
                    rotateZ(degrees)
                    translate(-pivot.x, -pivot.y)
                },
            )
        }
    return StickerShape(path, fill, outline)
}

private fun circlePath(
    grid: StickerGrid,
    x: Float,
    y: Float,
    radius: Float,
): Path =
    Path().apply {
        addOval(Rect(center = grid.point(x, y), radius = radius * grid.scale))
    }

/**
 * A teardrop flame on the design grid: a start point followed by cubic segments of three points
 * each, tracing a pointed tip, curved shoulders, and a round base that rests on the logs.
 */
private class FlameOutline(
    val points: List<Pair<Float, Float>>,
)

private val OuterFlame =
    FlameOutline(
        listOf(
            32f to 7f,
            41f to 18f,
            47f to 27f,
            47f to 35f,
            47f to 43.28f,
            40.28f to 50f,
            32f to 50f,
            23.72f to 50f,
            17f to 43.28f,
            17f to 35f,
            17f to 27f,
            23f to 18f,
            32f to 7f,
        ),
    )

private val InnerFlame =
    FlameOutline(
        listOf(
            32f to 24f,
            36f to 30f,
            39f to 33f,
            39f to 37f,
            39f to 40.87f,
            35.87f to 44f,
            32f to 44f,
            28.13f to 44f,
            25f to 40.87f,
            25f to 37f,
            25f to 33f,
            28f to 30f,
            32f to 24f,
        ),
    )

/**
 * Places [outline] on the canvas, scaled about the flame's base by [scale], leaned by [sway] grid
 * units at the tip, and stretched vertically by [stretch].
 */
private fun flamePath(
    grid: StickerGrid,
    outline: FlameOutline,
    scale: Float,
    sway: Float,
    stretch: Float,
): Path {
    fun place(point: Pair<Float, Float>): Offset {
        val (x, y) = point
        val rise = (FLAME_BASE_Y - y) / FLAME_HEIGHT
        return grid.point(
            FLAME_BASE_X + (x - FLAME_BASE_X) * scale + sway * rise,
            FLAME_BASE_Y - (FLAME_BASE_Y - y) * scale * stretch,
        )
    }

    val points = outline.points.map(::place)
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        for (index in 1 until points.size step 3) {
            val (control1, control2, end) = Triple(points[index], points[index + 1], points[index + 2])
            cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
        }
        close()
    }
}

private fun DrawScope.drawSmoke(
    grid: StickerGrid,
    angle: Float,
    backingWidth: Float,
) {
    val drift = sin(angle) * 1.5f
    val path =
        Path().apply {
            moveTo(grid.x(32f), grid.y(41f))
            cubicTo(grid.x(28f + drift), grid.y(35f), grid.x(36f + drift), grid.y(31f), grid.x(32f + drift), grid.y(25f))
            cubicTo(grid.x(28f - drift), grid.y(19f), grid.x(34f - drift), grid.y(15f), grid.x(32f), grid.y(11f))
        }
    val width = SMOKE_WIDTH * grid.scale
    drawPath(path, DieCutColor, style = Stroke(width = width + backingWidth, cap = StrokeCap.Round))
    drawPath(path, SmokeColor, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.drawRisingSpark(
    grid: StickerGrid,
    progress: Float,
    outline: Float,
) {
    val rise = (progress + SPARK_START) % 1f
    val alpha = 1f - rise
    val center = grid.point(36f + sin(rise * 6f) * 2f, 36f - rise * 18f)
    val radius = 2.2f * grid.scale
    drawCircle(OuterFlameColor.copy(alpha = alpha), radius = radius, center = center)
    drawCircle(OutlineColor.copy(alpha = alpha), radius = radius, center = center, style = Stroke(width = outline * 0.7f))
}

private data class Ember(
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Color,
)

private const val GRID_SIZE = 64f
private const val FLAME_BASE_X = 32f
private const val FLAME_BASE_Y = 50f
private const val FLAME_HEIGHT = 43f
private const val OUTLINE_WIDTH = 3f
private const val DIE_CUT_SCALE = 2.6f
private const val SMOKE_DIE_CUT_SCALE = 1.2f
private const val SMOKE_WIDTH = 3.5f
private const val SPARK_START = 0.35f
private const val FLICKER_PERIOD_MILLIS = 1_600
private const val WAITING_FLAME_SCALE = 0.82f

private val OutlineColor = Color(0xFF3B1F0E)
private val DieCutColor = Color(0xFFFFFFFF)
private val LogColor = Color(0xFFC27A45)
private val LogShadeColor = Color(0xFFB06A38)
private val CharredLogColor = Color(0xFFA8A29C)
private val CharredLogShadeColor = Color(0xFF8F8983)
private val OuterFlameColor = Color(0xFFFF8A3D)
private val InnerFlameColor = Color(0xFFFFD166)
private val SmokeColor = Color(0xFFB4B2A9)

private val EMBERS =
    listOf(
        Ember(x = 25f, y = 44f, radius = 4.5f, color = OuterFlameColor),
        Ember(x = 40f, y = 45f, radius = 4f, color = OuterFlameColor),
        Ember(x = 33f, y = 42f, radius = 5f, color = InnerFlameColor),
    )
