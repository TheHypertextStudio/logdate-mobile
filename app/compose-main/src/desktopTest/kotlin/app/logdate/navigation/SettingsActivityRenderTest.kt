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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.PrivacySettingsContent
import app.logdate.feature.core.settings.ui.SettingsCategoryNavigation
import app.logdate.navigation.scenes.SettingsActivityLayout
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldablePaneBounds
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsActivityRenderTest {
    @Test
    fun populatedWideSettings() = verify("settings-wide", 1280, 900, expectsCategories = true)

    @Test
    fun populatedCompactSettings() = verify("settings-phone", 411, 891)

    @Test
    fun compactLandscapeKeepsSectionReadable() = verify("settings-landscape", 891, 411)

    @Test
    fun largeTextUsesSingleReadableSection() = verify("settings-large-text", 1280, 900, fontScale = 2f)

    @Test
    fun rightToLeftPlacesCategoriesAtStart() = verify("settings-rtl-dark", 1280, 900, expectsCategories = true, rtl = true, dark = true)

    @Test
    fun bookKeepsContentOutsideHinge() =
        verify(
            "settings-book",
            1000,
            900,
            expectsCategories = true,
            split =
                FoldableSplitLayout.Vertical(
                    pane(0, 0, 490, 900),
                    pane(510, 0, 1000, 900),
                    FoldableHingeBounds(490.dp, 0.dp, 510.dp, 900.dp, 20.dp, 900.dp),
                ),
        )

    @Test
    fun narrowRtlBookKeepsPhysicalPaneWithinHingeBoundary() =
        verify(
            "settings-narrow-rtl-book",
            800,
            900,
            rtl = true,
            split =
                FoldableSplitLayout.Vertical(
                    pane(0, 0, 500, 900),
                    pane(520, 0, 800, 900),
                    FoldableHingeBounds(500.dp, 0.dp, 520.dp, 900.dp, 20.dp, 900.dp),
                ),
        )

    @Test
    fun tabletopUsesOneRegionOutsideHinge() =
        verify(
            "settings-tabletop",
            1000,
            900,
            split =
                FoldableSplitLayout.Horizontal(
                    pane(0, 0, 1000, 440),
                    pane(0, 460, 1000, 900),
                    FoldableHingeBounds(0.dp, 440.dp, 1000.dp, 460.dp, 1000.dp, 20.dp),
                ),
        )

    private fun verify(
        name: String,
        width: Int,
        height: Int,
        expectsCategories: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        dark: Boolean = false,
        split: FoldableSplitLayout = FoldableSplitLayout.None,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            LogDateTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    SettingsActivityLayout(
                        supportsDualPane = true,
                        foldableSplitLayout = split,
                        categories = {
                            SettingsCategoryNavigation(
                                selectedSection = "privacy",
                                onNavigateTo = {},
                                onBack = {},
                                onNavigateToProfile = {},
                            )
                        },
                        detail = {
                            Box(Modifier.fillMaxSize().testTag("settings_render_detail")) {
                                PrivacySettingsContent(
                                    onBack = {},
                                    onSetBiometricsEnabled = {},
                                    isBiometricsEnabled = true,
                                    showSystemSearchVisibilityToggle = true,
                                )
                            }
                        },
                    )
                }
            }
        }
        onAllNodesWithTag("settings_categories").assertCountEquals(if (expectsCategories) 1 else 0)
        onNodeWithText("Require biometrics").assertExists()
        val detailBounds = onNodeWithTag("settings_render_detail").fetchSemanticsNode().boundsInRoot
        if (expectsCategories) {
            val categoryBounds = onNodeWithTag("settings_categories").fetchSemanticsNode().boundsInRoot
            assertTrue(if (rtl) detailBounds.right <= categoryBounds.left else categoryBounds.right <= detailBounds.left)
        }
        when (split) {
            is FoldableSplitLayout.Vertical -> {
                assertTrue(
                    detailBounds.left >= split.hingeBounds.right.value || detailBounds.right <= split.hingeBounds.left.value,
                )
            }

            is FoldableSplitLayout.Horizontal -> {
                assertTrue(
                    detailBounds.bottom <= split.hingeBounds.top.value || detailBounds.top >= split.hingeBounds.bottom.value,
                )
            }

            FoldableSplitLayout.None -> {
                Unit
            }
        }
        val directory = System.getenv("LOGDATE_ACTIVITY_SCREENSHOT_DIR")
        if (directory != null) {
            val image = onRoot().captureToImage()
            val pixels = image.toPixelMap()
            val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
            }
            val file = File(directory, "$name.png")
            file.parentFile.mkdirs()
            ImageIO.write(output, "png", file)
        }
        if (!expectsCategories) {
            onNode(hasScrollAction()).performScrollToNode(hasText("Location Settings"))
            onNodeWithText("Location Settings").assertIsDisplayed()
        }
    }

    private fun pane(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = FoldablePaneBounds(left.dp, top.dp, right.dp, bottom.dp, (right - left).dp, (bottom - top).dp)
}
