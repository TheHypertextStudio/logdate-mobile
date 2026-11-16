package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlacesBetaSettingsItemTest {
    @Test
    fun `ordinary beta toggle works in dark RTL large text and shows persistence failure`() =
        runDesktopComposeUiTest(width = 411, height = 800) {
            var state: PlacesBetaSettingsState by mutableStateOf(PlacesBetaSettingsState.Ready(false))
            var requested: Boolean? = null
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LogDateTheme(dynamicColor = false, darkTheme = true) {
                        PlacesBetaSettingsItem(state) { requested = it }
                    }
                }
            }
            onNodeWithText("Places & location history · Beta").performClick()
            assertEquals(true, requested)
            runOnIdle { state = PlacesBetaSettingsState.Ready(false, saveFailed = true) }
            onNodeWithText("Couldn’t save your choice. Try again.").assertExists()
            runOnIdle { state = PlacesBetaSettingsState.Ready(false, saving = true) }
            onNodeWithText("Places & location history · Beta").assertIsNotEnabled()
        }
}
