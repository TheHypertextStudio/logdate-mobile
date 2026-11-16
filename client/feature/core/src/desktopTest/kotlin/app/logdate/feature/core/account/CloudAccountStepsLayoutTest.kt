package app.logdate.feature.core.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.ui.step.STEP_TITLE_TAG
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The account steps read as one flow on a phone: the title and the main action are in the same
 * place on every step, and every short line of copy fits on one line. A title that moved between
 * steps and copy that wrapped a word or two onto a second line both made these steps look
 * unfinished.
 */
@OptIn(ExperimentalTestApi::class)
class CloudAccountStepsLayoutTest {
    private class Step(
        val name: String,
        val title: DpRect,
        val action: DpRect,
    )

    @Test
    fun `the title and the main action are in the same place on every account step`() = assertStepsAlign(PHONE)

    @Test
    fun `on a tablet the title and the main action are in the same place on every account step`() {
        assertStepsAlign(TABLET_LANDSCAPE)
        assertStepsAlign(TABLET_PORTRAIT)
    }

    private fun assertStepsAlign(window: Window) {
        fun step(
            name: String,
            action: DesktopComposeUiTest.() -> SemanticsNodeInteraction,
            oneLine: List<String>,
            content: @Composable () -> Unit,
        ) = step(window, name, action, oneLine, content)

        val steps =
            listOf(
                step("welcome", action = { onNodeWithTag(CLOUD_ACCOUNT_WELCOME_CREATE_TAG) }, oneLine = WELCOME_COPY) {
                    CloudAccountWelcomeContent(
                        onContinue = {},
                        onSignIn = {},
                        onBack = {},
                        serverSelectionState = ServerSelectionState(),
                        onSeePlans = {},
                    )
                },
                step("hosting", action = { onNodeWithTag(CLOUD_ACCOUNT_HOSTING_DONE_TAG) }, oneLine = HOSTING_COPY) {
                    CloudAccountHostingContent(
                        serverSelectionState = ServerSelectionState(selectedPreset = ServerPreset.CUSTOM),
                        onSelectServerPreset = {},
                        onCustomServerUrlChange = {},
                        onShowCustomServerInfo = {},
                        onDone = {},
                        onBack = {},
                    )
                },
                step("plans", action = { onNodeWithTag(CLOUD_PLANS_DONE_TAG) }, oneLine = PLANS_COPY) {
                    CloudAccountPlansContent(onDone = {}, onBack = {})
                },
                step(
                    "sign-in",
                    action = { onNode(hasText("Sign in with passkey") and hasClickAction()) },
                    oneLine = listOf("Use your LogDate Cloud passkey.", "Own server? Type alex@journal.example.com"),
                ) {
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
                },
                step(
                    "name",
                    action = { onNodeWithTag(CLOUD_ACCOUNT_DISPLAY_NAME_CONTINUE_TAG) },
                    oneLine = listOf("You can change this later."),
                ) {
                    DisplayNameSetupScreen(displayName = "", onDisplayNameChange = {}, onContinue = {}, onBack = {})
                },
                step(
                    "username",
                    action = { onNodeWithTag(CLOUD_ACCOUNT_USERNAME_CONTINUE_TAG) },
                    oneLine = listOf("You can’t change it later.", "3–30 letters, numbers or underscores."),
                ) {
                    UsernameSetupScreen(
                        username = "",
                        onUsernameChange = {},
                        onContinue = {},
                        onBack = {},
                        handleDomain = "logdate.app",
                        stepNumber = 2,
                        stepCount = 3,
                        hostName = "LogDate Cloud",
                    )
                },
                step(
                    "confirm",
                    action = { onNodeWithTag(CLOUD_ACCOUNT_PASSKEY_CREATE_TAG) },
                    oneLine = listOf("Check your name and username."),
                ) {
                    PasskeyAccountCreationFinalContent(
                        displayName = "Alex Johnson",
                        username = "alex_j",
                        onCreateAccount = {},
                        onBack = {},
                        isCreatingAccount = false,
                        errorMessage = null,
                        onClearError = {},
                        isPasskeySupported = true,
                        serverDisplayName = "LogDate Cloud",
                        stepNumber = 3,
                        stepCount = 3,
                    )
                },
            )

        val first = steps.first()
        steps.drop(1).forEach { other ->
            assertEquals(first.title.top, other.title.top, "the title moved between ${first.name} and ${other.name}")
            assertEquals(first.action, other.action, "the main action moved between ${first.name} and ${other.name}")
        }
    }

    private fun step(
        window: Window,
        name: String,
        action: DesktopComposeUiTest.() -> SemanticsNodeInteraction,
        oneLine: List<String>,
        content: @Composable () -> Unit,
    ): Step {
        lateinit var step: Step
        runDesktopComposeUiTest(width = window.width, height = window.height) {
            setContent { LogDateTheme(dynamicColor = false, darkTheme = false, content = content) }
            waitForIdle()
            save("${window.name}-$name")
            assertEquals(1, lines(onNodeWithTag(STEP_TITLE_TAG)), "the title wraps on $name")
            oneLine.forEach { text -> assertEquals(1, lines(onNodeWithText(text)), "\"$text\" wraps on $name") }
            step = Step(name, onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot(), action().getUnclippedBoundsInRoot())
        }
        return step
    }

    private fun lines(node: SemanticsNodeInteraction): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.first().lineCount
    }

    private fun DesktopComposeUiTest.save(name: String) {
        val directory = System.getenv("LOGDATE_SETTINGS_SCREENSHOT_DIR") ?: return
        val pixels = onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private class Window(
        val name: String,
        val width: Int,
        val height: Int,
    )

    private companion object {
        val PHONE = Window("step", width = 390, height = 844)
        val TABLET_LANDSCAPE = Window("tablet-landscape", width = 1280, height = 800)
        val TABLET_PORTRAIT = Window("tablet-portrait", width = 800, height = 1280)

        val WELCOME_COPY =
            listOf(
                "Backed up and on all your devices.",
                "Free during the beta",
                "Join now and keep it free after launch.",
            )
        val HOSTING_COPY =
            listOf(
                "Most people use LogDate Cloud.",
                "Nothing to set up.",
                "If you run LogDate yourself.",
                "For example, journal.example.com",
            )
        val PLANS_COPY =
            listOf(
                "LogDate Cloud is free during the beta.",
                "Backs up everything",
                "Syncs your devices",
                "Stays free if you join now",
                "$3.99/month after the beta",
                "LogDate Cloud Plus",
                "Everything in LogDate Cloud",
                "More room for photos and video",
                "Extra space when you need it",
                "$7.99/month, plus $1 per extra 50 GB",
            )
    }
}
