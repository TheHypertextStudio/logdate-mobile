package app.logdate.client.e2e

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.feature.core.settings.ui.AdvancedSettingsScreen
import app.logdate.feature.core.settings.ui.labs.LabsSettingsScreen
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real settings screens; wide captures use resized API 34 windows, not physical hinge hardware. */
class PlacesBetaSettingsWindowsTest {
    @Test(timeout = 180_000)
    fun publicLabsKeepsPlacesReachableAtThreeWindowSizes() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(Build.HARDWARE in setOf("ranchu", "goldfish") && instrumentation.targetContext.packageName.endsWith(".debug"))
            val device = UiDevice.getInstance(instrumentation)
            val oldSize = overrideValue(device.executeShellCommand("wm size"))
            val oldDensity = overrideValue(device.executeShellCommand("wm density"))
            val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
            val flags = GlobalContext.get().get<FeatureFlagStore>()
            val developer = preferences.observeDeveloperModeEnabled().first()
            val original = flags.isEnabled(FeatureFlag.PLACES_BETA)
            val output =
                File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "places-settings-windows")
            check(output.mkdirs() || output.isDirectory)
            try {
                preferences.setDeveloperModeEnabled(false)
                val windows = listOf("normal-phone" to 0, "foldable-sized-window" to 1000, "landscape-tablet-sized-window" to 1280)
                for ((label, width) in windows) {
                    if (width != 0) {
                        device.executeShellCommand("wm density 160")
                        device.executeShellCommand("wm size ${width}x800")
                    }
                    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.enableEdgeToEdge()
                            activity.setContent {
                                var labs by remember { mutableStateOf(false) }
                                LogDateTheme(dynamicColor = false) {
                                    if (labs) {
                                        LabsSettingsScreen(onBack = { labs = false })
                                    } else {
                                        AdvancedSettingsScreen(onBack = {}, onNavigateToLabs = { labs = true })
                                    }
                                }
                            }
                        }
                        val entry = checkNotNull(device.wait(Until.findObject(By.textStartsWith("Labs")), 10_000))
                        assertNull(device.findObject(By.textContains("Developer Tools")))
                        entry.click()
                        var previous: Rect? = null
                        var stable = 0
                        val deadline = SystemClock.elapsedRealtime() + 10_000
                        while (stable < 3 && SystemClock.elapsedRealtime() < deadline) {
                            var matches = false
                            scenario.onActivity { activity ->
                                val decor = activity.window.decorView
                                matches = width == 0 || (decor.width == width && decor.height == 800)
                            }
                            val bounds = device.findObject(By.textContains("Places & location history · Beta"))?.visibleBounds
                            stable = if (matches && bounds != null && !bounds.isEmpty && bounds == previous) stable + 1 else 0
                            previous = bounds
                            SystemClock.sleep(100)
                        }
                        assertTrue(stable >= 3, "Settings must settle inside the requested viewport at $label")
                        assertNull(device.findObject(By.textContains("Redesigned watch app")))
                        check(device.takeScreenshot(File(output, "$label-labs.png")))
                        File(output, "$label-window.txt").writeText(
                            device.executeShellCommand("wm size") + device.executeShellCommand("wm density"),
                        )
                    }
                    assertEquals(original, flags.isEnabled(FeatureFlag.PLACES_BETA))
                }
            } finally {
                device.executeShellCommand("wm size $oldSize")
                device.executeShellCommand("wm density $oldDensity")
                preferences.setDeveloperModeEnabled(developer)
            }
        }

    private fun overrideValue(value: String): String =
        value
            .lineSequence()
            .firstOrNull { it.startsWith("Override") }
            ?.substringAfter(":")
            ?.trim() ?: "reset"
}
