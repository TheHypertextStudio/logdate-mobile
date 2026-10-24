package app.logdate.feature.editor.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the photo and editable caption together through resizing and keyboard transitions. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PhotoEditorLayout(
    aspectRatio: Float,
    framed: Boolean,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
    editor: @Composable () -> Unit,
) {
    val fold = rememberFoldableLayoutInfo()
    val (origin, trackOrigin) = rememberWindowOrigin()
    val surfaceColor = if (framed) Color.White else MaterialTheme.colorScheme.surfaceContainer
    BoxWithConstraints(modifier.fillMaxSize().then(trackOrigin)) {
        val split =
            calculateFoldableSplitLayout(
                maxWidth,
                maxHeight,
                fold.relativeTo(origin),
                minPaneWidth = 240.dp,
                minPaneHeight = 120.dp,
            )
        Layout(
            content = {
                Box(Modifier.fillMaxSize().background(surfaceColor).testTag("photo_surface"))
                Box(Modifier.fillMaxSize()) { preview() }
                Box(Modifier.padding(horizontal = if (framed) 12.dp else 16.dp, vertical = 12.dp)) { editor() }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val gap = 16.dp.roundToPx()
            val border = if (framed) 12.dp.roundToPx() else 0
            val pane =
                when (split) {
                    is FoldableSplitLayout.Vertical -> {
                        split.leftPane
                    }

                    is FoldableSplitLayout.Horizontal -> {
                        if (fold.hinge?.occlusionType ==
                            FoldableOcclusionType.Full
                        ) {
                            split.topPane
                        } else {
                            null
                        }
                    }

                    FoldableSplitLayout.None -> {
                        null
                    }
                }
            val availableWidth = (pane?.width?.roundToPx() ?: width) - gap * 2
            val availableHeight = (pane?.height?.roundToPx() ?: height) - gap * 2
            val captionBudget = min(120.dp.roundToPx(), availableHeight / 3).coerceAtLeast(0)
            val imageHeightLimit = (availableHeight - captionBudget - border).coerceAtLeast(1)
            val objectWidth =
                min(min(640.dp.roundToPx(), availableWidth), (imageHeightLimit * aspectRatio).roundToInt() + border * 2).coerceAtLeast(
                    border * 2 + 1,
                )
            val imageWidth = objectWidth - border * 2
            val imageHeight = (imageWidth / aspectRatio).roundToInt().coerceAtLeast(1)
            val caption = measurables[2].measure(Constraints(minWidth = objectWidth, maxWidth = objectWidth, maxHeight = captionBudget))
            val objectHeight = border + imageHeight + caption.height
            val surface = measurables[0].measure(Constraints.fixed(objectWidth, objectHeight))
            val photo = measurables[1].measure(Constraints.fixed(imageWidth, imageHeight))
            val objectX = ((pane?.width?.roundToPx() ?: width) - objectWidth) / 2
            layout(width, height) {
                surface.place(objectX, gap)
                photo.place(objectX + border, gap + border)
                caption.place(objectX, gap + border + imageHeight)
            }
        }
    }
}
