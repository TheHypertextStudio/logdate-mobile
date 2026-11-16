package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.account.CLOUD_ACCOUNT_WELCOME_CREATE_TAG
import app.logdate.feature.core.account.CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG
import app.logdate.feature.core.account.CloudAccountSignInContent
import app.logdate.feature.core.account.CloudAccountWelcomeContent
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.ui.step.STEP_TITLE_TAG
import app.logdate.ui.theme.FirstLaunchTheme
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every onboarding step keeps its title and its actions in one place, so a person tapping through
 * never has to find them again. The opening once pinned its actions to the bottom edge and once
 * floated them mid-screen with its words, and titles sat wherever each step's contents left them.
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingOpeningLayoutTest {
    @Test
    fun `the title and the main action stay put from the opening to the timeline to the next step`() {
        val opening =
            actions("opening-hero-phone", ONBOARDING_START_GET_STARTED_TAG, ONBOARDING_START_FROM_BACKUP_TAG) {
                OnboardingOpeningScreen(overview = false, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
            }
        val timeline =
            actions("opening-overview-phone", ONBOARDING_OVERVIEW_CONTINUE_TAG, "onboarding_overview_back") {
                OnboardingOpeningScreen(overview = true, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
            }
        val cloud =
            actions("cloud-welcome-phone", CLOUD_ACCOUNT_WELCOME_CREATE_TAG, CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG) {
                CloudAccountWelcomeContent(
                    onContinue = {},
                    onSignIn = {},
                    onBack = {},
                    serverSelectionState = ServerSelectionState(),
                )
            }

        var signIn = DpRect(0.dp, 0.dp, 0.dp, 0.dp)
        var signInTitle = DpRect(0.dp, 0.dp, 0.dp, 0.dp)
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountSignInContent(
                        username = "",
                        onUsernameChange = {},
                        onSignIn = {},
                        onAccountRecovery = {},
                        onPrivacyPolicy = {},
                        onTermsOfService = {},
                        onBack = {},
                        serverDisplayName = "LogDate Cloud",
                        serverHandleDomain = "logdate.app",
                    )
                }
            }
            save("cloud-sign-in-phone", onRoot().captureToImage().toPixelMap())
            signIn = onNode(hasText("Sign in with passkey") and hasClickAction()).getUnclippedBoundsInRoot()
            signInTitle = onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot()
        }

        assertEquals(opening.title.top, timeline.title.top, "the title moved between the opening and the timeline")
        assertEquals(opening.title.top, cloud.title.top, "the title moved between the opening and LogDate Cloud")
        assertEquals(opening.title.top, signInTitle.top, "the title moved between the opening and signing in")

        assertEquals(opening.primary, signIn, "the main action moved between the opening and signing in")
        assertEquals(opening.primary, timeline.primary, "the main action moved between the opening and the timeline")
        assertEquals(opening.secondary.top, timeline.secondary.top, "the second action moved between the opening and the timeline")
        assertEquals(opening.primary, cloud.primary, "the main action moved between the opening and a later step")
        assertEquals(opening.secondary.bottom, cloud.secondary.bottom, "the second action moved between the opening and a later step")
        assertTrue(PHONE_HEIGHT.dp - opening.secondary.bottom in 12.dp..24.dp, "actions end ${opening.secondary.bottom} down the screen")
    }

    @Test
    fun `the opening scene sits between its words and the actions`() =
        listOf(false, true).forEach { overview ->
            runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
                setContent {
                    LogDateTheme(dynamicColor = false, darkTheme = false) {
                        OnboardingOpeningScreen(overview = overview, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
                    }
                }
                val scene = onNodeWithTag(ONBOARDING_OPENING_SCENE_TAG).getUnclippedBoundsInRoot()
                val primary =
                    onNodeWithTag(if (overview) ONBOARDING_OVERVIEW_CONTINUE_TAG else ONBOARDING_START_GET_STARTED_TAG)
                        .getUnclippedBoundsInRoot()
                val title = onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot()
                assertTrue(scene.top >= title.bottom, "scene (top ${scene.top}) overlaps the words (title bottom ${title.bottom})")
                assertTrue(scene.bottom <= primary.top, "scene (bottom ${scene.bottom}) runs under the actions (top ${primary.top})")
            }
        }

    @Test
    fun `on a tablet the opening scene fills the step between its words and the actions`() =
        listOf("tablet-landscape" to (1280 to 800), "tablet-portrait" to (800 to 1280)).forEach { (name, size) ->
            listOf(false, true).forEach { overview ->
                runDesktopComposeUiTest(width = size.first, height = size.second) {
                    setContent {
                        LogDateTheme(dynamicColor = false, darkTheme = false) {
                            OnboardingOpeningScreen(overview = overview, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
                        }
                    }
                    save("opening-$name${if (overview) "-overview" else ""}", onRoot().captureToImage().toPixelMap())
                    val scene = onNodeWithTag(ONBOARDING_OPENING_SCENE_TAG).getUnclippedBoundsInRoot()
                    val title = onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot()
                    val primary =
                        onNodeWithTag(if (overview) ONBOARDING_OVERVIEW_CONTINUE_TAG else ONBOARDING_START_GET_STARTED_TAG)
                            .getUnclippedBoundsInRoot()
                    assertTrue(scene.top >= title.bottom, "scene overlaps the words on $name")
                    assertTrue(scene.bottom <= primary.top, "scene runs under the actions on $name")
                    assertTrue(scene.bottom - scene.top >= 180.dp, "scene is only ${scene.bottom - scene.top} tall on $name")
                }
            }
        }

    private class ActionBounds(
        val primary: DpRect,
        val secondary: DpRect,
        val title: DpRect,
    )

    private fun actions(
        name: String,
        primaryTag: String,
        secondaryTag: String,
        content: @Composable () -> Unit,
    ): ActionBounds {
        lateinit var bounds: ActionBounds
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { LogDateTheme(dynamicColor = false, darkTheme = false, content = content) }
            save(name, onRoot().captureToImage().toPixelMap())
            bounds =
                ActionBounds(
                    onNodeWithTag(primaryTag).getUnclippedBoundsInRoot(),
                    onNodeWithTag(secondaryTag).getUnclippedBoundsInRoot(),
                    onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot(),
                )
        }
        return bounds
    }

    @Test
    fun `turning the device moves the nearer pieces further than the date behind them`() =
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_WIDTH) {
            var turned by mutableStateOf(Offset.Zero)
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    FirstLaunchTheme {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            OnboardingObjects(overview = false, animateScene = false, parallax = turned)
                        }
                    }
                }
            }
            val rest = onRoot().captureToImage().toPixelMap()
            save("scene-at-rest", rest)
            turned = Offset(1f, -1f)
            waitForIdle()
            val moved = onRoot().captureToImage().toPixelMap()
            save("scene-turned", moved)
            val changed = (0 until rest.height).sumOf { y -> (0 until rest.width).count { x -> rest[x, y] != moved[x, y] } }
            assertTrue(changed > 2_000, "turning the device changed only $changed pixels")
        }

    private fun save(
        name: String,
        pixels: androidx.compose.ui.graphics.PixelMap,
    ) {
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/reports/onboarding-opening").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "$name.png"))
    }

    private companion object {
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844
    }
}
