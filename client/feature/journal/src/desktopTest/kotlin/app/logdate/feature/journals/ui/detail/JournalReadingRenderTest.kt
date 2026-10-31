package app.logdate.feature.journals.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class JournalReadingRenderTest {
    @Test
    fun populatedJournalReadingAtPhoneTabletAndRelatedPaneSizes() {
        render("phone", 411, 891)
        render("tablet", 960, 800)
        render("related-pane", 480, 800)
    }

    @Test
    fun compactLandscapeDarkRtlAndLargeText() {
        render("compact", 320, 640)
        render("landscape", 891, 411)
        render("dark", 960, 800, dark = true)
        render("rtl", 960, 800, rtl = true)
        render("large-text", 411, 891, fontScale = 2f)
        render("narrow-rtl-book", 960, 800, rtl = true, foldable = book)
        render("tabletop", 960, 900, foldable = tabletop)
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        foldable: FoldableLayoutInfo = FoldableLayoutInfo(),
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            LogDateTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    provideFoldableLayoutInfo(foldable) {
                        JournalDetailScreenContent(JournalReadingActivityTest.state, onGoBack = {})
                    }
                }
            }
        }
        onNodeWithContentDescription("Back to journals").assertIsDisplayed()
        onNodeWithContentDescription("Add to journal").assertIsDisplayed()
        save(name, onRoot().captureToImage())
    }

    private fun save(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_READING_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }

    private val book =
        FoldableLayoutInfo(
            true,
            FoldablePosture.Book,
            FoldableHingeInfo(
                FoldableHingeOrientation.Vertical,
                FoldableHingeState.HalfOpened,
                FoldableOcclusionType.Full,
                FoldableHingeBounds(450.dp, 0.dp, 474.dp, 800.dp, 24.dp, 800.dp),
                true,
            ),
        )
    private val tabletop =
        FoldableLayoutInfo(
            true,
            FoldablePosture.Tabletop,
            FoldableHingeInfo(
                FoldableHingeOrientation.Horizontal,
                FoldableHingeState.HalfOpened,
                FoldableOcclusionType.Full,
                FoldableHingeBounds(0.dp, 438.dp, 960.dp, 462.dp, 960.dp, 24.dp),
                true,
            ),
        )
}
