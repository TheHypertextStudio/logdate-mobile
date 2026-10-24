package app.logdate.ui.foldable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * Information about foldable device state.
 *
 * @param isFoldable Whether the device has foldable features
 * @param foldingFeature The current folding feature, if any
 * @param hingePosition Position of the hinge in dp (null if not foldable)
 * @param hingeBounds Bounds of the hinge area to avoid
 */
data class FoldableDeviceInfo(
    val isFoldable: Boolean = false,
    val foldingFeature: FoldingFeature? = null,
    val hingePosition: HingePosition? = null,
    val hingeBounds: HingeBounds? = null,
)

/**
 * Position information for device hinge.
 *
 * @param x Horizontal position in dp (for vertical hinges)
 * @param y Vertical position in dp (for horizontal hinges)
 * @param orientation Hinge orientation (vertical or horizontal)
 */
data class HingePosition(
    val x: Float? = null,
    val y: Float? = null,
    val orientation: FoldingFeature.Orientation,
)

/**
 * Bounds of the hinge area that content should avoid.
 *
 * @param left Left edge in dp
 * @param top Top edge in dp
 * @param right Right edge in dp
 * @param bottom Bottom edge in dp
 * @param width Width of hinge in dp
 * @param height Height of hinge in dp
 */
data class HingeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
)

/**
 * Remembers foldable device information.
 *
 * This composable observes the window layout and provides information about
 * foldable device features like hinges and fold lines. This information can
 * be used to adapt layouts to avoid content appearing across the hinge.
 *
 * Example usage:
 * ```kotlin
 * val foldableInfo = rememberFoldableDeviceInfo()
 * if (foldableInfo.isFoldable) {
 *     // Adapt layout to avoid hinge
 *     when (foldableInfo.hingePosition?.orientation) {
 *         FoldingFeature.Orientation.VERTICAL -> {
 *             // Use Row layout with content on either side of hinge
 *         }
 *         FoldingFeature.Orientation.HORIZONTAL -> {
 *             // Use Column layout with content above/below hinge
 *         }
 *     }
 * }
 * ```
 *
 * @return FoldableDeviceInfo with current device state
 */
@Composable
fun rememberFoldableDeviceInfo(): FoldableDeviceInfo {
    val context = LocalContext.current
    val density = LocalDensity.current

    val windowInfoTracker = remember { WindowInfoTracker.getOrCreate(context) }
    val windowLayoutInfo by windowInfoTracker
        .windowLayoutInfo(context)
        .collectAsStateWithLifecycle(initialValue = WindowLayoutInfo(emptyList()))

    return remember(windowLayoutInfo, density.density) {
        processFoldableInfo(windowLayoutInfo, density.density)
    }
}

/**
 * Cross-platform implementation of rememberFoldableLayoutInfo for Android.
 *
 * Uses androidx.window to detect foldable devices and their state.
 */
@Composable
actual fun rememberFoldableLayoutInfo(): FoldableLayoutInfo {
    val override = LocalFoldableLayoutInfoOverride.current
    if (override != null) return override

    val context = LocalContext.current
    val density = LocalDensity.current

    val windowInfoTracker = remember { WindowInfoTracker.getOrCreate(context) }
    val windowLayoutInfo by windowInfoTracker
        .windowLayoutInfo(context)
        .collectAsStateWithLifecycle(initialValue = WindowLayoutInfo(emptyList()))

    return remember(windowLayoutInfo, density.density) {
        processFoldableLayoutInfo(windowLayoutInfo, density.density)
    }
}

/**
 * Processes WindowLayoutInfo to extract foldable device information.
 *
 * @param windowLayoutInfo The window layout information from WindowInfoTracker
 * @param densityDpi Screen density for converting px to dp
 * @return FoldableDeviceInfo extracted from the layout info
 */
fun processFoldableInfo(
    windowLayoutInfo: WindowLayoutInfo,
    density: Float,
): FoldableDeviceInfo {
    val foldingFeature =
        windowLayoutInfo.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .firstOrNull()

    if (foldingFeature == null) {
        return FoldableDeviceInfo(isFoldable = false)
    }

    val bounds = foldingFeature.bounds
    val pxToDp = { px: Int -> pixelsToDp(px, density) }

    val hingePosition =
        when (foldingFeature.orientation) {
            FoldingFeature.Orientation.VERTICAL -> {
                HingePosition(
                    x = pxToDp(bounds.left),
                    y = null,
                    orientation = FoldingFeature.Orientation.VERTICAL,
                )
            }

            FoldingFeature.Orientation.HORIZONTAL -> {
                HingePosition(
                    x = null,
                    y = pxToDp(bounds.top),
                    orientation = FoldingFeature.Orientation.HORIZONTAL,
                )
            }

            else -> {
                HingePosition(
                    x = null,
                    y = null,
                    orientation = foldingFeature.orientation,
                )
            }
        }

    val hingeBounds =
        HingeBounds(
            left = pxToDp(bounds.left),
            top = pxToDp(bounds.top),
            right = pxToDp(bounds.right),
            bottom = pxToDp(bounds.bottom),
            width = pxToDp(bounds.width()),
            height = pxToDp(bounds.height()),
        )

    return FoldableDeviceInfo(
        isFoldable = true,
        foldingFeature = foldingFeature,
        hingePosition = hingePosition,
        hingeBounds = hingeBounds,
    )
}

fun processFoldableLayoutInfo(
    windowLayoutInfo: WindowLayoutInfo,
    density: Float,
): FoldableLayoutInfo {
    val foldingFeature =
        windowLayoutInfo.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .firstOrNull()
            ?: return FoldableLayoutInfo()

    val bounds = foldingFeature.bounds
    val hingeBounds =
        FoldableHingeBounds(
            left = pixelsToDp(bounds.left, density).dp,
            top = pixelsToDp(bounds.top, density).dp,
            right = pixelsToDp(bounds.right, density).dp,
            bottom = pixelsToDp(bounds.bottom, density).dp,
            width = pixelsToDp(bounds.width(), density).dp,
            height = pixelsToDp(bounds.height(), density).dp,
        )
    val orientation = foldingFeature.orientation.toFoldableHingeOrientation()
    val state = foldingFeature.state.toFoldableHingeState()

    return FoldableLayoutInfo(
        isFoldable = true,
        posture = postureFor(orientation = orientation, state = state),
        hinge =
            FoldableHingeInfo(
                orientation = orientation,
                state = state,
                occlusionType = foldingFeature.occlusionType.toFoldableOcclusionType(),
                bounds = hingeBounds,
                isSeparating = foldingFeature.isSeparating,
            ),
    )
}

/**
 * Determines if content should use a dual-pane layout based on foldable state.
 *
 * This function checks if the device is folded in a table-top or book mode where
 * a dual-pane layout would be beneficial.
 *
 * @param foldableInfo Current foldable device information
 * @return true if dual-pane layout is recommended
 */
fun shouldUseDualPaneLayout(foldableInfo: FoldableDeviceInfo): Boolean {
    val feature = foldableInfo.foldingFeature ?: return false

    return when (feature.state) {
        FoldingFeature.State.HALF_OPENED -> {
            // In half-opened state (like tabletop or book mode), use dual-pane
            true
        }

        FoldingFeature.State.FLAT -> {
            // In flat state, device is fully unfolded
            // Could still use dual-pane if screen is large enough
            true
        }

        else -> {
            false
        }
    }
}

private fun FoldingFeature.Orientation.toFoldableHingeOrientation(): FoldableHingeOrientation =
    when (this) {
        FoldingFeature.Orientation.VERTICAL -> FoldableHingeOrientation.Vertical
        FoldingFeature.Orientation.HORIZONTAL -> FoldableHingeOrientation.Horizontal
        else -> FoldableHingeOrientation.Unknown
    }

private fun FoldingFeature.State.toFoldableHingeState(): FoldableHingeState =
    when (this) {
        FoldingFeature.State.FLAT -> FoldableHingeState.Flat
        FoldingFeature.State.HALF_OPENED -> FoldableHingeState.HalfOpened
        else -> FoldableHingeState.Unknown
    }

private fun FoldingFeature.OcclusionType.toFoldableOcclusionType(): FoldableOcclusionType =
    when (this) {
        FoldingFeature.OcclusionType.NONE -> FoldableOcclusionType.None
        FoldingFeature.OcclusionType.FULL -> FoldableOcclusionType.Full
        else -> FoldableOcclusionType.Unknown
    }

private fun postureFor(
    orientation: FoldableHingeOrientation,
    state: FoldableHingeState,
): FoldablePosture =
    if (state == FoldableHingeState.HalfOpened) {
        when (orientation) {
            FoldableHingeOrientation.Vertical -> FoldablePosture.Book
            FoldableHingeOrientation.Horizontal -> FoldablePosture.Tabletop
            FoldableHingeOrientation.Unknown -> FoldablePosture.Standard
        }
    } else {
        FoldablePosture.Standard
    }
