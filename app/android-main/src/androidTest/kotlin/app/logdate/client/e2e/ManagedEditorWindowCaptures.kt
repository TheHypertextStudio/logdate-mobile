package app.logdate.client.e2e

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.UiDevice
import app.logdate.client.MainActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Resizes only the owned managed emulator; these are window-size checks, not hinge hardware proof. */
@Suppress("DEPRECATION")
internal class ManagedEditorWindowCaptures(
    private val device: UiDevice,
    private val compose: ComposeTestRule,
    private val scenario: ActivityScenario<MainActivity>,
    private val output: File,
    private val awaitCamera: () -> Unit,
) {
    fun captureCameraSizes() =
        captureSizes("camera", awaitCamera) {
            val shutter =
                compose
                    .onNodeWithContentDescription("Take Photo")
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .fetchSemanticsNode()
                    .boundsInRoot
            // In Camera mode the dock reads as a row of words under the shutter.
            val wordRow =
                compose
                    .onNodeWithTag("entry_capture_word_row")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(shutter.bottom <= wordRow.top, "The shutter must sit above the word row")
            compose.onNodeWithTag("entry_capture_word_Camera").assertIsSelected()
            scenario.onActivity {
                val view = it.window.decorView
                val safe =
                    WindowInsetsCompat
                        .toWindowInsetsCompat(view.rootWindowInsets)
                        .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                assertTrue(shutter.bottom <= view.height - safe.bottom, "Shutter must clear system navigation")
                assertEquals(
                    (view.width + safe.left - safe.right) / 2f,
                    shutter.center.x,
                    1f,
                    "Camera controls share the safe scene center",
                )
            }
        }

    fun captureWritingSizes() =
        captureSizes("writing", {
            compose.onNodeWithTag("editor_text_input").assertIsDisplayed().assertTextContains("A quiet moment.")
        }) {
            assertEquals(1, compose.onAllNodesWithTag("editor_text_input").fetchSemanticsNodes().size)
            val field = compose.onNodeWithTag("editor_text_input").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithContentDescription("Return to entry").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("Save entry").assertIsDisplayed().assertIsEnabled()
            scenario.onActivity {
                val view = it.window.decorView
                val safe =
                    WindowInsetsCompat
                        .toWindowInsetsCompat(view.rootWindowInsets)
                        .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                assertTrue(field.top >= safe.top, "Writing must remain below system controls")
                assertTrue(field.bottom <= view.height - safe.bottom + 1f, "Writing must remain above system controls")
                assertEquals((view.width + safe.left - safe.right) / 2f, field.center.x, 1f)
            }
        }

    fun captureOverviewSizes() =
        captureSizes("overview", {
            compose.onNodeWithTag("entry_journal_chooser").assertIsDisplayed()
            compose.onNodeWithTag("entry_add_memory").assertIsDisplayed()
        }) {
            val memories =
                compose.onAllNodes(
                    SemanticsMatcher("retained memory surface") {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("memory_block_")
                    },
                )
            val original = memories.fetchSemanticsNodes().single()
            val initialDock = compose.onNodeWithTag("entry_creation_actions").fetchSemanticsNode().boundsInWindow
            var density = 1f
            var screenX = 0
            var screenY = 0
            scenario.onActivity {
                density = it.resources.displayMetrics.density
                val screen = IntArray(2)
                val window = IntArray(2)
                it.window.decorView.getLocationOnScreen(screen)
                it.window.decorView.getLocationInWindow(window)
                screenX = screen[0] - window[0]
                screenY = screen[1] - window[1]
            }
            val initial = File(output, "${device.displayWidth}x${device.displayHeight}-overview-before-reveal.png")
            check(device.takeScreenshot(initial))
            val bitmap = checkNotNull(BitmapFactory.decodeFile(initial.path))
            try {
                for (edge in listOf(initialDock.top - 8 * density, initialDock.bottom + 8 * density)) {
                    val y = edge.toInt() + screenY
                    val canvas = bitmap.getPixel(2 + screenX, y)
                    for (x in initialDock.left.toInt() + screenX until initialDock.right.toInt() + screenX) {
                        val pixel = bitmap.getPixel(x, y)
                        assertTrue(
                            abs(Color.red(canvas) - Color.red(pixel)) <= 3 &&
                                abs(Color.green(canvas) - Color.green(pixel)) <= 3 &&
                                abs(Color.blue(canvas) - Color.blue(pixel)) <= 3,
                            "Scrolling photo paint must leave the dock clearance and lower canvas continuous at $x,$y",
                        )
                    }
                }
            } finally {
                bitmap.recycle()
            }
            check(device.click(initialDock.center.x.toInt() + screenX, (initialDock.top - 8 * density).toInt() + screenY))
            compose.onNodeWithTag("entry_journal_chooser").assertIsDisplayed()
            assertEquals(original.id, memories.fetchSemanticsNodes().single().id, "The clear gap cannot focus or replace the photo")
            // A fitted photo may be taller than a resized window. Reveal its real lower edge,
            // rather than requiring the entire original image to shrink into that window.
            compose.onNodeWithTag("entry_page_creation").performScrollTo()
            compose.waitForIdle()
            val revealed = memories.fetchSemanticsNodes().single()
            assertEquals(original.id, revealed.id, "Revealing the lower edge retains the same photo surface")
            compose.onNodeWithTag("entry_journal_chooser").assertIsDisplayed()
            val memory = revealed.boundsInWindow
            val dock = compose.onNodeWithTag("entry_creation_actions").fetchSemanticsNode().boundsInWindow
            scenario.onActivity {
                assertEquals(memory.left, dock.left, 1f, "Memory and Add share the content rail")
                assertEquals(memory.right, dock.right, 1f, "Memory and Add share the content rail")
                assertTrue(memory.bottom + 16 * density <= dock.top + 1f, "Memory and Add retain the same 16dp design clearance")
            }
        }

    private fun captureSizes(
        stage: String,
        awaitReady: () -> Unit,
        validate: () -> Unit,
    ) {
        val oldSize = overrideValue(device.executeShellCommand("wm size"))
        val oldDensity = overrideValue(device.executeShellCommand("wm density"))
        var originalWidth = 0
        var originalHeight = 0
        scenario.onActivity {
            originalWidth = it.window.decorView.width
            originalHeight = it.window.decorView.height
        }
        try {
            for ((label, width) in listOf("foldable-sized" to 1000, "landscape-tablet" to 1280)) {
                device.executeShellCommand("wm density 160")
                device.executeShellCommand("wm size ${width}x800")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                awaitViewport(width, 800, stage)
                awaitReady()
                validate()
                scenario.onActivity {
                    assertEquals(0, Color.alpha(it.window.statusBarColor))
                    assertEquals(0, Color.alpha(it.window.navigationBarColor))
                    assertFalse(it.window.isNavigationBarContrastEnforced)
                    assertFalse(it.window.isStatusBarContrastEnforced)
                }
                check(device.takeScreenshot(File(output, "$label-$stage-window-${width}x800.png")))
                File(output, "$label-$stage-window.txt").writeText(
                    "Native app on API34 managed emulator, resized ${width}x800px at density160. " +
                        "Synthetic editor content. No physical foldable/hinge behavior claimed.\n",
                )
            }
        } finally {
            device.executeShellCommand("wm size $oldSize")
            device.executeShellCommand("wm density $oldDensity")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitViewport(originalWidth, originalHeight, stage)
            awaitReady()
        }
    }

    private fun awaitViewport(
        width: Int,
        height: Int,
        stage: String,
    ) {
        val observations = ArrayDeque<String>()
        var previous: String? = null
        var stable = 0
        compose.waitUntil(15_000) {
            var decorMatches = false
            var expectedCenter = width / 2f
            var expectedRail = 0f
            var previewCenter: Float? = null
            scenario.onActivity {
                val decor = it.window.decorView
                decorMatches = decor.width == width && decor.height == height
                val safe =
                    WindowInsetsCompat
                        .toWindowInsetsCompat(decor.rootWindowInsets)
                        .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val density = it.resources.displayMetrics.density
                expectedCenter = (width + safe.left - safe.right) / 2f
                expectedRail =
                    if (stage ==
                        "camera"
                    ) {
                        72 * density
                    } else {
                        min(width.toFloat() - safe.left - safe.right, 640 * density) -
                            if (stage == "writing") 0f else 32 * density
                    }
                findPreview(decor)?.let { preview ->
                    val origin = IntArray(2)
                    preview.getLocationInWindow(origin)
                    previewCenter = origin[0] + preview.width / 2f
                }
            }
            val root = compose.onNodeWithTag("stories_editor").fetchSemanticsNode().boundsInWindow
            val target =
                if (stage == "camera") {
                    compose.onNodeWithContentDescription("Take Photo").fetchSemanticsNode().boundsInWindow
                } else {
                    compose
                        .onNodeWithTag(
                            when (stage) {
                                "overview" -> "entry_creation_actions"
                                "writing" -> "editor_text_input"
                                else -> "camera_review_surface"
                            },
                        ).fetchSemanticsNode()
                        .boundsInWindow
                }
            val current = "root=$root rail=$target previewCenter=$previewCenter expectedRail=$expectedRail expectedCenter=$expectedCenter"
            stable = if (current == previous) stable + 1 else 0
            previous = current
            observations.addLast("$current stable=$stable")
            if (observations.size > 20) observations.removeFirst()
            File(output, "$stage-${width}x$height-viewport-readiness.txt").writeText(observations.joinToString("\n"))
            decorMatches &&
                abs(root.width - width) <= 1f &&
                abs(root.height - height) <= 1f &&
                abs(target.center.x - expectedCenter) <= 1f &&
                abs(target.width - expectedRail) <= 1f &&
                (stage != "camera" || previewCenter?.let { abs(it - width / 2f) <= 1f } == true) &&
                stable >= 2
        }
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPreview(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun overrideValue(report: String): String =
        report
            .lineSequence()
            .firstOrNull { it.startsWith("Override") }
            ?.substringAfter(": ")
            ?.trim() ?: "reset"
}
