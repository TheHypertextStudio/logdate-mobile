package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.SettingsCategoryNavigation
import app.logdate.navigation.scenes.SettingsActivityLayout
import app.logdate.screenshots.shared.WatchCompanionAcceptanceContent
import app.logdate.screenshots.shared.WatchCompanionAcceptanceScene
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldablePaneBounds
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Populated production contents inside the actual adaptive Settings activity. */
@OptIn(ExperimentalTestApi::class)
class WatchCompanionVisualAcceptanceTest {
    @Test fun compact() = inspect("compact", 320, 568)

    @Test fun phone() = inspect("phone", 411, 891)

    @Test fun landscape() = inspect("landscape", 891, 411)

    @Test fun tablet() = inspect("tablet", 1280, 800)

    @Test fun tabletPortrait() = inspect("tablet-portrait", 800, 1280)

    @Test fun dark() = inspect("dark", 411, 891, dark = true)

    @Test fun rightToLeft() = inspect("rtl", 411, 891, rtl = true)

    @Test fun largeText() = inspect("large-text", 411, 891, fontScale = 2f)

    @Test fun compactLargeText() = inspect("compact-large-text", 320, 568, fontScale = 2f)

    @Test fun book() =
        inspect(
            "book",
            1000,
            900,
            split =
                FoldableSplitLayout.Vertical(
                    pane(0, 0, 490, 900),
                    pane(510, 0, 1000, 900),
                    FoldableHingeBounds(490.dp, 0.dp, 510.dp, 900.dp, 20.dp, 900.dp),
                ),
        )

    @Test fun tabletop() =
        inspect(
            "tabletop",
            1000,
            900,
            split =
                FoldableSplitLayout.Horizontal(
                    pane(0, 0, 1000, 440),
                    pane(0, 460, 1000, 900),
                    FoldableHingeBounds(0.dp, 440.dp, 1000.dp, 460.dp, 1000.dp, 20.dp),
                ),
        )

    private fun inspect(
        variant: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        split: FoldableSplitLayout = FoldableSplitLayout.None,
    ) {
        WatchCompanionAcceptanceScene.entries.forEach { scene ->
            runDesktopComposeUiTest(width = width, height = height) {
                setContent {
                    LogDateTheme(darkTheme = dark, dynamicColor = false) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(LocalDensity.current.density, fontScale),
                            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        ) {
                            SettingsActivityLayout(
                                supportsDualPane = true,
                                foldableSplitLayout = split,
                                categories = {
                                    SettingsCategoryNavigation("watch", {}, {}, {})
                                },
                                detail = {
                                    Box(Modifier.fillMaxSize().testTag("companion_visual_detail")) {
                                        WatchCompanionAcceptanceContent(scene)
                                    }
                                },
                            )
                        }
                    }
                }
                val bounds = onNodeWithTag("companion_visual_detail").fetchSemanticsNode().boundsInRoot
                when (split) {
                    is FoldableSplitLayout.Vertical -> assertTrue(bounds.right <= 490f || bounds.left >= 510f)
                    is FoldableSplitLayout.Horizontal -> assertTrue(bounds.bottom <= 440f || bounds.top >= 460f)
                    FoldableSplitLayout.None -> Unit
                }
                if (scene == WatchCompanionAcceptanceScene.COMPANION) {
                    onNodeWithText("Pixel Watch").assertIsDisplayed()
                    // TileRenderer is Android-only; desktop must not substitute a fabricated watch face.
                    onNodeWithTag("watch_today_preview").assertDoesNotExist()
                    if (variant == "compact-large-text" || variant == "phone") {
                        val icon =
                            onNodeWithTag("watch_identity_icon", useUnmergedTree = true)
                                .fetchSemanticsNode()
                                .boundsInRoot
                        val status = onNodeWithText("Connected").fetchSemanticsNode().boundsInRoot
                        if (variant == "compact-large-text") {
                            assertTrue(status.top >= icon.bottom, "Large watch status needs its own row")
                        } else {
                            assertTrue(status.left >= icon.right, "Phone watch status should share the icon row")
                        }
                    }
                }
                saveImage("${scene.name.lowercase()}-$variant-top", onRoot().captureToImage())
                val terminal =
                    when (scene) {
                        WatchCompanionAcceptanceScene.COMPANION -> "Remove watch"
                        WatchCompanionAcceptanceScene.DIAGNOSTICS -> "Watch notifications"
                        WatchCompanionAcceptanceScene.REMOVE_REVIEW -> "Remove watch"
                        WatchCompanionAcceptanceScene.REMOVED_WITH_UNSENT -> "2 moments that couldn't sync were cleared with it."
                        WatchCompanionAcceptanceScene.REMOVE_FAILED -> "Check again"
                    }
                val actionable =
                    scene != WatchCompanionAcceptanceScene.DIAGNOSTICS && scene != WatchCompanionAcceptanceScene.REMOVED_WITH_UNSENT
                val terminalMatcher = if (actionable) hasText(terminal) and hasClickAction() else hasText(terminal)
                onNode(
                    hasScrollAction() and hasAnyAncestor(hasTestTag("companion_visual_detail")),
                ).performScrollToNode(terminalMatcher)
                onNode(terminalMatcher).assertIsDisplayed()
                if (actionable) onNode(terminalMatcher).assertHasClickAction()
                val target = onNode(terminalMatcher).fetchSemanticsNode().boundsInRoot
                assertTrue(target.left >= bounds.left && target.right <= bounds.right, "$terminal overflows $variant")
                saveImage("${scene.name.lowercase()}-$variant-bottom", onRoot().captureToImage())
            }
        }
    }

    private fun saveImage(
        name: String,
        image: androidx.compose.ui.graphics.ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_COMPANION_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }

    private fun pane(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = FoldablePaneBounds(left.dp, top.dp, right.dp, bottom.dp, (right - left).dp, (bottom - top).dp)
}
