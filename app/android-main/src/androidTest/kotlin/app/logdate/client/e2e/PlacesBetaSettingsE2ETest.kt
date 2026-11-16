package app.logdate.client.e2e

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.ui.navigation.LocationTimelineRoute
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.AdvancedSettingsRoute
import app.logdate.feature.core.settings.navigation.LabsSettingsRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.navigation.LogDateNavDisplay
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production Settings/Home/NavDisplay with real app preferences; no onboarding or data resets. */
@RunWith(AndroidJUnit4::class)
class PlacesBetaSettingsE2ETest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var pending by mutableStateOf<NavKey?>(null)
    private var current by mutableStateOf<NavKey?>(null)
    private val flags get() = GlobalContext.get().get<FeatureFlagStore>()

    @Test fun ordinarySettingsGatesPlacesAndReturnsToHomeWithoutChangingCollection() =
        runBlocking {
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            val flags = GlobalContext.get().get<FeatureFlagStore>()
            val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
            val original = flags.isEnabled(FeatureFlag.PLACES_BETA)
            val developer = preferences.observeDeveloperModeEnabled().first()
            val collection = flags.isEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY)
            val tracking = GlobalContext.get().get<LocationTrackingSettingsRepository>()
            val originalTracking = tracking.getSettings()
            try {
                flags.setEnabled(FeatureFlag.PLACES_BETA, false)
                showApp()
                compose.waitUntil(15_000) { current == HomeRoute }
                compose.onNodeWithText("Places").assertDoesNotExist()
                compose.onNodeWithText("Rewind").assertIsDisplayed()

                compose.runOnIdle { pending = LocationTimelineRoute }
                compose.waitUntil(10_000) { pending == null && current == HomeRoute }
                compose.onNodeWithText("Places").assertDoesNotExist()

                verifyHomeToggle()

                verifyDirectRouteToggle()
                assertEquals(developer, preferences.observeDeveloperModeEnabled().first())
                assertEquals(collection, flags.isEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY))
                assertEquals(originalTracking, tracking.getSettings())
            } finally {
                flags.setEnabled(FeatureFlag.PLACES_BETA, original)
            }
        }

    private fun showApp() {
        compose.setContent {
            LogDateTheme(dynamicColor = false) {
                LogDateNavDisplay(
                    appUiState = GlobalAppUiLoadedState(isLoaded = true, isOnboarded = true),
                    onShowUnlockPrompt = {},
                    pendingNavKey = pending,
                    onPendingNavKeyConsumed = { pending = null },
                    onCurrentNavKeyChanged = { current = it },
                )
            }
        }
    }

    private fun verifyHomeToggle() {
        compose.onNodeWithTag("workspace_account").performClick()
        compose.onNodeWithText("Settings").performClick()
        openLabs()
        compose.onNodeWithText("Places (beta)").performScrollTo().assertIsDisplayed()
        capture("places-beta-settings-off")
        compose.onNodeWithText("Places (beta)").performClick()
        compose.waitUntil(10_000) { runBlocking { flags.isEnabled(FeatureFlag.PLACES_BETA) } }
        capture("places-beta-settings-on")
        leaveSettings()
        compose.onNodeWithText("Places").performClick()
        capture("places-beta-home-on")

        compose.onNodeWithTag("workspace_account").performClick()
        compose.onNodeWithText("Settings").performClick()
        openLabs()
        compose.onNodeWithText("Places (beta)").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !flags.isEnabled(FeatureFlag.PLACES_BETA) } }
        leaveSettings()
        compose.onNodeWithText("Places").assertDoesNotExist()
        compose.onNodeWithText("Timeline").assertIsDisplayed()
        compose.onNodeWithText("Rewind").assertIsDisplayed()
        capture("places-beta-home-off")
    }

    private suspend fun verifyDirectRouteToggle() {
        flags.setEnabled(FeatureFlag.PLACES_BETA, true)
        compose.runOnIdle { pending = LocationTimelineRoute }
        compose.waitUntil(10_000) { current == LocationTimelineRoute }
        compose.runOnIdle { pending = SettingsRoute() }
        compose.waitUntil(10_000) { current is SettingsRoute }
        openLabs()
        compose.onNodeWithText("Places (beta)").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !flags.isEnabled(FeatureFlag.PLACES_BETA) } }
        leaveSettings()
        compose.onNodeWithText("Places").assertDoesNotExist()
    }

    private fun openLabs() {
        compose.onNodeWithText("Places (beta)").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("About LogDate"))
        compose.onNodeWithText("About LogDate").performClick()
        compose.waitUntil(10_000) { current == AdvancedSettingsRoute }
        compose.onNodeWithText("Labs").performScrollTo().performClick()
        compose.waitUntil(10_000) { current == LabsSettingsRoute }
    }

    private fun leaveSettings() {
        repeat(3) {
            device.pressBack()
            compose.waitForIdle()
        }
        compose.waitUntil(10_000) { current == HomeRoute }
    }

    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }
}
