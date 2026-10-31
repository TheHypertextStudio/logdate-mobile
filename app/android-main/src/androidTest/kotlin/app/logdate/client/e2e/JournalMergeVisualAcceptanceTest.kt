package app.logdate.client.e2e

import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.logdate.client.repository.journals.JournalMergeCandidate
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.feature.journals.ui.merge.JournalMergeScreenContent
import app.logdate.feature.journals.ui.merge.JournalMergeStage
import app.logdate.feature.journals.ui.merge.JournalMergeUiState
import app.logdate.shared.model.Journal
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** Actual Android palette and animator-scale acceptance. Run only on an API 31+ Managed Device. */
class JournalMergeVisualAcceptanceTest {
    // The test rule replaces WindowRecomposer, so supply its Android settings adapter explicitly.
    private val systemMotionScale =
        object : MotionDurationScale {
            override val scaleFactor: Float
                get() =
                    Settings.Global.getFloat(
                        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE,
                        1f,
                    )
        }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = systemMotionScale)

    @Test(timeout = 120_000)
    fun populatedDynamicColorsAndSystemReducedMotion() {
        assertTrue("Dynamic color acceptance needs Android 12 or newer", Build.VERSION.SDK_INT >= 31)
        assertTrue("System settings may only be changed on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output =
            File(
                requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
                    "Run this acceptance test with a Gradle Managed Device"
                },
                "journal-merge-native",
            ).apply { mkdirs() }
        val cover = File(compose.activity.cacheDir, "journal-merge-acceptance-cover.jpg")
        compose.activity.assets.open("sample_note_photo.jpg").use { input ->
            cover.outputStream().use { input.copyTo(it) }
        }
        val source = Journal(title = "Summer in the mountains", coverImageUri = cover.toURI().toString())
        val destination = Journal(title = "Journeys with friends and family", coverImageUri = cover.toURI().toString())
        val preview = JournalMergePreview(source, destination, ids(1..12), ids(9..36))
        val picker =
            JournalMergeUiState(
                sourceTitle = source.title,
                source = JournalMergeCandidate(source, 12),
                loading = false,
                candidates =
                    listOf(
                        JournalMergeCandidate(destination, 28),
                        JournalMergeCandidate(Journal(title = "Weekend field notes", coverImageUri = cover.toURI().toString()), 16),
                        JournalMergeCandidate(Journal(title = "The road to the coast and everything we found"), 42),
                    ),
            )
        val scene = mutableStateOf(Scene("dynamic-light-picker"))
        val primary = mutableStateOf<Color?>(null)
        val motionScale = mutableStateOf<Float?>(null)
        val oldScale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            compose.setContent {
                key(scene.value) {
                    LogDateTheme(dynamicColor = true, darkTheme = scene.value.dark) {
                        val resolvedPrimary = MaterialTheme.colorScheme.primary
                        SideEffect {
                            primary.value = resolvedPrimary
                            val window = compose.activity.window
                            WindowInsetsControllerCompat(window, window.decorView).apply {
                                isAppearanceLightStatusBars = !scene.value.dark
                                isAppearanceLightNavigationBars = !scene.value.dark
                            }
                        }
                        LaunchedEffect(Unit) {
                            val scale = coroutineContext[MotionDurationScale]
                            snapshotFlow { scale?.scaleFactor }.collect { motionScale.value = it }
                        }
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalWorkspaceSearchAction provides {},
                            LocalDensity provides Density(density.density, scene.value.fontScale),
                        ) {
                            JournalMergeScreenContent(
                                if (scene.value.review) picker.copy(stage = JournalMergeStage.Review(preview)) else picker,
                            )
                        }
                    }
                }
            }
            listOf(
                Scene("dynamic-light-picker"),
                Scene("dynamic-light-review", review = true),
                Scene("dynamic-dark-picker", dark = true),
                Scene("dynamic-dark-review", review = true, dark = true),
                Scene("dynamic-large-text-review", review = true, fontScale = 2f),
            ).forEach { variant ->
                compose.runOnIdle { scene.value = variant }
                compose.waitUntil(10_000) { motionScale.value == 1f }
                checkPalette(variant) { primary.value }
                capture(device, output, variant)
            }
            device.executeShellCommand("settings put global animator_duration_scale 0")
            assertEquals(0f, device.executeShellCommand("settings get global animator_duration_scale").trim().toFloat())
            listOf(
                Scene("reduced-motion-picker"),
                Scene("reduced-motion-review", review = true),
            ).forEach { variant ->
                compose.runOnIdle { scene.value = variant }
                compose.waitUntil(10_000) { motionScale.value == 0f }
                checkPalette(variant) { primary.value }
                capture(device, output, variant)
            }
        } finally {
            if (oldScale == "null") {
                device.executeShellCommand("settings delete global animator_duration_scale")
            } else {
                device.executeShellCommand("settings put global animator_duration_scale $oldScale")
            }
            cover.delete()
        }
    }

    private fun checkPalette(
        scene: Scene,
        actual: () -> Color?,
    ) {
        compose.waitForIdle()
        val expected =
            if (scene.dark) dynamicDarkColorScheme(compose.activity) else dynamicLightColorScheme(compose.activity)
        compose.runOnIdle { assertEquals("The screen must use Android's actual dynamic palette", expected.primary, actual()) }
    }

    private fun capture(
        device: UiDevice,
        output: File,
        scene: Scene,
    ) {
        compose.onAllNodesWithTag("workspace_search").assertCountEquals(0)
        compose.onNodeWithTag("journal_merge_activity").assertIsDisplayed()
        compose.waitForIdle()
        device.waitForIdle(1000)
        assertNoAnr(device)
        val name = "${scene.name}-${device.displayWidth}x${device.displayHeight}"
        assertTrue("Native screenshot failed", device.takeScreenshot(File(output, "$name.png")))
        if (scene.review) {
            compose.onNodeWithText("Merge journals").performScrollTo()
            compose.onNodeWithText("Merge journals").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performScrollTo()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            assertNoAnr(device)
            assertTrue("Native action screenshot failed", device.takeScreenshot(File(output, "$name-actions.png")))
        } else {
            compose.onNodeWithText("Journeys with friends and family").assertIsDisplayed()
        }
    }

    private fun ids(range: IntRange) =
        range.mapTo(mutableSetOf()) { Uuid.parse("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}") }

    private fun assertNoAnr(device: UiDevice) {
        assertFalse(
            "An Android ANR dialog obscures native visual acceptance",
            device.hasObject(By.res("android:id/aerr_close")) ||
                device.hasObject(By.res("android:id/aerr_wait")) ||
                device.hasObject(By.textContains("isn't responding")) ||
                device.hasObject(By.textContains("isn’t responding")),
        )
    }

    private data class Scene(
        val name: String,
        val review: Boolean = false,
        val dark: Boolean = false,
        val fontScale: Float = 1f,
    )
}
