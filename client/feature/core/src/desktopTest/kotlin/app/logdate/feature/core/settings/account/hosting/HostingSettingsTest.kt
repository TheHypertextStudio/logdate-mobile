package app.logdate.feature.core.settings.account.hosting

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HostingSettingsTest {
    @Test
    fun cancelledInitialCheckLeavesAUsableRetry() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var retries = 0
            setContent {
                LogDateTheme(darkTheme = false, dynamicColor = false) {
                    HostingContent(
                        state = HostingUiState(server = ConnectedServerInfo("https://cloud.logdate.app", "LogDate Cloud", true, false)),
                        onBack = {},
                        onCheckAgain = { retries++ },
                    )
                }
            }
            onNodeWithText("cloud.logdate.app").assertIsDisplayed()
            onNodeWithText("Checking connection…").assertDoesNotExist()
            onNodeWithText("Check again").performClick()
            assertEquals(1, retries)
        }
}
