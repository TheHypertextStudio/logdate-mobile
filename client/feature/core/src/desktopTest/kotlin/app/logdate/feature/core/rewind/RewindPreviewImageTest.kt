package app.logdate.feature.core.rewind

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.rewind.ui.detail.ImageNotePanel
import app.logdate.feature.rewind.ui.detail.LocalRewindPreviewPainters
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RewindPreviewImageTest {
    @Test
    fun populatedInspectionShowsItsSuppliedImageWithoutAReadinessCallback() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalRewindPreviewPainters provides mapOf("fixture://photo" to ColorPainter(Color.Green)),
                ) {
                    LogDateTheme { ImageNotePanel("fixture://photo", "At the lake", "May 4", memoryId = "fixture") }
                }
            }
            assertEquals(
                "Memory loaded",
                onNodeWithTag("rewind_memory_fixture").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
            val pixels = onRoot().captureToImage().toPixelMap()
            var imagePixels = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (pixels[x, y].toArgb() == Color.Green.toArgb()) imagePixels++
                }
            }
            assertTrue(imagePixels > 1000, "The populated fixture must paint its supplied image")
        }

    @Test
    fun fixtureDoesNotMarkAnotherImageLoaded() = assertNotLoaded(inspection = true, uri = "fixture://other")

    @Test
    fun runtimeStillRequiresItsRealImageToLoad() = assertNotLoaded(inspection = false, uri = "fixture://photo")

    private fun assertNotLoaded(
        inspection: Boolean,
        uri: String,
    ) = runDesktopComposeUiTest(width = 411, height = 891) {
        setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides inspection,
                LocalRewindPreviewPainters provides mapOf("fixture://photo" to ColorPainter(Color.Green)),
            ) {
                LogDateTheme { ImageNotePanel(uri, "At the lake", "May 4", memoryId = "fixture") }
            }
        }
        assertNotEquals(
            "Memory loaded",
            onNodeWithTag("rewind_memory_fixture").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }
}
