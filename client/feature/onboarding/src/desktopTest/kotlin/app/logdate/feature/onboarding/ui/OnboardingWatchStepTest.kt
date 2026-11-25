package app.logdate.feature.onboarding.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OnboardingWatchStepTest {
    @Test fun `installed watch offers Continue rather than another installation`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var continued = 0
            setContent {
                LogDateTheme(dynamicColor = false) {
                    OnboardingWatchStepContent(
                        WatchConnectionState.Connected("Pixel Watch"),
                        WatchSetupState.Ready,
                        false,
                        false,
                        {},
                        { continued++ },
                        {},
                    )
                }
            }
            onNodeWithText("LogDate is set up on your watch").assertExists()
            onNodeWithText("Continue").performClick()
            assertEquals(1, continued)
        }

    @Test fun `installation is optional and pending setup keeps skipping available`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    OnboardingWatchStepContent(
                        WatchConnectionState.AppNotInstalled("Pixel Watch"),
                        WatchSetupState.WaitingForInstall,
                        true,
                        false,
                        {},
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Waiting for LogDate to be installed on your watch").assertExists()
            onNodeWithText("Add to watch").assertIsNotEnabled()
            onNodeWithText("Not now").assertIsEnabled()
        }

    @Test fun `unknown watch leads with Install on watch and offers Already installed fallback`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var opened = 0
            val confirmed = mutableListOf<String>()
            setContent {
                LogDateTheme(dynamicColor = false) {
                    OnboardingWatchStepContent(
                        WatchConnectionState.AppUnknown("Pixel Watch", "watch-node"),
                        WatchSetupState.CheckOnWatch("Pixel Watch", "watch-node", false),
                        false,
                        false,
                        {},
                        {},
                        {},
                        onConfirmMissing = { confirmed += it },
                        onOpenOnWatch = { opened++ },
                    )
                }
            }
            onNodeWithText("Install on watch").performClick()
            assertEquals(listOf("watch-node"), confirmed)
            onNodeWithText("Already installed").performClick()
            assertEquals(1, opened)
        }
}
