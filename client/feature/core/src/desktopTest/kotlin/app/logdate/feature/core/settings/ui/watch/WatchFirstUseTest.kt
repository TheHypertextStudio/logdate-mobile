package app.logdate.feature.core.settings.ui.watch

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticCheck
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticDetail
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticFix
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticOutcome
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticStep
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsContent
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsState
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.watch.WatchSetupProvider
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WatchFirstUseTest {
    @Test fun `setup opens the chosen manufacturer companion and never defaults to Pixel`() =
        runDesktopComposeUiTest {
            val opened = mutableListOf<WatchSetupProvider>()
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchSystemSetupButton { provider ->
                        opened.add(provider)
                        true
                    }
                }
            }
            onNodeWithText("Set up your watch").performClick()
            assertEquals(emptyList(), opened)
            onNodeWithText("Samsung Galaxy Watch").performClick()
            assertEquals(listOf(WatchSetupProvider.GALAXY), opened)
        }

    @Test fun `other watches show manufacturer setup guidance without opening the legacy Wear app`() =
        runDesktopComposeUiTest {
            val opened = mutableListOf<WatchSetupProvider>()
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchSystemSetupButton { provider ->
                        opened.add(provider)
                        true
                    }
                }
            }
            onNodeWithText("Set up your watch").performClick()
            onNodeWithText("Other Wear OS watch").performClick()
            onNodeWithText("Turn on your watch", substring = true).assertIsDisplayed()
            assertEquals(emptyList(), opened)
        }

    @Test fun `diagnostics with no node show the prerequisite instead of unusable checks`() =
        runDesktopComposeUiTest {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchDiagnosticsContent(WatchDiagnosticsState.NeedsWatch, false, {}, {}, {})
                }
            }
            onNodeWithText("Connect a Wear OS watch", substring = true).assertIsDisplayed()
            onNodeWithText("Watch notifications").assertDoesNotExist()
            onNodeWithText("Open on watch").assertDoesNotExist()
            onNodeWithText("Set up your watch").assertIsDisplayed()
        }

    @Test fun `fixable diagnostic titles do not claim the check has passed`() =
        runDesktopComposeUiTest {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    WatchDiagnosticsContent(
                        WatchDiagnosticsState.Ready(
                            listOf(
                                WatchDiagnosticStep(
                                    WatchDiagnosticCheck.SYNC,
                                    WatchDiagnosticOutcome.NEEDS_ACTION,
                                    WatchDiagnosticDetail.SyncPending(2),
                                    WatchDiagnosticFix.SYNC,
                                ),
                                WatchDiagnosticStep(
                                    WatchDiagnosticCheck.NOTIFICATIONS,
                                    WatchDiagnosticOutcome.NEEDS_ACTION,
                                    WatchDiagnosticDetail.NotificationsDisabled,
                                    WatchDiagnosticFix.OPEN_WATCH,
                                ),
                            ),
                        ),
                        false,
                        {},
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Moments sync").assertExists()
            onNodeWithText("Watch notifications").assertExists()
            onNodeWithText("All watch moments are synced to this phone.").assertDoesNotExist()
            onNodeWithText("Notifications are allowed on your watch.").assertDoesNotExist()
        }
}
