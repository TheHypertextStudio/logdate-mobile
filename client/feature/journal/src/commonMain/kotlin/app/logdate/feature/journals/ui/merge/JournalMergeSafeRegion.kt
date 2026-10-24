@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.merge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin

@Composable
internal fun MergeSafeRegion(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val layout = rememberFoldableLayoutInfo()
    val (origin, trackOrigin) = rememberWindowOrigin()
    BoxWithConstraints(modifier.fillMaxSize().then(trackOrigin), contentAlignment = Alignment.TopCenter) {
        val relative = layout.relativeTo(origin)
        val hinge = relative.hinge?.takeIf { it.isSeparating }
        when {
            hinge?.orientation == FoldableHingeOrientation.Horizontal -> {
                val above = hinge.bounds.top.coerceIn(0.dp, maxHeight)
                val below = (maxHeight - hinge.bounds.bottom).coerceIn(0.dp, maxHeight)
                val useAbove = above >= below
                Box(
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset(y = if (useAbove) 0.dp else hinge.bounds.bottom)
                        .fillMaxWidth()
                        .height(if (useAbove) above else below),
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { content() } }
            }

            hinge?.orientation == FoldableHingeOrientation.Vertical -> {
                val left = hinge.bounds.left.coerceIn(0.dp, maxWidth)
                val right = (maxWidth - hinge.bounds.right).coerceIn(0.dp, maxWidth)
                val useLeft = left >= right
                Box(
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset(x = if (useLeft) 0.dp else hinge.bounds.right)
                        .width(if (useLeft) left else right)
                        .fillMaxHeight(),
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { content() } }
            }

            else -> {
                content()
            }
        }
    }
}
