package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.feature.core.settings.updates.AppUpdateUiState
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeveloperSettingsVisibilityTest {
    @Test
    fun privacyDoesNotExposeDeveloperControls() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme(darkTheme = false) {
                    PrivacySettingsContent(onBack = {}, onSetBiometricsEnabled = {}, isBiometricsEnabled = false)
                }
            }
            onNodeWithText("Sync Diagnostics").assertDoesNotExist()
            onNodeWithText("Share diagnostic ZIP").assertDoesNotExist()
            onNodeWithText("Detailed sync history").assertDoesNotExist()
            onNodeWithText("App Security").assertExists()
            saveScreenshot("privacy-phone", onRoot().captureToImage())
        }

    @Test
    fun developerToolsRequireDeliberateUnlock() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var opens = 0
            val enabled = mutableStateOf(false)
            setContent {
                LogDateTheme(darkTheme = false) {
                    AdvancedSettingsContent(
                        onBack = {},
                        appUpdateUiState = AppUpdateUiState(currentVersionName = "1.2.3"),
                        onCheckForAppUpdates = {},
                        onCompleteAppUpdate = {},
                        onNavigateToDeveloperTools = { opens++ },
                        developerModeEnabled = enabled.value,
                        onSetDeveloperModeEnabled = { enabled.value = it },
                    )
                }
            }
            onNodeWithText("Developer Tools").assertDoesNotExist()
            repeat(6) { onNodeWithText("App version: 1.2.3").performClick() }
            onNodeWithText("Developer Tools").assertDoesNotExist()
            onNodeWithText("App version: 1.2.3").performClick()
            onNodeWithText("Developer Tools").assertExists().performClick()
            assertEquals(1, opens)
            saveScreenshot("about-unlocked-phone", onRoot().captureToImage())
            onNodeWithText("Hide developer mode").performClick()
            onNodeWithText("Developer Tools").assertDoesNotExist()
            onNodeWithText("Hide developer mode").assertDoesNotExist()
            onNodeWithText("Labs").assertExists()
            repeat(6) { onNodeWithText("App version: 1.2.3").performClick() }
            onNodeWithText("Developer Tools").assertDoesNotExist()
            onNodeWithText("App version: 1.2.3").performClick()
            onNodeWithText("Developer Tools").assertExists()
        }

    @Test
    fun savedDeveloperModeIsVisibleWithoutVersionTapsAtLargeText() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var hidden = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    LogDateTheme(darkTheme = true) {
                        AdvancedSettingsContent(
                            onBack = {},
                            appUpdateUiState = AppUpdateUiState(currentVersionName = "1.2.3"),
                            onCheckForAppUpdates = {},
                            onCompleteAppUpdate = {},
                            developerModeEnabled = true,
                            onSetDeveloperModeEnabled = { hidden = !it },
                            developerModeSaveFailed = true,
                        )
                    }
                }
            }
            onNodeWithText("Developer Tools").assertExists()
            onNode(hasScrollAction()).performScrollToNode(hasText("Hide developer mode"))
            onNodeWithText("Hide developer mode").performClick()
            assertEquals(true, hidden)
            onNode(hasScrollAction()).performScrollToNode(hasText("Couldn’t save developer mode. Please try again."))
            onNodeWithText("Couldn’t save developer mode. Please try again.").assertExists()
            saveScreenshot("about-developer-large-dark", onRoot().captureToImage())
        }

    @Test
    fun developerToolsGroupWorkingActionsOnTablet() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var exports = 0
            var verboseEnabled = false
            setContent {
                LogDateTheme(darkTheme = false) {
                    DeveloperToolsContent(
                        onBack = {},
                        state = LocalDiagnosticsState(eventCount = 3),
                        onPreview = {},
                        onExport = { exports++ },
                        onClear = {},
                        onSetVerboseEnabled = { verboseEnabled = it },
                        locationContent = {
                            DeveloperLocationSection(
                                settings = LocationTrackingSettings(),
                                onToggleServerAssist = {},
                                onSetDefaultLocation = {},
                            )
                        },
                    )
                }
            }
            onNodeWithText("Share diagnostic ZIP").performClick()
            onNodeWithText("Detailed sync history").performClick()
            assertEquals(1, exports)
            assertEquals(true, verboseEnabled)
            saveScreenshot("developer-tools-tablet", onRoot().captureToImage())
        }

    @Test
    fun privacyKeepsUserControlsAtLargeTextOnTablet() =
        runDesktopComposeUiTest(width = 1200, height = 900) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    LogDateTheme(darkTheme = true) {
                        PrivacySettingsContent(onBack = {}, onSetBiometricsEnabled = {}, isBiometricsEnabled = false)
                    }
                }
            }
            onNodeWithText("Sync Diagnostics").assertDoesNotExist()
            onNode(hasScrollAction()).performScrollToNode(hasText("Location Settings"))
            onNodeWithText("Location Settings").assertExists()
            saveScreenshot("privacy-tablet-large-dark", onRoot().captureToImage())
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
