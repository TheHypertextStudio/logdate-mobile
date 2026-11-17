@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.step

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min

/**
 * Where a step's words and actions lie, for a scene drawn around them.
 *
 * Everything is in pixels, in the scene's own coordinates: [size] is the room the scene has, which
 * is the whole window where the step is one column, and the header's side of it where a hinge or
 * a short window splits the step in two. [actions] is empty in that second case, because the
 * actions are somewhere the scene does not reach. [systemTop] is how far down from the top of the
 * scene the system draws its own bar over it, which is nothing where the scene starts below the bar.
 */
@Immutable
data class StepSceneBounds(
    val size: Size,
    val words: Rect,
    val actions: Rect,
    val systemTop: Float = 0f,
)

/**
 * Lays [step] out and, where the step has a [scene], the scene across it and in front of it, told
 * where the step's words and actions are.
 *
 * The scene is laid out in the same pass as the step. The step's words and actions each give off
 * a line for each of their edges, which travels up through whatever lays them out, so this can
 * read where they are as soon as the step is measured. Learning it from a position callback
 * instead would tell the scene a frame late, and anything that draws one frame, a screenshot or a
 * preview, would show the step with no scene at all.
 *
 * @param reachesActions whether the step's actions are inside [step], and so within the scene's reach.
 * @param fillsWindow whether this is the whole window, so that the system's bars are drawn over the scene.
 */
@Composable
internal fun StepWithScene(
    scene: (@Composable BoxScope.(StepSceneBounds) -> Unit)?,
    reachesActions: Boolean,
    fillsWindow: Boolean,
    modifier: Modifier = Modifier,
    step: @Composable BoxScope.() -> Unit,
) {
    if (scene == null) {
        Box(modifier, content = step)
        return
    }
    val safeDrawing = WindowInsets.safeDrawing
    SubcomposeLayout(modifier) { constraints ->
        val systemTop = if (fillsWindow) safeDrawing.getTop(this).toFloat() else 0f
        val body = subcompose(SceneSlot.Step) { Box(Modifier.fillMaxSize(), content = step) }.first().measure(constraints)
        val words = body.between(WordsTop, WordsBottom, WordsStart, WordsEnd)
        val actions = if (reachesActions) body.between(ActionsTop, ActionsBottom, ActionsStart, ActionsEnd) else null
        // A scene is drawn around the words, so a step that has not said where they are has none.
        val layer =
            words?.let {
                val bounds = StepSceneBounds(Size(body.width.toFloat(), body.height.toFloat()), it, actions ?: Rect.Zero, systemTop)
                subcompose(SceneSlot.Scene) { Box(Modifier.fillMaxSize()) { scene(bounds) } }
                    .first()
                    .measure(Constraints.fixed(body.width, body.height))
            }
        layout(body.width, body.height) {
            body.place(0, 0)
            layer?.place(0, 0)
        }
    }
}

/** Marks the step's words, so a scene can be told where they are. */
internal fun Modifier.stepWords(): Modifier = givesEdges(WordsTop, WordsBottom, WordsStart, WordsEnd)

/** Marks the step's actions, so a scene can be told where they are. */
internal fun Modifier.stepActions(): Modifier = givesEdges(ActionsTop, ActionsBottom, ActionsStart, ActionsEnd)

private fun Modifier.givesEdges(
    top: HorizontalAlignmentLine,
    bottom: HorizontalAlignmentLine,
    start: VerticalAlignmentLine,
    end: VerticalAlignmentLine,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(
            width = placeable.width,
            height = placeable.height,
            alignmentLines = mapOf(top to 0, bottom to placeable.height, start to 0, end to placeable.width),
        ) {
            placeable.place(0, 0)
        }
    }

/** The room between four edge lines this has been given, or nothing where it was not given them. */
private fun Placeable.between(
    top: HorizontalAlignmentLine,
    bottom: HorizontalAlignmentLine,
    start: VerticalAlignmentLine,
    end: VerticalAlignmentLine,
): Rect? {
    if (this[top] == AlignmentLine.Unspecified) return null
    return Rect(this[start].toFloat(), this[top].toFloat(), this[end].toFloat(), this[bottom].toFloat())
}

private enum class SceneSlot { Step, Scene }

private val WordsTop = HorizontalAlignmentLine(::min)
private val WordsBottom = HorizontalAlignmentLine(::max)
private val WordsStart = VerticalAlignmentLine(::min)
private val WordsEnd = VerticalAlignmentLine(::max)
private val ActionsTop = HorizontalAlignmentLine(::min)
private val ActionsBottom = HorizontalAlignmentLine(::max)
private val ActionsStart = VerticalAlignmentLine(::min)
private val ActionsEnd = VerticalAlignmentLine(::max)
