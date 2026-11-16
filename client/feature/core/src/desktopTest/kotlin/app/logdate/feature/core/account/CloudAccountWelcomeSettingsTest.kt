package app.logdate.feature.core.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LogDate Cloud's front door and the step for choosing a provider.
 *
 * The welcome once carried a server picker and a feature list, then nothing but two buttons. It
 * has one job: say what LogDate Cloud does, what it costs, and how to get in. Choosing a provider
 * is a step of its own, and it only asks for an address once another provider is chosen.
 */
@OptIn(ExperimentalTestApi::class)
class CloudAccountWelcomeSettingsTest {
    @Test
    fun `the welcome says what LogDate Cloud does, what it costs and how to get in`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var planRequests = 0
            var exits = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountWelcomeContent(
                        onContinue = {},
                        onSignIn = {},
                        onBack = { exits++ },
                        serverSelectionState = ServerSelectionState(),
                        onSeePlans = { planRequests++ },
                    )
                }
            }
            saveScreenshot("cloud-welcome", onRoot().captureToImage())
            onNodeWithText("Keep it all safe").assertIsDisplayed()
            onNodeWithText("Create account").assertIsDisplayed()
            onNodeWithText("I already have an account").assertIsDisplayed()
            // It is a subscription service, free for now, and the step says so.
            onNodeWithText("Free during the beta").assertIsDisplayed()
            onNodeWithText("Join now and keep it free after launch.").assertIsDisplayed()
            onNodeWithTag(CLOUD_OFFER_PLANS_TAG).assertIsDisplayed().performClick()
            assertEquals(1, planRequests)
            // Where an account is hosted is not asked here, and there is no way to it from here.
            assertTrue(onAllNodesWithText("Server address").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("Use another provider").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("Hosted by LogDate Cloud").fetchSemanticsNodes().isEmpty())
            onNodeWithContentDescription("Back").performClick()
            assertEquals(1, exits)
        }

    @Test
    fun `the welcome keeps both ways in on screen at the largest text`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    LogDateTheme(dynamicColor = false, darkTheme = false) {
                        CloudAccountWelcomeContent(
                            onContinue = {},
                            onSignIn = {},
                            onBack = {},
                            serverSelectionState = ServerSelectionState(),
                        )
                    }
                }
            }
            saveScreenshot("cloud-welcome-large-text", onRoot().captureToImage())
            onNodeWithTag(CLOUD_ACCOUNT_WELCOME_CREATE_TAG).assertIsDisplayed()
            onNodeWithTag(CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG).assertIsDisplayed()
            onNodeWithTag(CLOUD_OFFER_TAG).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `the welcome shows the store's price once LogDate Cloud is sold`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountWelcomeContent(
                        onContinue = {},
                        onSignIn = {},
                        onBack = {},
                        serverSelectionState = ServerSelectionState(),
                        offer = CloudOffer.Subscription(price = "$4.99 a month"),
                    )
                }
            }
            saveScreenshot("cloud-welcome-subscription", onRoot().captureToImage())
            onNodeWithText("$4.99 a month").assertIsDisplayed()
            onNodeWithText("Billed through your app store. Cancel anytime.").assertIsDisplayed()
            assertTrue(onAllNodesWithText("Free during the beta").fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `the welcome names the server an app already uses and leaves out LogDate Cloud's price`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountWelcomeContent(
                        onContinue = {},
                        onSignIn = {},
                        onBack = {},
                        serverSelectionState =
                            ServerSelectionState(selectedPreset = ServerPreset.CUSTOM, customServerUrl = "https://notes.example.org/"),
                    )
                }
            }
            saveScreenshot("cloud-welcome-other-provider", onRoot().captureToImage())
            onNodeWithText("Hosted on notes.example.org.").assertIsDisplayed()
            assertTrue(onAllNodesWithText("Free during the beta").fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `hosting asks for a server address only after your own server is chosen`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var selection by mutableStateOf(ServerSelectionState())
            var explanations = 0
            var done = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountHostingContent(
                        serverSelectionState = selection,
                        onSelectServerPreset = { selection = selection.copy(selectedPreset = it) },
                        onCustomServerUrlChange = { selection = selection.copy(customServerUrl = it) },
                        onShowCustomServerInfo = { explanations++ },
                        onDone = { done++ },
                        onBack = {},
                    )
                }
            }
            saveScreenshot("cloud-hosting", onRoot().captureToImage())
            onNodeWithText("LogDate Cloud").assertIsDisplayed()
            onNodeWithText("Your own server").assertIsDisplayed()
            assertTrue(onAllNodesWithText("Server address").fetchSemanticsNodes().isEmpty())
            onNodeWithText("Use LogDate Cloud").assertIsEnabled()

            onNodeWithTag(CLOUD_ACCOUNT_HOSTING_OWN_TAG).performClick()
            waitForIdle()
            saveScreenshot("cloud-hosting-own", onRoot().captureToImage())
            onNodeWithText("Server address").performScrollTo().assertIsDisplayed()
            // The field shows what an address looks like, and its info button explains the rest.
            onNodeWithText("For example, journal.example.com").assertIsDisplayed()
            onNodeWithContentDescription("How this works").assertIsDisplayed().performClick()
            assertEquals(1, explanations)
            // A server with no address cannot be used yet.
            onNodeWithTag(CLOUD_ACCOUNT_HOSTING_DONE_TAG).assertIsNotEnabled()

            selection = selection.copy(customServerUrl = "journal.example.com")
            waitForIdle()
            onNodeWithText("Use this server").assertIsEnabled().performClick()
            assertEquals(1, done)
        }

    @Test
    fun `the hosting step keeps the address and its action reachable at the largest text`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    LogDateTheme(dynamicColor = false, darkTheme = false) {
                        CloudAccountHostingContent(
                            serverSelectionState =
                                ServerSelectionState(selectedPreset = ServerPreset.CUSTOM, customServerUrl = "journal.example.com"),
                            onSelectServerPreset = {},
                            onCustomServerUrlChange = {},
                            onShowCustomServerInfo = {},
                            onDone = {},
                            onBack = {},
                        )
                    }
                }
            }
            onNodeWithText("Server address").performScrollTo().assertIsDisplayed()
            saveScreenshot("cloud-hosting-large-text", onRoot().captureToImage())
            onNodeWithTag(CLOUD_ACCOUNT_HOSTING_DONE_TAG).assertIsDisplayed().assertIsEnabled()
        }

    @Test
    fun `the username step names the host and opens hosting from it`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var hostingRequests = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    UsernameSetupScreen(
                        username = "alex",
                        onUsernameChange = {},
                        onContinue = {},
                        onBack = {},
                        handleDomain = "logdate.app",
                        stepNumber = 2,
                        stepCount = 3,
                        hostName = "LogDate Cloud",
                        onChooseHosting = { hostingRequests++ },
                    )
                }
            }
            // The name being chosen is a name on this host, and the field says so.
            onNodeWithText("@logdate.app").assertIsDisplayed()
            onNodeWithTag(CLOUD_ACCOUNT_USERNAME_HOSTING_TAG).assertIsDisplayed().performClick()
            onNodeWithText("Hosted by LogDate Cloud").assertIsDisplayed()
            assertEquals(1, hostingRequests)
        }

    @Test
    fun `signing in takes a full name and stops naming LogDate Cloud once it has one`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var username by mutableStateOf("")
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountSignInContent(
                        username = username,
                        onUsernameChange = { username = it },
                        onSignIn = {},
                        onAccountRecovery = {},
                        onPrivacyPolicy = null,
                        onTermsOfService = null,
                        onBack = {},
                        serverDisplayName = "LogDate Cloud",
                        serverHandleDomain = "logdate.app",
                    )
                }
            }
            // There is no server to pick. The field says how to name an account elsewhere.
            onNodeWithText("Own server? Type alex@journal.example.com").assertIsDisplayed()
            username = "alex"
            waitForIdle()
            onNodeWithText("@logdate.app").assertIsDisplayed()
            username = "alex@journal.example.com"
            waitForIdle()
            saveScreenshot("cloud-sign-in-full-name", onRoot().captureToImage())
            assertTrue(onAllNodesWithText("@logdate.app").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("Use your LogDate Cloud passkey.").fetchSemanticsNodes().isEmpty())
            onNodeWithText("Use your passkey.").assertIsDisplayed()
        }

    @Test
    fun `the plans step at the largest text`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    LogDateTheme(dynamicColor = false, darkTheme = false) {
                        CloudAccountPlansContent(onDone = {}, onBack = {})
                    }
                }
            }
            saveScreenshot("cloud-plans-large-text", onRoot().captureToImage())
            onNodeWithText("$7.99/month, plus $1 per extra 50 GB").performScrollTo().assertIsDisplayed()
            onNodeWithTag(CLOUD_PLANS_DONE_TAG).assertIsDisplayed()
        }

    @Test
    fun `the plans step shows both plans and what each costs at launch`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var done = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountPlansContent(onDone = { done++ }, onBack = {})
                }
            }
            saveScreenshot("cloud-plans", onRoot().captureToImage())
            onNodeWithText("LogDate Cloud").assertIsDisplayed()
            onNodeWithText("Free in beta").assertIsDisplayed()
            onNodeWithText("Stays free if you join now").assertIsDisplayed()
            onNodeWithText("$3.99/month after the beta").assertIsDisplayed()
            onNodeWithText("LogDate Cloud Plus").assertIsDisplayed()
            onNodeWithText("$7.99/month, plus $1 per extra 50 GB").assertIsDisplayed()
            // Plus is not a launch-only plan, so nothing on it says when it starts.
            assertTrue(onAllNodesWithText("Available at launch").fetchSemanticsNodes().isEmpty())
            onNodeWithTag(CLOUD_PLANS_DONE_TAG).assertIsDisplayed().performClick()
            assertEquals(1, done)
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
