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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.feature.core.settings.ui.watch.DiscoveredWatch
import app.logdate.feature.core.settings.ui.watch.WatchLinkLine
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchSetupStep
import app.logdate.feature.core.settings.ui.watch.WatchStatus
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
            var installSent by mutableStateOf(false)
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.NotInstalled("Pixel Watch"),
                        requestFailure = null,
                        onBack = {},
                        onAssociate = {},
                        onInstall = {
                            installRequests++
                            installSent = true
                        },
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        installRequestSent = installSent,
                    )
                }
            }
            saveScreenshot(onRoot().captureToImage(), "watch-setup-install.png")
            onNodeWithText("Install on watch").performClick()
            assertEquals(1, installRequests)
            waitForIdle()
            onNodeWithText("Finish installing on Pixel Watch").assertExists()
            onNodeWithText("Check your watch", substring = true).assertExists()
            onNodeWithText("Already installed", substring = true).assertDoesNotExist()
            onNodeWithText("Check connection").assertDoesNotExist()
            onNodeWithText("Timeline history").assertDoesNotExist()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-install-sent.png")
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
    fun `multiple discovered watches open a device chooser before device setup`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val selected = mutableListOf<String>()
            val watches =
                listOf(
                    DiscoveredWatch("watch-a", "Pixel Watch 3", isNearby = true, appInstalled = false),
                    DiscoveredWatch("watch-b", "Galaxy Watch 7", isNearby = true, appInstalled = true),
                )
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.Unknown("Pixel Watch 3", "watch-a", knownInstalled = false, availableWatches = watches),
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
                        onSelectWatch = { selected += it },
                    )
                }
            }
            onNodeWithText("Choose your watch").assertExists()
            onNodeWithText("Pixel Watch 3").assertExists()
            onNodeWithText("Galaxy Watch 7").assertExists()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-choose-watch.png")

            onNodeWithText("Galaxy Watch 7").performClick()
            onNodeWithText("Continue").performClick()
            assertEquals(listOf("watch-b"), selected)
            onNodeWithText("Install LogDate on Pixel Watch 3").assertExists()
        }

    @Test
    fun `unverified watch leads with Install LogDate directly and supports Already installed fallback`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val confirmed = mutableListOf<String>()
            var opened = 0
            var backs = 0
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = WatchStatus.Unknown("Pixel Watch", "watch-a", knownInstalled = false),
                        requestFailure = null,
                        onBack = { backs++ },
                        onAssociate = {},
                        onInstall = {},
                        onReminders = {},
                        onTiles = {},
                        onMicrophone = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onRemove = {},
                        onConfirmMissing = { confirmed += it },
                        onOpenOnWatch = { opened++ },
                    )
                }
            }
            onNodeWithTag("watch_setup_art").assertExists()
            onNodeWithText("Install LogDate on Pixel Watch").assertExists()
            onNodeWithText("Don’t have it on your watch?").assertDoesNotExist()
            onNodeWithText("Check connection").assertDoesNotExist()
            onNodeWithText("Connection details").assertDoesNotExist()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-open-watch.png")

            onNodeWithText("Already installed").performClick()
            assertEquals(1, opened)
            waitForIdle()
            onNodeWithText("Opening LogDate on Pixel Watch").assertExists()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-opening-watch.png")

            onNodeWithContentDescription("Back").performClick()
            assertEquals(0, backs)
            onNodeWithText("Install LogDate on Pixel Watch").assertExists()

            onNodeWithText("Install on watch").performClick()
            assertEquals(listOf("watch-a"), confirmed)
            waitForIdle()
            onNodeWithText("Finish installing on Pixel Watch").assertExists()
        }

    @Test
    fun `known installed unresponsive watch stays inside focused setup without install or duplicate diagnostics`() {
        for ((width, height) in listOf(411 to 891, 1000 to 800, 1280 to 800)) {
            runDesktopComposeUiTest(width = width, height = height) {
                var diagnostics = 0
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
                            onDiagnostics = { diagnostics++ },
                            onRemove = {},
                            onOpenOnWatch = {},
                        )
                    }
                }
                onNodeWithTag("watch_setup_art").assertExists()
                onNodeWithText("Don’t have it on your watch?").assertDoesNotExist()
                onNodeWithText("Install on watch").assertDoesNotExist()
                onNodeWithText("Check connection").assertDoesNotExist()
                onNodeWithText("Microphone").assertDoesNotExist()
                onNodeWithText("Remove watch").assertDoesNotExist()
                onNodeWithText("Pixel Watch didn’t respond").assertExists()
                onNodeWithText("Open on watch").assertExists()
                saveScreenshot(onRoot().captureToImage(), "watch-setup-open-installed-$width-$height.png")
                onNodeWithText("Troubleshoot connection").performClick()
                assertEquals(1, diagnostics)
            }
        }
    }

    @Test
    fun `setup progression captures pairing, confirmation, linking, and celebratory completion before companion settings`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var status by mutableStateOf<WatchStatus>(WatchStatus.SettingUp(WatchSetupStep.PairWithPhone("Pixel Watch")))
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchCompanionPage(
                        status = status,
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
            onNodeWithTag("watch_setup_art").assertExists()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-pair.png")

            status = WatchStatus.SettingUp(WatchSetupStep.ConfirmOnPhone("Pixel Watch"))
            waitForIdle()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-confirm-phone.png")

            status = WatchStatus.SettingUp(WatchSetupStep.Linking("Pixel Watch"))
            waitForIdle()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-linking.png")

            status = WatchStatus.Ready("Pixel Watch", nearby = true, link = WatchLinkLine.ON_ITS_OWN)
            waitForIdle()
            onNodeWithText("Pixel Watch is ready").assertExists()
            saveScreenshot(onRoot().captureToImage(), "watch-setup-complete.png")

            onNodeWithText("Done").performClick()
            waitForIdle()
            onNodeWithText("Check connection").assertExists()
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
