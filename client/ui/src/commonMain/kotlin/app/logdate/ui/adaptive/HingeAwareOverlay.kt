@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.rememberFoldableLayoutInfo

/**
 * Constrains a single blocking overlay — a lock screen card, an update banner, or a blocking
 * dialog — so it lands fully inside one physical pane of a folded device instead of straddling a
 * separating hinge.
 *
 * Unlike [FoldableBookLayout] and [FoldableTabletopLayout], this is not a two-pane splitter: there
 * is exactly one piece of [content]. On a separating vertical hinge the content area is pinned to
 * the left physical pane; on a separating horizontal hinge it is pinned to the top physical pane.
 * When no separating hinge is present the overlay keeps the caller's existing layout: full-window
 * content (such as a lock screen) sets [fillMaxSizeWhenFlat] to true so it stays centered in the
 * whole window, while an inline banner sets it to false so it keeps its intrinsic size and the
 * caller's alignment. [content] is positioned within the resolved area according to [alignment].
 *
 * The container itself draws nothing and registers no pointer input, so empty regions do not block
 * interaction with composables drawn beneath it.
 */
@Composable
fun HingeAwareOverlay(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    fillMaxSizeWhenFlat: Boolean = true,
    foldableLayoutInfo: FoldableLayoutInfo = rememberFoldableLayoutInfo(),
    content: @Composable BoxScope.() -> Unit,
) {
    val hinge = foldableLayoutInfo.hinge
    if (hinge == null || !hinge.isSeparating) {
        // No separating hinge: there is no pane to pin to, so keep the caller's intrinsic layout
        // instead of forcing a full-window container that would override the caller's alignment.
        Box(
            modifier = if (fillMaxSizeWhenFlat) modifier.fillMaxSize() else modifier,
            contentAlignment = alignment,
            content = content,
        )
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val splitLayout =
            calculateFoldableSplitLayout(
                containerWidth = maxWidth,
                containerHeight = maxHeight,
                layoutInfo = foldableLayoutInfo,
            )

        val paneModifier =
            when (splitLayout) {
                is FoldableSplitLayout.Vertical -> {
                    Modifier
                        .width(splitLayout.leftPane.width)
                        .fillMaxHeight()
                }

                is FoldableSplitLayout.Horizontal -> {
                    Modifier
                        .fillMaxWidth()
                        .height(splitLayout.topPane.height)
                }

                FoldableSplitLayout.None -> {
                    Modifier.fillMaxSize()
                }
            }

        Box(
            modifier = paneModifier,
            contentAlignment = alignment,
            content = content,
        )
    }
}
