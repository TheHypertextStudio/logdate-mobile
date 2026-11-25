package app.logdate.feature.core.settings.ui.watch

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPage
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPreferences
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionSections
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.watch.WatchSetupProvider
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WatchCompanionPageTest {
    @Test fun `setup action stays reachable at compact width and large text`() =
        runDesktopComposeUiTest(width = 320, height = 568) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        WatchCompanionPage(
                            status = WatchStatus.NotInstalled("Pixel Watch"),
                            requestFailure = null,
                            onBack = {},
                            onAssociate = {},
                            onInstall = {},
                            onReminders = {},
                            onTiles = {},
                            onMicrophone = {},
                            onNotifications = {},
                            onDiagnostics = {},
                            onRemove = {},
                        )
                    }
                }
            }
            onNodeWithText("Install on watch").performScrollTo()
            val action = onNodeWithText("Install on watch").fetchSemanticsNode().boundsInRoot
            assertTrue(action.left >= 0f && action.right <= 320f, "Setup action overflows compact width: $action")
            saveScreenshot(onRoot().captureToImage(), "watch-setup-compact-large-text.png")
        }

    @Test fun `linking a watch shows progress, then the reason and one retry when it fails`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var step by mutableStateOf(WatchSetupStep.Linking("Pixel Watch"))
            var retries = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    WatchCompanionPage(
                        status = WatchStatus.SettingUp(step, "node"),
                        requestFailure = null,
                        onBack = {},
                        onAssociate = { retries++ },
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                    )
                }
            }
            onNodeWithText("Connecting to Pixel Watch").assertExists()
            onNodeWithText("Connect").assertDoesNotExist()
            onNodeWithText("Try again").assertDoesNotExist()

            step = WatchSetupStep.Linking("Pixel Watch", WatchLinkProblem.ANOTHER_ACCOUNT)
            waitForIdle()
            onNodeWithText("Pixel Watch uses another account").assertExists()
            onNodeWithText("Try again").performClick()
            assertEquals(1, retries)

            step = WatchSetupStep.Linking("Pixel Watch", WatchLinkProblem.REMOVED)
            waitForIdle()
            onNodeWithText("Set up again").performClick()
            assertEquals(2, retries)
        }

    @Test fun `without a paired watch the page offers setup and no watch settings`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    WatchCompanionPage(
                        status = WatchStatus.NoWatch,
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        setupLauncher = { true },
                    )
                }
            }
            onNodeWithText("LogDate on your wrist").assertExists()
            onNodeWithText("Record voice notes, mark moments, and replay memories on a Wear OS watch", substring = true).assertExists()
            onNodeWithText("Set up watch").assertExists()
            onNodeWithText("Set up watch").assertIsEnabled()
            val art = onNodeWithTag("watch_setup_art").fetchSemanticsNode().boundsInRoot
            val title = onNodeWithText("LogDate on your wrist").fetchSemanticsNode().boundsInRoot
            val action = onNodeWithText("Set up watch").fetchSemanticsNode().boundsInRoot
            assertTrue(title.top < 200f, "The words should start at the top of the pane: $title")
            assertTrue(title.bottom <= art.top, "The picture should sit under the words: $art $title")
            assertTrue(art.bottom <= action.top, "The action should sit under the picture: $art $action")
            assertTrue(art.height > 300f, "The picture should use the room between the words and the action: $art")
            assertTrue(action.bottom > 800f, "The action should end the pane: $action")
            onNodeWithText("Check connection").assertDoesNotExist()
            onNodeWithText("Timeline history").assertDoesNotExist()
            onNodeWithText("Reminders").assertDoesNotExist()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-no-watch.png")
        }

    @Test fun `wide unpaired setup keeps the words, picture and action in one column`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.NoWatch,
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        setupLauncher = { true },
                    )
                }
            }
            val art = onNodeWithTag("watch_setup_art").fetchSemanticsNode().boundsInRoot
            val title = onNodeWithText("LogDate on your wrist").fetchSemanticsNode().boundsInRoot
            val action = onNodeWithText("Set up watch").fetchSemanticsNode().boundsInRoot
            assertTrue(title.bottom <= art.top, "The picture should sit under the words: $art $title")
            assertTrue(art.bottom <= action.top, "The action should sit under the picture: $art $action")
            assertTrue(action.width <= 560f, "A wide window should not stretch the step across itself: $action")
            assertTrue(art.height > 300f, "The picture should use the room between the words and the action: $art")
            onNodeWithText("Check connection").assertDoesNotExist()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-no-watch-wide.png")
        }

    @Test fun `setup opens the selected watch companion path`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var selected: WatchSetupProvider? = null
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.NoWatch,
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        setupLauncher = { provider ->
                            selected = provider
                            true
                        },
                    )
                }
            }
            onNodeWithText("Set up watch").performClick()
            onNodeWithText("Google Pixel Watch").performClick()
            assertEquals(WatchSetupProvider.PIXEL, selected)
        }

    @Test fun `watch install confirms a sent Play request without extra setup copy`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var installRequests = 0
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.NotInstalled("Pixel Watch"),
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = { installRequests++ },
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        installRequestSent = true,
                    )
                }
            }
            onNodeWithText("Install on watch").performClick()
            assertEquals(1, installRequests)
            onNodeWithText("Check your watch", substring = true).assertExists()
            onNodeWithText("Already installed", substring = true).assertDoesNotExist()
            onNodeWithText("Check connection").assertDoesNotExist()
            onNodeWithText("Timeline history").assertDoesNotExist()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-install.png")
        }

    @Test fun `wide companion keeps watch identity beside settings`() =
        runDesktopComposeUiTest(width = 1100, height = 900) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.Ready("Pixel Watch", true),
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        preferences = { captureActions ->
                            WatchCompanionSections(
                                WatchCompanionPreferences(),
                                false,
                                false,
                                {},
                                {},
                                {},
                                {},
                                {},
                                captureActions = captureActions,
                            )
                        },
                    )
                }
            }
            val watch = onNodeWithText("Pixel Watch").fetchSemanticsNode().boundsInRoot
            val settings = onNodeWithText("Timeline history").fetchSemanticsNode().boundsInRoot
            val title = onNodeWithText("Watch").fetchSemanticsNode().boundsInRoot
            val leadingSection = onNodeWithText("Connection").fetchSemanticsNode().boundsInRoot
            saveScreenshot(onRoot().captureToImage(), "watch-companion-page-tablet.png")
            assertTrue(settings.left > watch.right, "Watch identity $watch and settings $settings should occupy separate panes")
            assertTrue(abs(title.left - leadingSection.left) <= 1f, "Settings title $title should align with content $leadingSection")
        }

    @Test fun `wide companion renders cleanly with onLibrary`() =
        runDesktopComposeUiTest(width = 1100, height = 900) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.Ready("Pixel Watch", true),
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        onLibrary = {},
                    )
                }
            }
            onNodeWithText("Timeline & audio").assertExists()
            onNodeWithText("On your watch").assertExists()
        }

    @Test fun `companion destinations are actionable through shared settings rows`() =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            val actions = mutableListOf<String>()
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        WatchStatus.Ready("Pixel Watch", true),
                        null,
                        {},
                        {},
                        {},
                        onReminders = { actions += "reminders" },
                        onTiles = { actions += "tiles" },
                        onMicrophone = { actions += "microphone" },
                        onNotifications = { actions += "notifications" },
                        onDiagnostics = { actions += "diagnostics" },
                        onRemove = { actions += "remove" },
                        preferences = { captureActions ->
                            WatchCompanionSections(
                                WatchCompanionPreferences(),
                                false,
                                false,
                                {},
                                {},
                                {},
                                {},
                                {},
                                captureActions = captureActions,
                            )
                        },
                    )
                }
            }
            saveScreenshot(onRoot().captureToImage())
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Reminders"))
            onNodeWithText("Reminders").performClick()
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Tiles & complications"))
            onNodeWithText("Tiles & complications").performClick()
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Microphone"))
            onNodeWithText("Microphone").performClick()
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Check connection"))
            onNodeWithText("Check connection").performClick()
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Remove watch"))
            onNodeWithText("Remove watch").performClick()
            assertEquals(listOf("reminders", "tiles", "microphone", "diagnostics", "remove"), actions)
        }

    @Test
    fun `unknown installation offers an explicit targeted confirmation before Play`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val confirmed = mutableListOf<String>()
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.Unknown("Pixel Watch", "watch-a"),
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        onConfirmMissing = { confirmed += it },
                        onOpenOnWatch = {},
                    )
                }
            }
            onNodeWithText("Install on watch").assertDoesNotExist()
            onNodeWithText("Pixel Watch connected to this phone").assertExists()
            onNodeWithText("Open LogDate on watch").assertExists()
            onNodeWithText("Connection details").assertExists()
            onNodeWithText("Install from Google Play").performClick()
            assertTrue(confirmed.isEmpty())
            onNodeWithText("Install LogDate on this watch?").assertExists()
            onNodeWithText("Install on watch").performClick()
            assertEquals(listOf("watch-a"), confirmed)
            saveScreenshot(onRoot().captureToImage(), "watch-connection-unknown-phone.png")
        }

    @Test
    fun `known installed unresponsive watch never instructs installation at any width`() {
        for ((width, height) in listOf(411 to 891, 1000 to 800, 1280 to 800)) {
            runDesktopComposeUiTest(width = width, height = height) {
                var retries = 0
                setContent {
                    LogDateTheme(dynamicColor = false) {
                        WatchCompanionPage(
                            status = WatchStatus.Unknown("Pixel Watch", "watch-a", knownInstalled = true),
                            requestFailure = WatchRequestFailure.WATCH_DID_NOT_RESPOND,
                            onBack = {},
                            onAssociate = {},
                            onInstall = {},
                            onReminders = {},
                            onTiles = {},
                            onMicrophone = {},
                            onNotifications = {},
                            onDiagnostics = {},
                            onRemove = {},
                            onRetry = { retries++ },
                        )
                    }
                }
                onNodeWithText("Install from Google Play").assertDoesNotExist()
                onNodeWithText("Install on watch").assertDoesNotExist()
                onNodeWithText("Install on Watch").assertDoesNotExist()
                onNodeWithTag("watch_setup_art").assertDoesNotExist()
                onNodeWithText("Microphone").assertDoesNotExist()
                onNodeWithText("Remove watch").assertDoesNotExist()
                onNodeWithText("Check again").performClick()
                assertEquals(1, retries)
                saveScreenshot(onRoot().captureToImage(), "watch-connection-installed-$width-$height.png")
            }
        }
    }

    private fun saveScreenshot(
        image: ImageBitmap,
        filename: String = "watch-companion-page.png",
    ) {
        val directory = System.getenv("LOGDATE_SETTINGS_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, filename)
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
