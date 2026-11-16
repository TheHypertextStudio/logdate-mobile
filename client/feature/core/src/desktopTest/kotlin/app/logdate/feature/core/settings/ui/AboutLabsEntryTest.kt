@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.core.settings.updates.AppUpdateUiState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Labs remains discoverable from About without enabling developer mode.
 */
@OptIn(ExperimentalTestApi::class)
class AboutLabsEntryTest {
    @Test
    fun `Labs opens without tapping the installed version`() =
        runDesktopComposeUiTest {
            var labsOpened = 0
            setContent { About(AppUpdateUiState(currentVersionName = "1.4.0"), onOpenLabs = { labsOpened += 1 }) }

            onNodeWithText("Labs").assertExists().performClick()
            assertEquals(1, labsOpened)
        }

    @Test
    fun `Labs opens when the version is not available`() =
        runDesktopComposeUiTest {
            var labsOpened = 0
            setContent { About(AppUpdateUiState(), onOpenLabs = { labsOpened += 1 }) }

            onNodeWithText("Labs").assertExists().performClick()
            assertEquals(1, labsOpened)
        }
}

@Composable
private fun About(
    appUpdateUiState: AppUpdateUiState,
    onOpenLabs: () -> Unit,
) {
    val developerModeEnabled = remember { mutableStateOf(false) }
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        AdvancedSettingsContent(
            onBack = {},
            appUpdateUiState = appUpdateUiState,
            onCheckForAppUpdates = {},
            onCompleteAppUpdate = {},
            onNavigateToLabs = onOpenLabs,
            developerModeEnabled = developerModeEnabled.value,
            onSetDeveloperModeEnabled = { developerModeEnabled.value = it },
        )
    }
}
