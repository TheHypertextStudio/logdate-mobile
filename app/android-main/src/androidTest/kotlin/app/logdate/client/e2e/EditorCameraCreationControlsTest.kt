package app.logdate.client.e2e

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.logdate.client.MainActivity
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.testing.onboarding.DEBUG_SKIP_ONBOARDING_EXTRA
import app.logdate.ui.media.MediaDeviceSelectorTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class EditorCameraCreationControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @Test(timeout = 180_000)
    fun physicalCameraCaptureAndReviewStayClearOfCreationChoicesAndTransparentBars() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ensureLogDateApplicationStarted(context.applicationContext as Application)
        val device = UiDevice.getInstance(instrumentation)
        val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
        val previousMode = runBlocking { preferences.observeEntryStartMode().first() }
        val previousHardwareIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val overlays = device.executeShellCommand("cmd overlay list")
        val previousNavigation =
            checkNotNull(
                overlays.lineSequence().firstOrNull {
                    it.startsWith("[x]") && it.contains("com.android.internal.systemui.navbar.")
                },
            ).substringAfter("[x] ").trim()
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "editor-camera-creation",
            )
        output.mkdirs()
        try {
            runBlocking { preferences.setEntryStartMode(EntryStartMode.TEXT) }
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.executeShellCommand("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
            val intent = Intent(context, MainActivity::class.java).putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                try {
                    val create =
                        hasText("Create Entry") or hasContentDescription("Create new entry") or hasContentDescription("Add a memory")
                    compose.waitUntil(20_000) { compose.onAllNodes(create).fetchSemanticsNodes().isNotEmpty() }
                    tap(device, scenario, compose.onAllNodes(create).onFirst())
                    compose.waitUntil(20_000) {
                        compose.onAllNodes(hasContentDescription("Camera") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    awaitImeAndCameraTarget(scenario, output)
                    check(device.takeScreenshot(File(output, "camera-choice-before-tap.png")))
                    tap(device, scenario, compose.onNodeWithContentDescription("Camera").assertIsEnabled())
                    compose.waitUntil(20_000) {
                        compose.onAllNodes(hasContentDescription("Take Photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    check(device.takeScreenshot(File(output, "camera-before-stream.png")))
                    compose.waitUntil(30_000) {
                        var streaming = false
                        scenario.onActivity { streaming = previewStreaming(it.window.decorView) }
                        streaming
                    }
                    val focused =
                        compose
                            .onAllNodes(
                                SemanticsMatcher("focused camera block") {
                                    it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("focused_block_")
                                },
                            ).fetchSemanticsNodes()
                            .single()
                            .config[SemanticsProperties.TestTag]
                    val route = compose.onNodeWithTag(MediaDeviceSelectorTags.chip("Camera")).assertIsDisplayed().assertIsEnabled()
                    val backBounds = compose.onNodeWithContentDescription("Return to entry").fetchSemanticsNode().boundsInRoot
                    assertTrue(route.fetchSemanticsNode().boundsInRoot.top >= backBounds.bottom, "Camera route must clear Return to entry")
                    tap(device, scenario, route)
                    compose.onNodeWithTag(MediaDeviceSelectorTags.sheet("Camera")).assertIsDisplayed()
                    device.pressBack()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag(MediaDeviceSelectorTags.sheet("Camera")).fetchSemanticsNodes().isEmpty()
                    }
                    val shutter = compose.onNodeWithContentDescription("Take Photo").assertIsDisplayed().assertIsEnabled()
                    val shutterBounds = shutter.fetchSemanticsNode().boundsInRoot
                    compose.onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
                    compose.onNodeWithContentDescription("Write").assertIsNotDisplayed()
                    scenario.onActivity {
                        val view = it.window.decorView
                        val insets =
                            WindowInsetsCompat
                                .toWindowInsetsCompat(view.rootWindowInsets)
                                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        assertTrue(
                            shutterBounds.bottom <= view.height - insets.bottom,
                            "Camera owns the lower controls and clears system UI",
                        )
                    }
                    check(device.takeScreenshot(File(output, "mobile-camera-shutter.png")))
                    val windowSizes =
                        ManagedEditorWindowCaptures(device, compose, scenario, output) {
                            compose.waitUntil(30_000) {
                                var streaming = false
                                scenario.onActivity { streaming = previewStreaming(it.window.decorView) }
                                streaming
                            }
                        }
                    windowSizes.captureCameraSizes()
                    tap(device, scenario, compose.onNodeWithContentDescription("Take Photo"))
                    compose.waitUntil(20_000) { compose.onAllNodesWithText("Use Photo").fetchSemanticsNodes().isNotEmpty() }
                    windowSizes.awaitReviewImage()
                    var safeBottom = 0
                    var windowHeight = 0
                    scenario.onActivity {
                        val window = it.window
                        assertEquals(0, Color.alpha(window.statusBarColor))
                        assertEquals(0, Color.alpha(window.navigationBarColor))
                        assertFalse(window.isNavigationBarContrastEnforced)
                        assertFalse(window.isStatusBarContrastEnforced)
                        val icons = WindowInsetsControllerCompat(window, window.decorView)
                        assertFalse(icons.isAppearanceLightStatusBars, "Camera status icons must be light over its dark preview")
                        assertFalse(icons.isAppearanceLightNavigationBars)
                        safeBottom =
                            WindowInsetsCompat
                                .toWindowInsetsCompat(window.decorView.rootWindowInsets)
                                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                .bottom
                        windowHeight = window.decorView.height
                    }
                    for (label in listOf("Retake", "Use Photo")) {
                        val action = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
                        val bounds = action.fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.bottom <= windowHeight - safeBottom, "$label must clear navigation UI")
                        val dock = compose.onNodeWithTag("entry_creation_actions").fetchSemanticsNode().boundsInRoot
                        if (dock.height > 0f) assertTrue(bounds.bottom <= dock.top, "$label must clear an exiting creation surface")
                    }
                    check(device.takeScreenshot(File(output, "mobile-camera-review.png")))
                    windowSizes.captureReviewSizes()
                    tap(device, scenario, compose.onNodeWithText("Use Photo"))
                    compose.waitUntil(
                        15_000,
                    ) { compose.onAllNodesWithContentDescription("Captured photo").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag(focused).assertIsDisplayed()
                    compose.onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
                    val back = ManagedEditorBackGesture(device)
                    back.perform(cancel = true) {
                        compose.onNodeWithTag(focused).assertIsDisplayed()
                        compose.onNodeWithContentDescription("Captured photo").assertIsDisplayed()
                        compose.onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
                        check(device.takeScreenshot(File(output, "accepted-photo-back-partial.png")))
                    }
                    compose.onNodeWithTag(focused).assertIsDisplayed()
                    device.pressBack()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("entry_add_memory").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Image").assertIsDisplayed()
                    tap(device, scenario, compose.onNodeWithContentDescription("Image"))
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag(focused).fetchSemanticsNodes().isNotEmpty() }
                    device.pressBack()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag("entry_journal_chooser").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithContentDescription("Image").assertIsDisplayed()
                    check(device.takeScreenshot(File(output, "normal-mobile-accepted-photo-overview.png")))
                    windowSizes.captureOverviewSizes()
                    ManagedSystemBarCaptures(device, compose, scenario, output).captureGestureAndThreeButtonBars()
                } finally {
                    device.takeScreenshot(File(output, "camera-terminal.png"))
                    scenario.onActivity { activity ->
                        val rows = mutableListOf<String>()

                        fun visit(
                            view: View,
                            depth: Int,
                        ) {
                            if (rows.size >= 200) return
                            val state = if (view is PreviewView) " preview=${view.previewStreamState.value}" else ""
                            rows += "${" ".repeat(depth)}${view.javaClass.simpleName} ${view.width}x${view.height}$state"
                            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
                        }
                        visit(activity.window.decorView, 0)
                        File(output, "camera-terminal-views.txt").writeText(rows.joinToString("\n"))
                    }
                }
            }
        } finally {
            device.executeShellCommand("cmd overlay enable-exclusive --category $previousNavigation")
            runBlocking { preferences.setEntryStartMode(previousMode) }
            device.executeShellCommand(
                if (previousHardwareIme == "null") {
                    "settings delete secure show_ime_with_hard_keyboard"
                } else {
                    "settings put secure show_ime_with_hard_keyboard $previousHardwareIme"
                },
            )
        }
    }

    private fun awaitImeAndCameraTarget(
        scenario: ActivityScenario<MainActivity>,
        output: File,
    ) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("editor_text_input") and isFocused()).fetchSemanticsNodes().size == 1
        }
        var previous: Triple<Rect, Int, Pair<Int, Int>>? = null
        var unchangedSamples = 0
        val observations = ArrayDeque<String>()
        compose.waitUntil(10_000) {
            var imeBottom = 0
            var windowHeight = 0
            var imeVisible = false
            var origin = 0 to 0
            scenario.onActivity {
                val decor = it.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                windowHeight = decor.height
                val screen = IntArray(2)
                val window = IntArray(2)
                decor.getLocationOnScreen(screen)
                decor.getLocationInWindow(window)
                origin = screen[0] - window[0] to screen[1] - window[1]
            }
            val camera = compose.onNodeWithContentDescription("Camera").fetchSemanticsNode()
            val bounds = camera.boundsInWindow
            val current = Triple(bounds, imeBottom, origin)
            unchangedSamples = if (current == previous) unchangedSamples + 1 else 0
            previous = current
            observations.addLast(
                "root=${camera.boundsInRoot} window=$bounds screenOrigin=$origin " +
                    "imeVisible=$imeVisible imeBottom=$imeBottom windowHeight=$windowHeight stable=$unchangedSamples",
            )
            if (observations.size > 20) observations.removeFirst()
            File(output, "camera-choice-readiness.txt").writeText(observations.joinToString("\n"))
            imeVisible && imeBottom > 0 && bounds.bottom <= windowHeight - imeBottom && unchangedSamples >= 2
        }
    }

    private fun previewStreaming(view: View): Boolean =
        when (view) {
            is PreviewView -> view.previewStreamState.value == PreviewView.StreamState.STREAMING
            is ViewGroup -> (0 until view.childCount).any { previewStreaming(view.getChildAt(it)) }
            else -> false
        }

    private fun tap(
        device: UiDevice,
        scenario: ActivityScenario<MainActivity>,
        node: SemanticsNodeInteraction,
    ) {
        compose.waitForIdle()
        device.waitForIdle(2_000)
        node.assertIsDisplayed().assertIsEnabled()
        val center = node.fetchSemanticsNode().boundsInWindow.center
        var origin = 0 to 0
        scenario.onActivity {
            val screen = IntArray(2)
            val window = IntArray(2)
            it.window.decorView.getLocationOnScreen(screen)
            it.window.decorView.getLocationInWindow(window)
            origin = screen[0] - window[0] to screen[1] - window[1]
        }
        check(device.click(center.x.roundToInt() + origin.first, center.y.roundToInt() + origin.second))
    }
}
