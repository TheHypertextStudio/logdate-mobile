package app.logdate.feature.editor.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/** Optional foreground boundary within a scrolling reading pane, in window coordinates. */
internal val LocalEntryContentBottom = compositionLocalOf<Float?> { null }

/** Clip ink and controls, after their owning surface has painted behind system UI. */
@Composable
internal fun Modifier.clipEntryContentToSafeWindow(): Modifier {
    val insets = LocalEntryContentInsets.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val contentBottom =
        minOf(
            window.height - insets.getBottom(density).toFloat(),
            LocalEntryContentBottom.current ?: Float.POSITIVE_INFINITY,
        )
    val origin = remember { mutableStateOf(Offset.Zero) }
    val measured = remember { mutableStateOf(IntSize.Zero) }
    return onGloballyPositioned {
        origin.value = it.positionInWindow()
        measured.value = it.size
    }.semantics {
        val position = origin.value
        if (position.y + measured.value.height <= insets.getTop(density) ||
            position.y >= contentBottom ||
            position.x + measured.value.width <= insets.getLeft(density, direction) ||
            position.x >= window.width - insets.getRight(density, direction)
        ) {
            hideFromAccessibility()
        }
    }.graphicsLayer {
        // Read position in the layer phase: scrolling invalidates paint without remounting
        // content or recomposing the block on each frame.
        val position = origin.value
        val left = (insets.getLeft(density, direction) - position.x).coerceIn(0f, size.width)
        val top = (insets.getTop(density) - position.y).coerceIn(0f, size.height)
        val right = (window.width - insets.getRight(density, direction) - position.x).coerceIn(left, size.width)
        val bottom = (contentBottom - position.y).coerceIn(top, size.height)
        shape = SafeContentClip(Rect(left, top, right, bottom))
        clip = true
    }
}

/** A real layer clip protects painting and pointer hit testing without changing layout. */
private class SafeContentClip(
    private val bounds: Rect,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(bounds)
}
