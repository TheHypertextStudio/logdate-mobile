@file:Suppress("ktlint:standard:filename")

package app.logdate.ui.foldable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Basic foldable device information available across all platforms.
 *
 * On platforms without foldable support (iOS, Desktop), this will always
 * return non-foldable state.
 */
data class FoldableState(
    val isFoldable: Boolean = false,
    val isHalfOpened: Boolean = false,
    val hasVerticalHinge: Boolean = false,
    val hasHorizontalHinge: Boolean = false,
)

data class FoldableLayoutInfo(
    val isFoldable: Boolean = false,
    val posture: FoldablePosture = FoldablePosture.Standard,
    val hinge: FoldableHingeInfo? = null,
)

enum class FoldablePosture {
    Standard,
    Book,
    Tabletop,
}

data class FoldableHingeInfo(
    val orientation: FoldableHingeOrientation,
    val state: FoldableHingeState,
    val occlusionType: FoldableOcclusionType,
    val bounds: FoldableHingeBounds,
    val isSeparating: Boolean,
)

enum class FoldableHingeOrientation {
    Vertical,
    Horizontal,
    Unknown,
}

enum class FoldableHingeState {
    Flat,
    HalfOpened,
    Unknown,
}

enum class FoldableOcclusionType {
    None,
    Full,
    Unknown,
}

data class FoldableHingeBounds(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
    val width: Dp,
    val height: Dp,
)

data class FoldablePaneBounds(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
    val width: Dp,
    val height: Dp,
)

sealed interface FoldableSplitLayout {
    data object None : FoldableSplitLayout

    data class Vertical(
        val leftPane: FoldablePaneBounds,
        val rightPane: FoldablePaneBounds,
        val hingeBounds: FoldableHingeBounds,
    ) : FoldableSplitLayout

    data class Horizontal(
        val topPane: FoldablePaneBounds,
        val bottomPane: FoldablePaneBounds,
        val hingeBounds: FoldableHingeBounds,
    ) : FoldableSplitLayout
}

/**
 * Remembers the current foldable state of the device.
 *
 * This is a cross-platform composable that provides foldable device
 * information. On Android, it uses androidx.window to detect foldables.
 * On other platforms, it returns a non-foldable state.
 *
 * @return Current foldable state
 */
@Composable
fun rememberFoldableState(): FoldableState = rememberFoldableLayoutInfo().toFoldableState()

@Composable
expect fun rememberFoldableLayoutInfo(): FoldableLayoutInfo

val LocalFoldableLayoutInfoOverride =
    staticCompositionLocalOf<FoldableLayoutInfo?> { null }

@Composable
fun provideFoldableLayoutInfo(
    foldableLayoutInfo: FoldableLayoutInfo,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFoldableLayoutInfoOverride provides foldableLayoutInfo) {
        content()
    }
}

/**
 * This layout info as seen from a container whose top-left corner sits at [origin] in the window.
 * Hinge bounds are reported in window coordinates, so a container that isn't at the window origin
 * -- under a top app bar, beside a navigation rail -- has to shift them into its own coordinates
 * before splitting, or its panes land off the hinge.
 */
fun FoldableLayoutInfo.relativeTo(origin: DpOffset): FoldableLayoutInfo {
    val hinge = hinge ?: return this
    if (origin == DpOffset.Zero) return this
    val bounds = hinge.bounds
    return copy(
        hinge =
            hinge.copy(
                bounds =
                    bounds.copy(
                        left = bounds.left - origin.x,
                        top = bounds.top - origin.y,
                        right = bounds.right - origin.x,
                        bottom = bounds.bottom - origin.y,
                    ),
            ),
    )
}

/**
 * The window position [rememberWindowOrigin] reports before it has measured anything. A layout learns
 * where it sits one frame after it is first drawn, so previews and tests that render a single frame
 * provide it to show the settled layout.
 */
val LocalWindowOriginOverride = staticCompositionLocalOf<DpOffset?> { null }

/**
 * Remembers where the modified element's top-left corner sits in the window, in dp. Pair with
 * [relativeTo] so a foldable split lines up with the physical hinge wherever the layout is placed.
 */
@Composable
fun rememberWindowOrigin(): Pair<DpOffset, Modifier> {
    LocalWindowOriginOverride.current?.let { return it to Modifier }
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(DpOffset.Zero) }
    val modifier =
        Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            origin = with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
        }
    return origin to modifier
}

fun calculateFoldableSplitLayout(
    containerWidth: Dp,
    containerHeight: Dp,
    layoutInfo: FoldableLayoutInfo,
    minPaneWidth: Dp = 320.dp,
    minPaneHeight: Dp = 280.dp,
): FoldableSplitLayout {
    val hinge = layoutInfo.hinge ?: return FoldableSplitLayout.None
    if (!hinge.isSeparating) return FoldableSplitLayout.None

    return when (hinge.orientation) {
        FoldableHingeOrientation.Vertical -> {
            val leftWidth = hinge.bounds.left.coerceAtLeast(0.dp)
            val rightWidth = (containerWidth - hinge.bounds.right).coerceAtLeast(0.dp)
            if (leftWidth < minPaneWidth || rightWidth < minPaneWidth) {
                FoldableSplitLayout.None
            } else {
                FoldableSplitLayout.Vertical(
                    leftPane =
                        FoldablePaneBounds(
                            left = 0.dp,
                            top = 0.dp,
                            right = hinge.bounds.left,
                            bottom = containerHeight,
                            width = leftWidth,
                            height = containerHeight,
                        ),
                    rightPane =
                        FoldablePaneBounds(
                            left = hinge.bounds.right,
                            top = 0.dp,
                            right = containerWidth,
                            bottom = containerHeight,
                            width = rightWidth,
                            height = containerHeight,
                        ),
                    hingeBounds = hinge.bounds,
                )
            }
        }

        FoldableHingeOrientation.Horizontal -> {
            val topHeight = hinge.bounds.top.coerceAtLeast(0.dp)
            val bottomHeight = (containerHeight - hinge.bounds.bottom).coerceAtLeast(0.dp)
            if (topHeight < minPaneHeight || bottomHeight < minPaneHeight) {
                FoldableSplitLayout.None
            } else {
                FoldableSplitLayout.Horizontal(
                    topPane =
                        FoldablePaneBounds(
                            left = 0.dp,
                            top = 0.dp,
                            right = containerWidth,
                            bottom = hinge.bounds.top,
                            width = containerWidth,
                            height = topHeight,
                        ),
                    bottomPane =
                        FoldablePaneBounds(
                            left = 0.dp,
                            top = hinge.bounds.bottom,
                            right = containerWidth,
                            bottom = containerHeight,
                            width = containerWidth,
                            height = bottomHeight,
                        ),
                    hingeBounds = hinge.bounds,
                )
            }
        }

        FoldableHingeOrientation.Unknown -> {
            FoldableSplitLayout.None
        }
    }
}

internal fun pixelsToDp(
    px: Int,
    density: Float,
): Float = px / density

private fun FoldableLayoutInfo.toFoldableState(): FoldableState {
    val hinge = hinge
    return FoldableState(
        isFoldable = isFoldable,
        isHalfOpened = hinge?.state == FoldableHingeState.HalfOpened,
        hasVerticalHinge = hinge?.orientation == FoldableHingeOrientation.Vertical,
        hasHorizontalHinge = hinge?.orientation == FoldableHingeOrientation.Horizontal,
    )
}
