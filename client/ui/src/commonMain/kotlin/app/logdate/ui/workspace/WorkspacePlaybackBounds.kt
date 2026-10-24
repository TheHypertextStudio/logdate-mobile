package app.logdate.ui.workspace

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.relativeTo

internal fun resolveWorkspacePlaybackBounds(
    width: Dp,
    height: Dp,
    foldable: FoldableLayoutInfo = FoldableLayoutInfo(),
    origin: DpOffset = DpOffset.Zero,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): PanelBounds {
    val viewport = PanelBounds(0.dp, 0.dp, width, height)
    val hinge =
        foldable.relativeTo(origin).hinge?.takeIf {
            it.isSeparating &&
                when (it.orientation) {
                    FoldableHingeOrientation.Vertical -> it.bounds.bottom > 0.dp && it.bounds.top < height
                    FoldableHingeOrientation.Horizontal -> it.bounds.right > 0.dp && it.bounds.left < width
                    else -> false
                }
        }
    val region =
        when (hinge?.orientation) {
            FoldableHingeOrientation.Vertical -> {
                val left = hinge.bounds.left.coerceIn(0.dp, width)
                val right = hinge.bounds.right.coerceIn(left, width)
                val leading = PanelBounds(0.dp, 0.dp, left, height)
                val trailing = PanelBounds(right, 0.dp, width - right, height)
                val preferred =
                    when {
                        leading.width > trailing.width -> leading
                        trailing.width > leading.width -> trailing
                        layoutDirection == LayoutDirection.Rtl -> trailing
                        else -> leading
                    }
                if (preferred.width >= 560.dp) preferred else viewport
            }

            FoldableHingeOrientation.Horizontal -> {
                val top = hinge.bounds.top.coerceIn(0.dp, height)
                val bottom = hinge.bounds.bottom.coerceIn(top, height)
                val upper = PanelBounds(0.dp, 0.dp, width, top)
                val lower = PanelBounds(0.dp, bottom, width, height - bottom)
                if (upper.height >= 220.dp || upper.height >= lower.height) upper else lower
            }

            else -> {
                viewport
            }
        }
    val storyWidth = region.width.coerceAtMost(1200.dp)
    return region.copy(x = region.x + (region.width - storyWidth) / 2, width = storyWidth)
}
