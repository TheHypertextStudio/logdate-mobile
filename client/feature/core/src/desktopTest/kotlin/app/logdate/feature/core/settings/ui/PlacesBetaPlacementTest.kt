package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.feature.core.settings.ui.labs.LabsFlag
import app.logdate.feature.core.settings.ui.labs.LabsSettingsContent
import app.logdate.feature.core.settings.ui.labs.LabsUiState
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
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlacesBetaPlacementTest {
    @Test
    fun publicBetaIsReachableOnPhone() = checkLabs(411, 891, "labs-phone")

    @Test
    fun publicBetaIsReachableInBookPosture() = checkLabs(1000, 800, "labs-book", book = true)

    @Test
    fun publicBetaIsReachableOnLandscapeTablet() = checkLabs(1280, 800, "labs-tablet-landscape")

    @Test
    fun publicBetaIsReachableAtLargeTextInDarkRtl() = checkLabs(411, 891, "labs-phone-large-dark-rtl", largeText = true)

    @Test
    fun overviewDoesNotOfferTheBetaToggle() =
        runDesktopComposeUiTest(width = 411, height = 2000) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    SettingsOverviewContent(
                        onBack = {},
                        onNavigateToProfile = {},
                        onNavigateToAccount = {},
                        onNavigateToDevices = {},
                        onNavigateToReset = {},
                        onNavigateToLocation = {},
                        onNavigateToPrivacy = {},
                        onNavigateToMemories = {},
                        onNavigateToSync = {},
                        onNavigateToExport = {},
                        userProfile = UserProfile("Alex Rivera", "alex", true),
                    )
                }
            }
            onNode(hasScrollAction()).performScrollToNode(hasText("Your library"))
            onNodeWithText("Places & location history · Beta").assertDoesNotExist()
            onNode(hasScrollAction()).performScrollToNode(hasText("About LogDate"))
            onNodeWithText("About LogDate").assertIsDisplayed()
        }

    private fun checkLabs(
        width: Int,
        height: Int,
        name: String,
        book: Boolean = false,
        largeText: Boolean = false,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        var requested: Boolean? = null
        val posture =
            if (book) {
                FoldableLayoutInfo(
                    isFoldable = true,
                    posture = FoldablePosture.Book,
                    hinge =
                        FoldableHingeInfo(
                            orientation = FoldableHingeOrientation.Vertical,
                            state = FoldableHingeState.HalfOpened,
                            occlusionType = FoldableOcclusionType.Full,
                            bounds = FoldableHingeBounds(490.dp, 0.dp, 510.dp, height.dp, 20.dp, height.dp),
                            isSeparating = true,
                        ),
                )
            } else {
                FoldableLayoutInfo()
            }
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (largeText) 2f else 1f),
                LocalLayoutDirection provides if (largeText) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                provideFoldableLayoutInfo(posture) {
                    LogDateTheme(dynamicColor = false, darkTheme = largeText) {
                        LabsSettingsContent(
                            uiState = LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, false))),
                            onBack = {},
                            onSetEnabled = { _, _ -> },
                            betaSettingsContent = { PlacesBetaSettingsItem(PlacesBetaSettingsState.Ready(true)) { requested = it } },
                        )
                    }
                }
            }
        }
        onNodeWithText("Places & location history · Beta").performScrollTo().assertIsDisplayed()
        onNodeWithText("Explore where you’ve been and revisit the memories connected to each place.").assertIsDisplayed()
        onNode(isToggleable() and hasText("Places & location history · Beta")).assertIsOn().performClick()
        assertEquals(false, requested)
        onNodeWithText("Redesigned watch app").assertDoesNotExist()
        saveScreenshot(name, onRoot().captureToImage())
    }

    private fun saveScreenshot(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_SETTINGS_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
