@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin

/** Keeps a focused settings page together in one usable region beside a separating hinge. */
@Composable
internal fun AudioSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val foldable = rememberFoldableLayoutInfo()
    val (origin, trackOrigin) = rememberWindowOrigin()
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(trackOrigin)) {
        val pane =
            when (
                val split =
                    calculateFoldableSplitLayout(
                        maxWidth,
                        maxHeight,
                        foldable.relativeTo(origin),
                        minPaneWidth = 0.dp,
                        minPaneHeight = 0.dp,
                    )
            ) {
                is FoldableSplitLayout.Vertical -> {
                    if (split.leftPane.width >= split.rightPane.width) split.leftPane else split.rightPane
                }
                is FoldableSplitLayout.Horizontal ->
                    if (split.topPane.height >=
                        split.bottomPane.height
                    ) {
                        split.topPane
                    } else {
                        split.bottomPane
                    }
                FoldableSplitLayout.None -> null
            }
        if (pane == null) {
            SettingsScaffold(title, onBack, titleMaxLines = 1, content = content)
        } else {
            val left = pane.left.coerceIn(0.dp, maxWidth)
            val top = pane.top.coerceIn(0.dp, maxHeight)
            Box(
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset(left, top)
                    .width(pane.width.coerceIn(0.dp, maxWidth - left))
                    .height(pane.height.coerceIn(0.dp, maxHeight - top))
                    .clipToBounds(),
            ) {
                SettingsScaffold(title, onBack, titleMaxLines = 1, content = content)
            }
        }
    }
}
