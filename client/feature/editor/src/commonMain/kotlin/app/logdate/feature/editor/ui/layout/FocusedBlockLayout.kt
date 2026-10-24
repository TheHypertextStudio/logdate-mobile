package app.logdate.feature.editor.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin

/** Keeps the same editor composition and focus as the keyboard changes the available space. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun FocusedBlockLayout(
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
    textEditor: Boolean = false,
    contentPadding: Dp = 16.dp,
    expanded: Boolean = true,
    editor: @Composable () -> Unit,
) {
    val fold = rememberFoldableLayoutInfo()
    val (origin, trackOrigin) = rememberWindowOrigin()
    val localFold = fold.relativeTo(origin)
    Layout(
        content = {
            Box(Modifier.fillMaxSize()) { preview?.invoke() }
            Box(Modifier.fillMaxSize().padding(contentPadding)) { editor() }
        },
        modifier = modifier.then(if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).then(trackOrigin),
    ) { measurables, constraints ->
        if (!expanded) {
            val input = measurables[1].measure(constraints)
            measurables[0].measure(Constraints.fixed(0, 0))
            return@Layout layout(input.width, input.height) { input.place(0, 0) }
        }
        val split =
            calculateFoldableSplitLayout(
                containerWidth = constraints.maxWidth.toDp(),
                containerHeight = constraints.maxHeight.toDp(),
                layoutInfo = localFold,
                minPaneWidth = 240.dp,
                minPaneHeight = 120.dp,
            )
        val sideBySide = constraints.maxWidth.toDp() >= 840.dp
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        var previewWidth = width
        var previewHeight = 0
        var editorX = 0
        var editorY = 0
        var editorWidth = width
        var editorHeight = height
        when (split) {
            is FoldableSplitLayout.Vertical -> {
                previewWidth = split.leftPane.width.roundToPx()
                previewHeight = height
                editorX = split.rightPane.left.roundToPx()
                editorWidth = split.rightPane.width.roundToPx()
            }

            is FoldableSplitLayout.Horizontal -> {
                previewHeight = split.topPane.height.roundToPx()
                editorY = split.bottomPane.top.roundToPx()
                editorHeight = split.bottomPane.height.roundToPx()
            }

            FoldableSplitLayout.None -> {
                if (preview != null && sideBySide) {
                    editorWidth = (if (textEditor) 640.dp else 360.dp).roundToPx().coerceAtMost(width * 2 / 3)
                    previewWidth = width - editorWidth
                    previewHeight = height
                    editorX = previewWidth
                } else if (preview != null && !textEditor) {
                    editorHeight = 152.dp.roundToPx().coerceAtMost(height / 2)
                    previewHeight = height - editorHeight
                    editorY = previewHeight
                }
            }
        }
        val image = measurables[0].measure(Constraints.fixed(previewWidth, previewHeight))
        val input = measurables[1].measure(Constraints.fixed(editorWidth, editorHeight))
        layout(width, height) {
            image.place(0, 0)
            input.place(editorX, editorY)
        }
    }
}
