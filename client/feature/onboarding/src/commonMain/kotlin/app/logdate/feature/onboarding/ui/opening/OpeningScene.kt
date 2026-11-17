@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui.opening

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.logdate.ui.common.formatting.LocalToday
import app.logdate.ui.firstlaunch.PaperPiece
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.step.StepSceneBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_description
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_today
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.min

/** The tag of one piece on the table, by the id it has in [OpeningCatalog]. */
internal fun openingPieceTag(id: String) = "onboarding_opening_piece_$id"

/**
 * The first thing a person sees: a few days of someone's life, spread across the whole screen.
 *
 * The pieces come in from every side and land around the words, today's date comes down last and
 * the table jumps under it. From then on they are things, not a picture: each can be picked up,
 * moved and thrown, and stays where it is left. When the step asks how LogDate works, [tidied],
 * today's pile lines up on one page, laid out the way LogDate shows a day, and the rest of the
 * table stays as it is.
 *
 * The scene is laid out for the screen it is on by [arrangeOpening], from where the step put its
 * words and actions, so it fills a phone, a tablet either way up and half a folded screen alike.
 * With [animate] off, or when the person has asked for less motion, nothing moves on its own: the
 * pieces are simply where they belong.
 *
 * @param parallax how the device is being turned, from -1 to 1 each way. Read while drawing.
 */
@Composable
internal fun OpeningScene(
    bounds: StepSceneBounds,
    tidied: Boolean,
    animate: Boolean,
    parallax: () -> Offset,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reducedMotion by rememberSystemReduceMotion()
    val moving = animate && !reducedMotion
    val canvas = Size(bounds.size.width / density.density, bounds.size.height / density.density)
    val words = bounds.words.inDp(density.density)
    val actions = if (bounds.actions.isEmpty) Rect.Zero else bounds.actions.inDp(density.density)
    val systemTop = bounds.systemTop / density.density
    val arrangement = remember(canvas, words, actions, systemTop) { arrangeOpening(canvas, words, actions, systemTop) }

    val scope = rememberCoroutineScope()
    val haptics = LocalLogDateHaptics.current
    val table = remember(scope, haptics) { OpeningTable(scope, haptics) }
    table.arrangement = arrangement

    val phase = if (moving) driftPhase() else remember { mutableFloatStateOf(0f) }
    val description = stringResource(Res.string.onboarding_scene_description)
    // What is written on a piece is part of a picture, so it does not grow with the person's text size.
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        // Clipped, because where the scene has half a folded screen a piece that has left it must
        // not show on the other half. The scene itself says nothing to a screen reader: it lies in
        // front of the whole step, and a description on it would be read in place of the buttons
        // under a finger. Today's date carries the description instead.
        Box(modifier.fillMaxSize().clipToBounds()) {
            Backdrop(arrangement)
            val today = LocalToday.current
            val filingOrder = remember(arrangement) { filingOrder(arrangement) }
            // The page lies under today's four pieces and over everything else.
            arrangement.page?.let { TodayPage(it, tidied, moving, layer = arrangement.pieces.size - 4.5f, today = today) }
            arrangement.pieces.forEachIndexed { order, placed ->
                key(placed.piece.id) {
                    TablePiece(
                        placed = placed,
                        off = remember(placed, canvas, order) { offTable(placed, canvas, order) },
                        order = order,
                        count = arrangement.pieces.size,
                        filingTurn = filingOrder.getValue(placed.piece.id),
                        table = table,
                        tidied = tidied,
                        moving = moving,
                        phase = phase,
                        parallax = parallax,
                        today = today,
                        description = description.takeIf { placed.piece.id == OpeningCatalog.todayStamp.id },
                    )
                }
            }
        }
    }
}

/** The light the pile lies in. */
@Composable
private fun Backdrop(arrangement: OpeningArrangement) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxSize()) {
        val pile = arrangement.pile
        val glow = pile.width.dp.toPx() * 0.82f
        val middle =
            Offset(
                pile.center.x.dp
                    .toPx(),
                pile.center.y.dp
                    .toPx(),
            )
        drawCircle(Brush.radialGradient(listOf(colors.tertiaryContainer.copy(alpha = 0.5f), Color.Transparent), middle, glow), glow, middle)
    }
}

/**
 * Today's page, which comes up under the pile as the pile lines up on it. It is a piece of paper
 * like the rest, so it is drawn as one. Its head reads the way a day's does in LogDate: "Today",
 * and the date.
 */
@Composable
private fun TodayPage(
    page: DayPage,
    shown: Boolean,
    moving: Boolean,
    layer: Float,
    today: LocalDate,
) {
    val up by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec =
            when {
                !moving -> snap()
                shown -> spring(dampingRatio = 0.75f, stiffness = 170f)
                else -> tween(160)
            },
        label = "Today's page",
    )
    PaperPiece(
        width = page.width.dp,
        height = page.height.dp,
        shape = RoundedCornerShape(PAGE_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        place = {
            // It rises a little and grows into place, so it reads as arriving under the pile.
            val grown = page.scale * (0.92f + 0.08f * up)
            translationX = (page.centre.x - page.width / 2f).dp.toPx()
            translationY = (page.centre.y - page.height / 2f).dp.toPx() + (1f - up) * 18.dp.toPx()
            scaleX = grown
            scaleY = grown
            alpha = up.coerceIn(0f, 1f)
        },
        lift = { 0.3f },
        modifier = Modifier.zIndex(layer),
    ) {
        Column(Modifier.offset(page.heading.x.dp, page.heading.y.dp)) {
            Text(
                stringResource(Res.string.onboarding_scene_today),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                today.spelledOut(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A date the way LogDate heads a day: "Monday, November 16". */
private fun LocalDate.spelledOut(): String =
    "${DayOfWeekNames.ENGLISH_FULL.names[dayOfWeek.isoDayNumber - 1]}, ${MonthNames.ENGLISH_FULL.names[month.number - 1]} $day"

@Composable
private fun TablePiece(
    placed: PlacedPiece,
    off: PiecePose,
    order: Int,
    count: Int,
    filingTurn: Int,
    table: OpeningTable,
    tidied: Boolean,
    moving: Boolean,
    phase: State<Float>,
    parallax: () -> Offset,
    today: LocalDate,
    description: String?,
) {
    val piece = placed.piece
    val motion = remember { PieceMotion(arrived = !moving, tidied = tidied) }
    val isToday = piece.id == OpeningCatalog.todayStamp.id

    LaunchedEffect(moving) {
        if (!moving) {
            motion.arrived.snapTo(1f)
            return@LaunchedEffect
        }
        if (motion.arrived.value >= 1f) return@LaunchedEffect
        val between = min(DEAL_EVERY_MILLIS, DEAL_WITHIN_MILLIS / count)
        if (!isToday) {
            delay(order * between)
            motion.arrived.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 105f))
            return@LaunchedEffect
        }
        delay(count * between + DATE_WAITS_MILLIS)
        var landed = false
        motion.arrived.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f)) {
            if (!landed && value >= 1f) {
                landed = true
                table.dateLanded()
            }
        }
    }
    LaunchedEffect(tidied, moving) {
        val target = if (tidied) 1f else 0f
        if (!moving) {
            motion.moved.snapTo(Offset.Zero)
            motion.tidy.snapTo(target)
            return@LaunchedEffect
        }
        if (motion.tidy.targetValue == target && motion.moved.targetValue == Offset.Zero) return@LaunchedEffect
        if (tidied) launch { motion.moved.animateTo(Offset.Zero, spring(dampingRatio = 0.8f, stiffness = 130f)) }
        delay(filingTurn * FILE_EVERY_MILLIS)
        motion.tidy.animateTo(target, spring(dampingRatio = 0.7f, stiffness = 115f))
    }
    // A screen that changes shape deals the table again, so nothing is left where there is no longer room.
    LaunchedEffect(table.arrangement?.canvas) { motion.moved.snapTo(Offset.Zero) }

    val nowTidied by rememberUpdatedState(tidied)
    val nowPlaced by rememberUpdatedState(placed)
    // A piece can be picked up once it has landed and while it is not on its way somewhere. A piece
    // in flight crosses the actions, and it must not take a tap that was meant for them.
    val atRest by remember { derivedStateOf { motion.arrived.value > 0.9f && !motion.tidy.isRunning } }
    val hold =
        if (!atRest) {
            Modifier
        } else {
            Modifier.pointerInput(piece.id) {
                val speed = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    table.pickUp(motion)
                    var travelled = Offset.Zero
                    speed.resetTracking()
                    speed.addPosition(down.uptimeMillis, travelled)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val amount = motion.acrossScreen(change.positionChange(), density)
                            if (amount == Offset.Zero) continue
                            change.consume()
                            travelled += amount
                            speed.addPosition(change.uptimeMillis, travelled)
                            table.drag(motion, amount)
                        }
                    } finally {
                        val thrown = speed.calculateVelocity()
                        table.letGo(nowPlaced, motion, Offset(thrown.x, thrown.y), nowTidied)
                    }
                }
            }
        }
    val gesture =
        hold.clearAndSetSemantics {
            testTag = openingPieceTag(piece.id)
            if (description != null) contentDescription = description
        }

    PaperPiece(
        width = piece.width.dp,
        height = piece.height.dp,
        shape = piece.outline(),
        color = piece.paper(),
        place = {
            val drawn =
                motion.drawn(
                    nowPlaced,
                    off,
                    order,
                    phase.value,
                    parallax(),
                    table.jolt.value,
                    table.arrangement?.pile?.center ?: Offset.Zero,
                )
            motion.drawnRotation = drawn.rotation
            motion.drawnScale = drawn.scale
            translationX = (drawn.centre.x - piece.width / 2f).dp.toPx()
            translationY = (drawn.centre.y - piece.height / 2f).dp.toPx()
            rotationZ = drawn.rotation
            scaleX = drawn.scale
            scaleY = drawn.scale
            alpha = drawn.alpha
        },
        lift = { motion.lift(nowPlaced) },
        // A piece that has been touched lies on top of the ones that have not, the latest on top of all.
        modifier = Modifier.zIndex(if (motion.raised > 0) (count + motion.raised).toFloat() else order.toFloat()),
        gesture = gesture,
    ) { PieceFace(piece, today) }
}

/**
 * The turn each piece takes when the table is tidied: the pieces in the page's way go first and
 * together, then today's pieces in the order they stand on the page, the date first.
 */
private fun filingOrder(arrangement: OpeningArrangement): Map<String, Int> {
    val onPage = OpeningCatalog.today.mapIndexed { turn, piece -> piece.id to turn + 2 }.toMap()
    return arrangement.pieces.associate { it.piece.id to (onPage[it.piece.id] ?: 0) }
}

@Composable
private fun driftPhase(): State<Float> =
    rememberInfiniteTransition(label = "Table drift").animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "Table drift phase",
    )

private fun Rect.inDp(density: Float) = Rect(left / density, top / density, right / density, bottom / density)

// Pieces are dealt this far apart, but the whole table is dealt within the longer time whatever its size.
private const val DEAL_EVERY_MILLIS = 42L
private const val DEAL_WITHIN_MILLIS = 700L

// How long after the last piece is dealt today's date comes down.
private const val DATE_WAITS_MILLIS = 260L

// How far apart today's pieces set off for their places on the page.
private const val FILE_EVERY_MILLIS = 45L

// How round the page's corners are, which is how round a panel's are in the app.
private val PAGE_CORNER = 28.dp
