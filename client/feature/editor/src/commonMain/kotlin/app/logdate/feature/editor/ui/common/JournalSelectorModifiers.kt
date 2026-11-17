@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.common

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Number of journal items visible before the list scrolls
private const val JOURNAL_PICKER_VISIBLE_ITEMS = 4

// MD3 ListItem one-line natural height (56dp) + 2dp top + 2dp bottom outer padding
private val JOURNAL_PICKER_ITEM_HEIGHT: Dp = 60.dp

// "Journals" label row height
private val JOURNAL_PICKER_HEADER_HEIGHT: Dp = 36.dp

// Reserved for system bars, editor toolbar, collapsed selector card, and breathing room.
// Sized conservatively so the list stays comfortably on screen even in landscape.
private val JOURNAL_PICKER_SCREEN_MARGIN: Dp = 160.dp

/**
 * Shared corner shape applied to both the collapsed trigger card and the expanded picker card
 * in [JournalSelectorDropdown], keeping their outlines visually continuous during the
 * expand/collapse animation.
 */
internal val JournalSelectorShape = RoundedCornerShape(16.dp)

/**
 * MD3 Expressive *Emphasized Decelerate* easing — used for elements entering the screen.
 *
 * Cubic bézier (0.05, 0.7, 0.1, 1.0) per the Material Design motion spec. Applied in
 * [journalItemCheckmarkEnterTransition].
 *
 * @see EmphasizedAccelerate
 */
internal val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * MD3 Expressive *Emphasized Accelerate* easing — used for elements leaving the screen.
 *
 * Cubic bézier (0.3, 0.0, 0.8, 0.15) per the Material Design motion spec. Applied in
 * [journalItemCheckmarkExitTransition].
 *
 * @see EmphasizedDecelerate
 */
internal val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Clips the journal [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] from the top
 * during a predictive back gesture, keeping the bottom edge fixed.
 *
 * At progress `0f` the list is full height. As progress approaches `1f` the visible height
 * shrinks toward zero — items disappear from the top while the bottom stays anchored. The
 * "Journals" header above this modifier is unaffected.
 *
 * @param backProgress Lambda returning the current gesture progress (0–1).
 */
internal fun Modifier.journalListBackClip(backProgress: () -> Float): Modifier =
    this
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val progress = backProgress()
            // Floor at JOURNAL_PICKER_HEADER_HEIGHT so the overall card (header + list)
            // never shrinks below roughly the collapsed trigger's height.
            val minHeightPx = JOURNAL_PICKER_HEADER_HEIGHT.roundToPx()
            val visibleHeight =
                (placeable.height * (1f - progress))
                    .roundToInt()
                    .coerceAtLeast(minHeightPx)
            layout(placeable.width, visibleHeight) {
                // Bottom-anchored: content placed at negative offset so its bottom
                // aligns with the layout bottom. Top portion clips away.
                placeable.placeRelative(0, visibleHeight - placeable.height)
            }
        }

/**
 * Content transition for the [AnimatedContent][androidx.compose.animation.AnimatedContent] that
 * switches between the collapsed and expanded states of [JournalSelectorDropdown].
 *
 * The `sharedBounds` overlay handles the card surface morph (size + shape + color)
 * independently; this spec only crossfades the inner content.
 */
internal fun journalSelectorContentTransition(): ContentTransform =
    fadeIn(tween(200, delayMillis = 80, easing = EmphasizedDecelerate)) togetherWith
        fadeOut(tween(150, easing = EmphasizedAccelerate))

/**
 * Enter transition for the trailing checkmark in a [JournalItem] row appearing on selection.
 *
 * Uses [EmphasizedDecelerate] so all motion within the selector follows a unified feel.
 *
 * @see journalItemCheckmarkExitTransition
 */
internal fun journalItemCheckmarkEnterTransition(): EnterTransition =
    scaleIn(animationSpec = tween(200, easing = EmphasizedDecelerate)) +
        fadeIn(tween(150, delayMillis = 30))

/**
 * Exit transition for the trailing checkmark in a [JournalItem] row disappearing on deselection.
 *
 * Uses [EmphasizedAccelerate] for a snappy dismissal feel.
 *
 * @see journalItemCheckmarkEnterTransition
 */
internal fun journalItemCheckmarkExitTransition(): ExitTransition =
    scaleOut(animationSpec = tween(150, easing = EmphasizedAccelerate)) +
        fadeOut(tween(100))

/**
 * Constrains the [JournalPickerList] card height when expanded.
 *
 * The natural height shows [JOURNAL_PICKER_VISIBLE_ITEMS] items plus the header row. This is
 * capped against the current window height minus [JOURNAL_PICKER_SCREEN_MARGIN] so the list
 * never overflows on compact screens (e.g. landscape phones where height can be ~360dp).
 * Applied directly on the expanded [Card] inside [JournalSelectorDropdown].
 */
@Composable
fun Modifier.journalSelectorExpandedHeight(): Modifier {
    val naturalHeight =
        JOURNAL_PICKER_ITEM_HEIGHT * JOURNAL_PICKER_VISIBLE_ITEMS + JOURNAL_PICKER_HEADER_HEIGHT
    val windowHeight =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    val maxHeight = minOf(naturalHeight, windowHeight - JOURNAL_PICKER_SCREEN_MARGIN)
    return heightIn(max = maxHeight)
}
