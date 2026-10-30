package app.logdate.feature.editor.ui.handwriting

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Clips scrolling foreground, leaving the scene's full-window background continuously painted. */
internal fun GraphicsLayerScope.clearEntryDock(
    windowTop: Float,
    dockTop: Float?,
    gap: Float,
    focusProgress: Float = 0f,
) {
    clip = dockTop != null && focusProgress < 1f
    if (clip) shape = EntryDockForegroundClip(dockTop!! - gap - windowTop, focusProgress.coerceIn(0f, 1f))
}

private data class EntryDockForegroundClip(
    val bottom: Float,
    val focusProgress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val end = bottom.coerceIn(0f, size.height)
        return Outline.Rectangle(Rect(0f, 0f, size.width, end + (size.height - end) * focusProgress))
    }
}
