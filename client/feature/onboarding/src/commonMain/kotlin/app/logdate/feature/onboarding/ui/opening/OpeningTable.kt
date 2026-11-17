package app.logdate.feature.onboarding.ui.opening

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.logdate.ui.platform.LogDateHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How one piece is moving. Everything here is read while the piece is drawn, so changing any of it
 * redraws the piece without recomposing the scene.
 */
@Stable
internal class PieceMotion(
    arrived: Boolean,
    tidied: Boolean,
) {
    /** From off the table (0) to lying on it (1). */
    val arrived = Animatable(if (arrived) 1f else 0f)

    /** From lying loose (0) to tidied (1): on today's page, or off the table where it lay in the page's way. */
    val tidy = Animatable(if (tidied) 1f else 0f)

    /** How far the person has moved it from where it belongs, in dp. */
    val moved = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Whether a finger is on it, from 0 to 1. */
    val held = Animatable(0f)

    /** How far it swings, in degrees, from being pulled sideways. */
    val lean = Animatable(0f)

    /** The latest touch raises a piece above the others: 0 until it has been touched. */
    var raised by mutableIntStateOf(0)

    // The turn and size the piece was last drawn at, which a drag needs to follow the finger.
    var drawnRotation = 0f
    var drawnScale = 1f
}

/** Where a piece is drawn this frame. [alpha] is below 1 only while a date is coming down. */
internal class DrawnPiece(
    val centre: Offset,
    val rotation: Float,
    val scale: Float,
    val alpha: Float,
)

/**
 * The table the pieces lie on: what the pieces share, and what happens when one is touched.
 *
 * A piece that is picked up comes off the table, follows the finger and swings from where it is
 * held. Let go, it keeps the speed it was thrown with, slides to a stop and stays there, except
 * that it never comes to rest over the words or the actions, or mostly off the screen. Once the
 * table is tidied a piece goes back to where it belongs instead.
 */
@Stable
internal class OpeningTable(
    private val scope: CoroutineScope,
    private val haptics: LogDateHaptics,
) {
    var arrangement: OpeningArrangement? = null

    /** The jolt the table takes when today's date lands on it: 1 at the moment it lands, settling to 0. */
    val jolt = Animatable(0f)

    private var touches by mutableIntStateOf(0)

    fun dateLanded() {
        haptics.momentMarked()
        scope.launch {
            jolt.snapTo(1f)
            jolt.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 210f))
        }
    }

    fun pickUp(motion: PieceMotion) {
        haptics.detentTick()
        touches += 1
        motion.raised = touches
        scope.launch { motion.moved.stop() }
        scope.launch { motion.held.animateTo(1f, spring(stiffness = Spring.StiffnessMedium)) }
    }

    fun drag(
        motion: PieceMotion,
        amount: Offset,
    ) {
        scope.launch { motion.moved.snapTo(motion.moved.value + amount) }
        // Each movement kicks the swing, and the spring is always pulling it back upright.
        scope.launch {
            motion.lean.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 140f),
                initialVelocity = (motion.lean.velocity + amount.x * LEAN_KICK).coerceIn(-LEAN_FASTEST, LEAN_FASTEST),
            )
        }
    }

    fun letGo(
        placed: PlacedPiece,
        motion: PieceMotion,
        velocity: Offset,
        tidied: Boolean,
    ) {
        haptics.detentTick()
        scope.launch { motion.held.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        scope.launch {
            if (tidied) {
                motion.moved.animateTo(Offset.Zero, spring(dampingRatio = 0.55f, stiffness = 170f), velocity)
                return@launch
            }
            val thrown = motion.moved.value + velocity * THROW_SECONDS
            motion.moved.animateTo(restingPlace(placed, thrown), spring(dampingRatio = 0.78f, stiffness = 85f), velocity)
        }
    }

    /**
     * Where a piece thrown to [moved] comes to rest: as near there as it can be with most of
     * itself on the screen and none of itself over the words or the actions.
     */
    private fun restingPlace(
        placed: PlacedPiece,
        moved: Offset,
    ): Offset {
        val scene = arrangement ?: return moved
        val lying = placed.loose
        val width = placed.piece.width * lying.scale
        val height = placed.piece.height * lying.scale
        var pose = lying.copy(center = lying.center + moved)
        repeat(3) {
            val onScreen =
                Offset(
                    pose.center.x.coerceIn(width * HIDDEN_MOST, scene.canvas.width - width * HIDDEN_MOST),
                    pose.center.y.coerceIn(height * HIDDEN_MOST, scene.canvas.height - height * HIDDEN_MOST),
                )
            pose = pose.copy(center = onScreen).clearOf(placed.piece, scene.keepClear)
        }
        return pose.center - lying.center
    }
}

/**
 * Where a piece waits before it is dealt, and where it goes when the table is tidied and it lies
 * in the way of today's page: just off the screen, straight out from the middle through where it
 * lies, spun a little. Today's date is the exception. It comes down from above its own place, so
 * it lands where it is, larger and turned until it does.
 */
internal fun offTable(
    placed: PlacedPiece,
    canvas: Size,
    order: Int,
): PiecePose {
    val rest = placed.loose
    if (placed.piece.id == OpeningCatalog.todayStamp.id) return PiecePose(rest.center, rest.rotation - 16f, rest.scale * 2.3f)
    val middle = Offset(canvas.width / 2f, canvas.height / 2f)
    val out = rest.center - middle
    val far = out.getDistance()
    val direction = if (far < 24f) SPARE_DIRECTIONS[order % SPARE_DIRECTIONS.size] else out / far
    val reach = hypot(placed.piece.width, placed.piece.height) * rest.scale * OFF_TABLE_SCALE / 2f + 24f

    fun travel(
        from: Float,
        towards: Float,
        length: Float,
    ) = when {
        towards > 0.001f -> (length + reach - from) / towards
        towards < -0.001f -> (-reach - from) / towards
        else -> Float.POSITIVE_INFINITY
    }
    val distance = min(travel(rest.center.x, direction.x, canvas.width), travel(rest.center.y, direction.y, canvas.height))
    return PiecePose(rest.center + direction * distance, rest.rotation + if (order % 2 == 0) 26f else -32f, rest.scale * OFF_TABLE_SCALE)
}

/**
 * Where [placed] is this frame, from how it is moving.
 *
 * @param off where it is before it is dealt.
 * @param phase the slow clock every loose piece drifts on, in radians.
 * @param look how the device is being turned, from -1 to 1 each way.
 * @param jolt the jolt the table is taking, and [joltFrom] where it came from.
 */
internal fun PieceMotion.drawn(
    placed: PlacedPiece,
    off: PiecePose,
    order: Int,
    phase: Float,
    look: Offset,
    jolt: Float,
    joltFrom: Offset,
): DrawnPiece {
    val arriving = arrived.value
    val filing = tidy.value
    val dealt = mix(off, placed.loose, arriving)
    // Tidying moves a piece only if it has a place on the page or lies in the page's way.
    val moves = placed.filed != null || !placed.stays
    val place = if (moves) mix(dealt, placed.filed ?: off, filing) else dealt
    // What a loose piece does and one on the page does not: drift, and answer the device fully.
    val loose = (if (moves) (1f - filing).coerceIn(0f, 1f) else 1f) * arriving.coerceIn(0f, 1f)
    val beat = phase + order * 1.7f
    val drift = Offset(cos(beat) * DRIFT_ACROSS, sin(beat) * DRIFT_ALONG) * loose
    val turned = look * (LOOK_FAR + (LOOK_NEAR - LOOK_FAR) * placed.depth) * (0.4f + 0.6f * loose)
    val away = place.center - joltFrom
    val distance = away.getDistance().coerceAtLeast(1f)
    val pushed = away / distance * (jolt * JOLT_PUSH * (JOLT_REACH / (JOLT_REACH + distance)))
    val isDate = placed.piece.id == OpeningCatalog.todayStamp.id
    return DrawnPiece(
        centre = place.center + moved.value + drift + turned + if (isDate) Offset.Zero else pushed,
        rotation =
            place.rotation + sin(beat + 1f) * DRIFT_TURN * loose + lean.value + if (isDate) 0f else jolt * JOLT_TURN * sin(order * 2.1f),
        scale = place.scale * (1f + held.value * HELD_GROWS),
        alpha = if (isDate) dateAlpha(arriving, filing, onPage = placed.filed != null) else 1f,
    )
}

/**
 * How solid today's date is. It appears as it comes down, and it is there outright once it is on
 * the page, however it got there: a screen restored already tidied has no deal to wait for.
 */
private fun dateAlpha(
    arriving: Float,
    filing: Float,
    onPage: Boolean,
): Float {
    val dealt = (arriving * 3f).coerceIn(0f, 1f)
    val filed = filing.coerceIn(0f, 1f)
    return if (onPage) max(dealt, filed) else dealt
}

/** How far above the table a piece is: nearer pieces sit higher, a piece on the page lies flatter, and a held one is lifted. */
internal fun PieceMotion.lift(placed: PlacedPiece): Float {
    val flatter = if (placed.filed != null) 0.6f * tidy.value.coerceIn(0f, 1f) else 0f
    return (0.2f + 0.6f * placed.depth) * (1f - flatter) + held.value * 0.6f
}

/** A movement of the finger as the piece saw it, turned back into a movement across the screen, in dp. */
internal fun PieceMotion.acrossScreen(
    seen: Offset,
    density: Float,
): Offset {
    val turn = drawnRotation * (PI.toFloat() / 180f)
    val across = Offset(seen.x * cos(turn) - seen.y * sin(turn), seen.x * sin(turn) + seen.y * cos(turn))
    return across * (drawnScale / density)
}

private fun mix(
    from: PiecePose,
    to: PiecePose,
    fraction: Float,
) = PiecePose(
    center = from.center + (to.center - from.center) * fraction,
    rotation = from.rotation + (to.rotation - from.rotation) * fraction,
    scale = from.scale + (to.scale - from.scale) * fraction,
)

// A piece off the table is a little larger than it will lie, as if nearer the eye.
private const val OFF_TABLE_SCALE = 1.12f

// Where a piece in the very middle comes from, since it has no direction of its own.
private val SPARE_DIRECTIONS = listOf(Offset(-0.7f, -0.7f), Offset(0.7f, 0.7f), Offset(-0.7f, 0.7f), Offset(0.7f, -0.7f))

// A loose piece drifts this far, in dp, and turns this much, in degrees.
private const val DRIFT_ACROSS = 1.2f
private const val DRIFT_ALONG = 2.6f
private const val DRIFT_TURN = 0.6f

// Turning the device carries the furthest pieces and the nearest this far, in dp.
private const val LOOK_FAR = 3f
private const val LOOK_NEAR = 15f

// The date landing pushes the pieces around it away by up to this much, in dp, falling off with distance.
private const val JOLT_PUSH = 9f
private const val JOLT_REACH = 260f
private const val JOLT_TURN = 1.4f

// A held piece is drawn this much larger.
private const val HELD_GROWS = 0.07f

// How hard pulling a piece sideways swings it, and the fastest it may swing, in degrees a second.
private const val LEAN_KICK = 9f
private const val LEAN_FASTEST = 260f

// How far a thrown piece slides: this many seconds at the speed it was let go with.
private const val THROW_SECONDS = 0.16f

// A piece comes to rest with its middle at least this far inside the screen, as a share of its
// width or height, so no more than a quarter of it is ever off the edge and it can always be
// picked up again.
private const val HIDDEN_MOST = 0.25f
