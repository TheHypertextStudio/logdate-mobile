package app.logdate.client.e2e

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.EditorActivity
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.ensureLogDateApplicationStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/** The creation anchor may be below the viewport; its interactive selector must remain safe. */
class EditorLongPageAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 180_000)
    fun longContentKeepsCaptureAboveNavigationAndLastLineCanScrollClear() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "editor-long-page")
        check(output.mkdirs() || output.isDirectory)
        ensureLogDateApplicationStarted(instrumentation.targetContext.applicationContext as Application)
        val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
        val previousMode = runBlocking { preferences.observeEntryStartMode().first() }
        val overlays = device.executeShellCommand("cmd overlay list")
        val previousNavigation =
            checkNotNull(
                overlays
                    .lineSequence()
                    .firstOrNull { it.startsWith("[x]") && it.contains("com.android.internal.systemui.navbar.") }
                    ?.substringAfter("[x] ")
                    ?.trim(),
            )
        try {
            runBlocking { preferences.setEntryStartMode(EntryStartMode.TEXT) }
            for (mode in listOf("gestural", "threebutton")) {
                val overlay = "com.android.internal.systemui.navbar.$mode"
                check(overlays.contains(overlay))
                device.executeShellCommand("cmd overlay enable-exclusive --category $overlay")
                for ((name, orientation) in listOf(
                    "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                )) {
                    val text = (1..24).joinToString("\n\n") { "Retained detail $it from a quiet walk by the water." }
                    val intent = Intent(instrumentation.targetContext, EditorActivity::class.java)
                    ActivityScenario.launch<EditorActivity>(intent).use { scenario ->
                        scenario.onActivity { it.requestedOrientation = orientation }
                        compose.waitUntil(20_000) {
                            var rotated = false
                            scenario.onActivity {
                                rotated =
                                    (it.window.decorView.width > it.window.decorView.height) == (name == "landscape")
                            }
                            rotated && compose.onAllNodesWithTag("editor_text_input").fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.onNodeWithTag("editor_text_input").performClick().performTextInput(text)
                        compose.onNodeWithTag("editor_text_input").assertTextContains(text)
                        if (compose.onAllNodesWithContentDescription("Return to entry").fetchSemanticsNodes().isNotEmpty()) {
                            compose.onNodeWithContentDescription("Return to entry").performClick()
                        }
                        compose.waitUntil(10_000) {
                            var keyboard = true
                            scenario.onActivity {
                                keyboard =
                                    ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                            }
                            !keyboard && compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.waitForIdle()
                        val longText = compose.onNodeWithTag("editor_text_input").getUnclippedBoundsInRoot()
                        val initialCapture = compose.onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
                        assertTrue(longText.bottom > initialCapture.bottom, "Fixture must overflow before any scroll: $longText")
                        assertSafeCapture(scenario, "$mode-$name-initial")
                        check(device.takeScreenshot(File(output, "$mode-$name-initial.png")))
                        compose.onNodeWithTag("entry_page_creation").performScrollTo()
                        compose.waitForIdle()
                        assertSafeCapture(scenario, "$mode-$name-end", expectBalancedMargin = true)
                        val textBounds = compose.onNodeWithTag("editor_text_input").getUnclippedBoundsInRoot()
                        val capture = compose.onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
                        assertTrue(
                            textBounds.bottom.value <= capture.top.value + 1f,
                            "Last content line cannot scroll clear of the selector: $textBounds, $capture",
                        )
                        val screenshot = File(output, "$mode-$name-end.png")
                        check(device.takeScreenshot(screenshot))
                        assertCleanStatusStrip(scenario, screenshot, "$mode-$name-end")
                    }
                }
            }
        } finally {
            device.executeShellCommand("cmd overlay enable-exclusive --category $previousNavigation")
            runBlocking { preferences.setEntryStartMode(previousMode) }
        }
    }

    private fun assertCleanStatusStrip(
        scenario: ActivityScenario<EditorActivity>,
        screenshot: File,
        label: String,
    ) {
        var statusTop = 0
        scenario.onActivity {
            statusTop =
                checkNotNull(ViewCompat.getRootWindowInsets(it.window.decorView)).getInsets(WindowInsetsCompat.Type.statusBars()).top
        }
        check(statusTop > 4) { "The fixture must expose a status strip" }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(screenshot.path))
        try {
            // The synthetic long text card crosses this strip. Avoid Android's left clock
            // and right icons: its center must contain continuous surface paint, never ink.
            val reference = bitmap.getPixel(bitmap.width / 2, 2)
            for (y in 2 until statusTop - 2) {
                for (x in bitmap.width * 35 / 100 until bitmap.width * 65 / 100) {
                    val pixel = bitmap.getPixel(x, y)
                    assertTrue(
                        abs(Color.red(pixel) - Color.red(reference)) <= 3 &&
                            abs(Color.green(pixel) - Color.green(reference)) <= 3 &&
                            abs(Color.blue(pixel) - Color.blue(reference)) <= 3,
                        "$label: inner text/control ink in the status strip at $x,$y",
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSafeCapture(
        scenario: ActivityScenario<EditorActivity>,
        label: String,
        expectBalancedMargin: Boolean = false,
    ) {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        scenario.onActivity {
            val view = it.window.decorView
            val scale = it.resources.displayMetrics.density
            val insets =
                checkNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
                )
            left = insets.left / scale
            top = insets.top / scale
            right = (view.width - insets.right) / scale
            bottom = (view.height - insets.bottom) / scale
        }
        val capture = compose.onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
        if (expectBalancedMargin) {
            val railWidth = minOf(right - left, 640f)
            val railLeft = left + (right - left - railWidth) / 2f
            val startMargin = capture.left.value - railLeft
            val endMargin = railLeft + railWidth - capture.right.value
            val bottomMargin = bottom - capture.bottom.value
            assertTrue(
                abs(startMargin - 16f) <= 1f && abs(endMargin - 16f) <= 1f,
                "$label: wrong inner rail margins $startMargin/$endMargin",
            )
            assertTrue(
                abs(bottomMargin - startMargin) <= 1f,
                "$label: bottom design margin $bottomMargin differs from side margin $startMargin",
            )
        }
        assertTrue(
            capture.top.value >= top - 1 && capture.bottom.value <= bottom + 1,
            "$label selector overlaps system UI: $capture vs $top..$bottom",
        )
        assertTrue(
            capture.left.value >= left - 1 && capture.right.value <= right + 1,
            "$label selector crosses horizontal safe bounds",
        )
    }
}
