package app.logdate.client.e2e

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.MainActivity
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.testing.onboarding.DEBUG_SKIP_ONBOARDING_EXTRA
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Real app, OS-injected taps and unedited normal-speed recording; only an owned Managed Device. */
class EditorEntrySwitchVideoTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 120_000)
    fun homeToEmptyTypeSwitchAndContentHidesCreationChoices() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ensureLogDateApplicationStarted(context.applicationContext as Application)
        val device = UiDevice.getInstance(instrumentation)
        val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
        val previousMode = runBlocking { preferences.observeEntryStartMode().first() }
        val previousTouches = device.executeShellCommand("settings get system show_touches").trim()
        val previousIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val previousBack = device.executeShellCommand("settings get global enable_back_animation").trim()
        val motionKeys = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
        val previousMotion = motionKeys.associateWith { device.executeShellCommand("settings get global $it").trim() }
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "editor-entry-switch")
        output.mkdirs()
        val token = Uuid.random().toString()
        val remoteVideo = "/sdcard/logdate-entry-switch-$token.mp4"
        val remotePid = "/sdcard/logdate-entry-switch-$token.pid"
        val video = File(output, "home-entry-type-switch.mp4")
        try {
            device.executeShellCommand("settings put system show_touches 1")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            check(device.executeShellCommand("settings get secure navigation_mode").trim() == "2") {
                "This physical predictive gesture requires the configured gestural managed device"
            }
            device.executeShellCommand("settings put global enable_back_animation 1")
            motionKeys.forEach { key ->
                device.executeShellCommand("settings put global $key 1")
                check(device.executeShellCommand("settings get global $key").trim().toFloat() == 1f)
            }
            runBlocking { preferences.setEntryStartMode(EntryStartMode.TEXT) }
            val intent = Intent(context, MainActivity::class.java).putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                val create = hasText("Create Entry") or hasContentDescription("Create new entry") or hasContentDescription("Add a memory")
                compose.waitUntil(25_000) { compose.onAllNodes(create).fetchSemanticsNodes().isNotEmpty() }
                device.waitForIdle(2_000)
                val executor = Executors.newSingleThreadExecutor()
                val script = File(output, "record-screen-$token.sh")
                script.writeText(
                    "#!/system/bin/sh\necho \$\$ > $remotePid\n" +
                        "exec screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 30 $remoteVideo\n",
                )
                val command = "sh ${script.absolutePath}"
                val stream = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                val reader =
                    executor.submit {
                        stream.use { input ->
                            File(output, "screenrecord.log").outputStream().use { outputStream -> input.copyTo(outputStream) }
                        }
                    }
                var pid: Int? = null
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    compose.waitUntil(5_000) {
                        pid = device.executeShellCommand("cat $remotePid").trim().toIntOrNull()
                        pid != null
                    }
                    SystemClock.sleep(2_000)
                    tap(device, compose.onAllNodes(create).onFirst())
                    compose.waitUntil(15_000) { compose.onAllNodesWithTag("editor_text_input").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("entry_capture_tools").assertIsDisplayed()
                    SystemClock.sleep(1_500)
                    tap(device, compose.onNodeWithContentDescription("Handwrite"))
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("handwriting_focus_surface").fetchSemanticsNodes().isNotEmpty() }
                    SystemClock.sleep(2_000)
                    tap(device, compose.onNodeWithContentDescription("Write"))
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_text_input").fetchSemanticsNodes().isNotEmpty() }
                    SystemClock.sleep(1_500)
                    tap(device, compose.onNodeWithTag("editor_text_input"))
                    for (word in listOf("A", "%squiet", "%smoment.")) {
                        device.executeShellCommand("input text $word")
                        SystemClock.sleep(500)
                    }
                    compose.onNodeWithTag("editor_text_input").assertTextContains("A quiet moment.")
                    compose.waitForIdle()
                    compose.onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
                    SystemClock.sleep(2_000)
                    tap(device, compose.onNodeWithContentDescription("Return to entry"))
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("entry_add_memory").assertIsDisplayed()
                    SystemClock.sleep(500)
                    tap(device, compose.onNodeWithTag("editor_text_input"))
                    waitForIme(scenario, true)
                    device.pressBack()
                    waitForIme(scenario, false)
                    // The focused text header has compact actions and no redundant type title.
                    val chrome = compose.onNodeWithTag("entry_mode_focus").fetchSemanticsNode().boundsInWindow
                    check(device.click(chrome.center.x.roundToInt(), chrome.center.y.roundToInt()))
                    val fieldId = compose.onNodeWithTag("editor_text_input").fetchSemanticsNode().id

                    fun focusProgress(): Float =
                        compose
                            .onAllNodes(
                                SemanticsMatcher("editor surface progress") { node ->
                                    node.config.any { it.key.name == "BlockFocusProgress" }
                                },
                            ).fetchSemanticsNodes()
                            .single()
                            .config
                            .first { it.key.name == "BlockFocusProgress" }
                            .value as Float

                    fun semanticsTrees(): String {
                        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                        return roots.fetchSemanticsNodes().indices.joinToString("\n") { index ->
                            "Root $index\n" + roots[index].printToString()
                        }
                    }
                    val back = ManagedEditorBackGesture(device)
                    File(output, "predictive-before.txt").writeText(
                        "progress=${focusProgress()}\n" + semanticsTrees(),
                    )
                    check(device.takeScreenshot(File(output, "predictive-before.png")))
                    back.perform(cancel = true) {
                        File(output, "predictive-partial.txt").writeText(
                            "progress=${focusProgress()}\n" + semanticsTrees(),
                        )
                        check(device.takeScreenshot(File(output, "predictive-partial-observed.png")))
                        compose.waitUntil(5_000) { focusProgress() in 0.01f..0.99f }
                        check(device.takeScreenshot(File(output, "predictive-back-partial.png")))
                    }
                    compose.waitUntil(5_000) { focusProgress() >= 0.99f }
                    check(compose.onNodeWithTag("editor_text_input").fetchSemanticsNode().id == fieldId)
                    compose.onNodeWithTag("editor_text_input").assertTextContains("A quiet moment.")
                    back.perform(cancel = false) {
                        compose.waitUntil(5_000) { focusProgress() in 0.01f..0.99f }
                    }
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("editor_text_input").assertTextContains("A quiet moment.")
                    assertTrue(!reader.isDone, "The full journey must finish within the bounded normal-speed recording")
                    val remaining = 12_000 - (SystemClock.elapsedRealtime() - startedAt)
                    if (remaining > 0) SystemClock.sleep(remaining)
                    check(device.takeScreenshot(File(output, "typed-memory-retained.png")))
                } finally {
                    pid?.let { device.executeShellCommand("kill -2 $it") }
                    try {
                        reader.get(35, TimeUnit.SECONDS)
                    } finally {
                        executor.shutdown()
                        executor.awaitTermination(5, TimeUnit.SECONDS)
                    }
                    device.executeShellCommand("cp $remoteVideo ${video.absolutePath}")
                    device.executeShellCommand("rm -f $remoteVideo $remotePid")
                }
                assertTrue(video.isFile && video.length() > 10_000, "Screen recording was not retained")
                tap(device, compose.onNodeWithTag("editor_text_input"))
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry_mode_focus").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(10_000) {
                    var keyboard = false
                    scenario.onActivity {
                        keyboard =
                            androidx.core.view.WindowInsetsCompat
                                .toWindowInsetsCompat(it.window.decorView.rootWindowInsets)
                                .isVisible(
                                    androidx.core.view.WindowInsetsCompat.Type
                                        .ime(),
                                )
                    }
                    keyboard
                }
                device.pressBack() // Close the real IME before comparing viewport geometry.
                compose.waitUntil(10_000) {
                    var keyboard = true
                    scenario.onActivity {
                        keyboard =
                            androidx.core.view.WindowInsetsCompat
                                .toWindowInsetsCompat(it.window.decorView.rootWindowInsets)
                                .isVisible(
                                    androidx.core.view.WindowInsetsCompat.Type
                                        .ime(),
                                )
                    }
                    !keyboard
                }
                compose.waitForIdle()
                check(device.takeScreenshot(File(output, "normal-mobile-focused-writing.png")))
                ManagedEditorWindowCaptures(device, compose, scenario, output) {}.captureWritingSizes()
            }
        } finally {
            runBlocking { preferences.setEntryStartMode(previousMode) }
            restoreSetting(device, "system", "show_touches", previousTouches)
            restoreSetting(device, "secure", "show_ime_with_hard_keyboard", previousIme)
            restoreSetting(device, "global", "enable_back_animation", previousBack)
            previousMotion.forEach { (key, value) -> restoreSetting(device, "global", key, value) }
        }
    }

    private fun waitForIme(
        scenario: ActivityScenario<MainActivity>,
        visible: Boolean,
    ) {
        compose.waitUntil(10_000) {
            var actual = !visible
            scenario.onActivity {
                actual =
                    androidx.core.view.WindowInsetsCompat
                        .toWindowInsetsCompat(it.window.decorView.rootWindowInsets)
                        .isVisible(
                            androidx.core.view.WindowInsetsCompat.Type
                                .ime(),
                        )
            }
            actual == visible
        }
    }

    private fun tap(
        device: UiDevice,
        node: SemanticsNodeInteraction,
    ) {
        node.assertIsDisplayed()
        val center = node.fetchSemanticsNode().boundsInRoot.center
        check(device.click(center.x.roundToInt(), center.y.roundToInt()))
    }

    private fun restoreSetting(
        device: UiDevice,
        namespace: String,
        name: String,
        previous: String,
    ) {
        device.executeShellCommand(
            if (previous ==
                "null"
            ) {
                "settings delete $namespace $name"
            } else {
                "settings put $namespace $name $previous"
            },
        )
    }
}
