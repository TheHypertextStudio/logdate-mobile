package app.logdate.feature.editor.ui.handwriting

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.foldable.FoldablePosture
import org.jetbrains.skia.Image
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class EntryPagePreviewMotionTest(
    private val name: String,
    private val width: Int,
    private val height: Int,
) {
    @Test
    fun focusAndReturn() =
        runDesktopComposeUiTest(width = width, height = height) {
            val posture = if (name == "book") FoldablePosture.Book else FoldablePosture.Standard
            val page = EntryPageOverviewState(false)
            setContent { EntryPageStudy(fold = foldFixture(posture, width, height), overviewStateOverride = page) }
            waitForIdle()
            val previewTop = onNodeWithTag("handwriting_preview").getUnclippedBoundsInRoot().top.value
            runOnIdle {
                kotlinx.coroutines.runBlocking {
                    page.scroll.scrollTo(
                        (page.scroll.value + previewTop - 160).toInt().coerceAtLeast(0),
                    )
                }
            }
            val output = File("build/reports/entry-page-motion/$name").apply { mkdirs() }
            var index = 0

            fun frames(count: Int) {
                repeat(count) {
                    mainClock.advanceTimeBy(40)
                    File(output, "frame-${(index++).toString().padStart(4, '0')}.png").writeBytes(
                        Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes,
                    )
                }
            }
            mainClock.autoAdvance = false
            frames(8)
            onNodeWithTag("handwriting_preview").performClick()
            frames(16)
            frames(10)
            onNodeWithContentDescription("Return to entry").performClick()
            frames(16)
            frames(8)
            onNodeWithTag("entry_mode_overview").assertExists()
            mainClock.autoAdvance = true
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(arrayOf("phone", 360, 800), arrayOf("book", 1000, 800))
    }
}
