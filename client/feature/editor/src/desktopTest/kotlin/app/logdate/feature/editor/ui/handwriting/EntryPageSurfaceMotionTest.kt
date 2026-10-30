package app.logdate.feature.editor.ui.handwriting

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EntryPageSurfaceMotionTest {
    @Test
    fun `paper grows from the block without recoloring the workspace`() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val page = EntryPageOverviewState(false)
            setContent { EntryPageStudy(overviewStateOverride = page) }
            waitForIdle()
            val previewTop = onNodeWithTag("handwriting_preview").getUnclippedBoundsInRoot().top.value
            runOnIdle {
                kotlinx.coroutines.runBlocking {
                    page.scroll.scrollTo(
                        (page.scroll.value + previewTop - 160).toInt().coerceAtLeast(0),
                    )
                }
            }

            fun corner(): Color = onRoot().captureToImage().toPixelMap()[359, 0]

            fun distance(
                a: Color,
                b: Color,
            ): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
            val workspace = corner()
            mainClock.autoAdvance = false
            onNodeWithTag("handwriting_preview").performClick()
            mainClock.advanceTimeBy(64)
            assertTrue(distance(workspace, corner()) < 0.03f, "Space outside the expanding surface must retain its workspace color")
            mainClock.advanceTimeBy(1000)
            assertTrue(distance(workspace, corner()) > 0.1f, "Settled focused paper must reach the window edges")
            onNodeWithContentDescription("Return to entry").performClick()
            mainClock.advanceTimeBy(1000)
            assertTrue(distance(workspace, corner()) < 0.03f, "Returning must restore the original workspace")
            mainClock.autoAdvance = true
        }
}
