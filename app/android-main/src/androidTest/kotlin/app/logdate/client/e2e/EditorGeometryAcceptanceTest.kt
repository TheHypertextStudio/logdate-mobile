package app.logdate.client.e2e

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
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

/** Measured production geometry, including real Android font scaling and IME. */
class EditorGeometryAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 180_000)
    fun minimumBlocksCenteredChooserAndAlignedRailSurviveRotationAndLargeText() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "editor-geometry")
        check(output.mkdirs() || output.isDirectory)
        ensureLogDateApplicationStarted(instrumentation.targetContext.applicationContext as Application)
        val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
        val previousMode = runBlocking { preferences.observeEntryStartMode().first() }
        val previousFont = device.executeShellCommand("settings get system font_scale").trim()
        val previousIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            runBlocking { preferences.setEntryStartMode(EntryStartMode.TEXT) }
            for (scale in listOf(1f, 2f)) {
                device.executeShellCommand("settings put system font_scale $scale")
                val intent =
                    Intent(instrumentation.targetContext, EditorActivity::class.java)
                        .putExtra("initial_text", "A quiet walk by the water.")
                ActivityScenario.launch<EditorActivity>(intent).use { scenario ->
                    compose.waitUntil(20_000) { compose.onAllNodesWithTag("editor_text_input").fetchSemanticsNodes().isNotEmpty() }
                    for ((name, orientation) in listOf(
                        "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    )) {
                        scenario.onActivity { it.requestedOrientation = orientation }
                        compose.waitUntil(15_000) {
                            var ready = false
                            scenario.onActivity {
                                ready = (it.window.decorView.width > it.window.decorView.height) == (name == "landscape") &&
                                    abs(it.resources.configuration.fontScale - scale) < 0.01f
                            }
                            ready && compose.onAllNodesWithTag("editor_text_input").fetchSemanticsNodes().isNotEmpty()
                        }
                        if (compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isEmpty()) {
                            hideIme(scenario, device)
                            returnToOverview()
                        }
                        compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithTag("entry_page_creation").performScrollTo()
                        compose.waitForIdle()
                        assertOverviewGeometry("$name-font$scale")
                        check(device.takeScreenshot(File(output, "$name-font$scale.png")))
                        compose.onNodeWithTag("editor_text_input").performScrollTo().performTouchInput { click() }
                        compose.waitUntil(10_000) {
                            compose.onAllNodesWithContentDescription("Return to entry").fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.waitUntil(10_000) {
                            var ime = false
                            scenario.onActivity {
                                ime =
                                    ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                            }
                            ime
                        }
                        device.waitForIdle(2_000)
                        compose.waitForIdle()
                        // A short keyboard viewport may be smaller than the inline block minimum;
                        // its writing area must remain readable rather than enforcing an impossible fixed height.
                        val writing = compose.onNodeWithTag("editor_text_input").getUnclippedBoundsInRoot()
                        assertTrue(writing.bottom.value - writing.top.value >= 24f, "No writing line at $name/$scale: $writing")
                        if (scale == 2f) check(device.takeScreenshot(File(output, "$name-font$scale-keyboard.png")))
                        hideIme(scenario, device)
                        returnToOverview()
                    }
                }
            }
        } finally {
            device.executeShellCommand(
                if (previousIme ==
                    "null"
                ) {
                    "settings delete secure show_ime_with_hard_keyboard"
                } else {
                    "settings put secure show_ime_with_hard_keyboard $previousIme"
                },
            )
            device.executeShellCommand(
                if (previousFont ==
                    "null"
                ) {
                    "settings delete system font_scale"
                } else {
                    "settings put system font_scale $previousFont"
                },
            )
            runBlocking { preferences.setEntryStartMode(previousMode) }
        }
    }

    private fun returnToOverview() {
        if (compose.onAllNodesWithContentDescription("Return to entry").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Return to entry").performTouchInput { click() }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hideIme(
        scenario: ActivityScenario<EditorActivity>,
        device: UiDevice,
    ) {
        var visible = false
        scenario.onActivity {
            visible =
                ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        if (visible) device.pressBack()
        compose.waitUntil(10_000) {
            scenario.onActivity {
                visible =
                    ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            !visible
        }
        compose.waitForIdle()
    }

    private fun assertOverviewGeometry(label: String) {
        val chooser = compose.onNodeWithTag("entry_journal_chooser").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("entry_mode_overview").getUnclippedBoundsInRoot()
        val memory =
            compose
                .onNode(
                    SemanticsMatcher("memory surface") {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("memory_block_")
                    },
                ).getUnclippedBoundsInRoot()
        val first = compose.onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
        val last = first
        assertTrue(
            abs((chooser.left + chooser.right).value - (header.left + header.right).value) <= 1f,
            "$label chooser is off center: $chooser, $header",
        )
        assertTrue(memory.bottom.value - memory.top.value >= 119f, "$label block collapsed: $memory")
        assertTrue(
            abs((memory.left - first.left).value) <= 1f && abs((memory.right - last.right).value) <= 1f,
            "$label block and mode rail edges differ: $memory, $first, $last",
        )
    }
}
