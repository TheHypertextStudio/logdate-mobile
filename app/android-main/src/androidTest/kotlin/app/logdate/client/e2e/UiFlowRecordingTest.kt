package app.logdate.client.e2e

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.logdate.client.MainActivity
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.testing.onboarding.DEBUG_SKIP_ONBOARDING_EXTRA
import app.logdate.client.testing.onboarding.OnboardingTestFixture
import app.logdate.client.testing.onboarding.putOnboardingTestFixture
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Records named UI flows of the real app at a human pace, one video per flow, so the same flows
 * can be compared across Android, Mac and iPhone.
 *
 * Each test records one flow and writes `ui-flows/<flow>/<flow>.mp4` to the managed device's
 * additional test output. Flow names match `testFlow_<flow>` in the Apple apps' UIFlowRecordingTests
 * where the same flow exists. Runs only on a Gradle Managed Device.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test(timeout = 120_000)
    fun flowFirstRun() =
        record("first_run", skipOnboarding = false) {
            await(By.textContains("Meet LogDate"), "the opening screen")
            hold(4_000)
            await(By.text("See how it works"), "the opening action").click()
            hold(3_000)
        }

    @Test(timeout = 120_000)
    fun flowWriteEntry() =
        record("write_entry") {
            create().click()
            val field = await(By.clazz("android.widget.EditText"), "the entry text field")
            field.click()
            hold()
            for (word in listOf("Morning", "%swalk", "%sby", "%sthe", "%sriver.")) {
                device.executeShellCommand("input text $word")
                SystemClock.sleep(300)
            }
            hold(2_000)
            device.pressBack()
            hold()
            device.pressBack()
            hold(2_000)
        }

    @Test(timeout = 120_000)
    fun flowTimelineBrowse() =
        record("timeline_browse") {
            hold(2_000)
            val width = device.displayWidth
            val height = device.displayHeight
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 40)
            hold()
            device.swipe(width / 2, height / 4, width / 2, height * 3 / 4, 40)
            hold(2_000)
        }

    @Test(timeout = 120_000)
    fun flowSearch() =
        record("search") {
            await(By.text("Search your log"), "the workspace search bar").click()
            await(By.focused(true), "the focused search field")
            for (letter in "park") {
                device.executeShellCommand("input text $letter")
                SystemClock.sleep(250)
            }
            hold(3_000)
        }

    @Test(timeout = 120_000)
    fun flowSettings() =
        record("settings") {
            await(By.desc("Account, backup and settings"), "the account action").click()
            hold(2_000)
            device.findObject(By.text("Settings"))?.click()
            hold(3_000)
        }

    private fun record(
        flow: String,
        skipOnboarding: Boolean = true,
        steps: () -> Unit,
    ) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "UI flows record only on a managed emulator" }
        val context = instrumentation.targetContext
        ensureLogDateApplicationStarted(context.applicationContext as Application)
        val root =
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
                "Run UI flow recordings through a Gradle Managed Device task"
            }
        val output = File(root, "ui-flows/$flow").apply { mkdirs() }
        val intent =
            Intent(context, MainActivity::class.java).apply {
                if (skipOnboarding) {
                    putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
                } else {
                    putOnboardingTestFixture(OnboardingTestFixture.FRESH_ONBOARDING)
                }
            }
        val samples = if (skipOnboarding) seedSampleEntries() else emptyList()
        try {
            ActivityScenario.launch<MainActivity>(intent).use {
                if (skipOnboarding) create()
                ManagedEditorRecording(device, output, "$flow.mp4").use { recording ->
                    hold(700)
                    steps()
                    recording.assertStillRecording()
                }
            }
        } finally {
            val notes = KoinJavaComponent.getKoin().get<JournalNotesRepository>()
            runBlocking { samples.forEach { notes.removeById(it.uid) } }
        }
    }

    /**
     * Adds the same kind of synthetic entries the Apple apps show with `--preview-data`, so every
     * platform's flows show a populated timeline and search has something to find.
     */
    private fun seedSampleEntries(): List<JournalNote> {
        val now = Clock.System.now()
        val samples =
            listOf(
                now - 1.hours to "The light hit the kitchen wall before I left, and the apartment felt quiet.",
                now - 3.hours to "A quiet morning walk through the park. Wrote this down to remember the light.",
                now - 1.days to "Made coffee and sorted through photos from the weekend.",
            ).map { (time, text) -> JournalNote.Text(creationTimestamp = time, lastUpdated = time, content = text) }
        val notes = KoinJavaComponent.getKoin().get<JournalNotesRepository>()
        runBlocking { samples.forEach { notes.create(it) } }
        return samples
    }

    /** The Home creation action, which proves the workspace has loaded. */
    private fun create(): UiObject2 = await("the Home creation action", 40_000, By.desc("Add a memory"), By.text("Create Entry"))

    private fun await(
        selector: BySelector,
        what: String,
        timeoutMillis: Long = 15_000,
    ): UiObject2 = await(what, timeoutMillis, selector)

    /** Waits for any of [selectors], clearing the emulator's own "isn't responding" dialogs while it waits. */
    private fun await(
        what: String,
        timeoutMillis: Long,
        vararg selectors: BySelector,
    ): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            dismissNativeEmulatorPlatformDialog(device)
            selectors.firstNotNullOfOrNull { device.findObject(it) }?.let { return it }
            SystemClock.sleep(500)
        }
        error("Timed out waiting for $what. ${visibleLabels()}")
    }

    /** Names what the screen shows, so a timeout explains where the flow stopped. */
    private fun visibleLabels(): String {
        val hierarchy = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())
        val labels =
            Regex("""(?:text|content-desc)="([^"]+)"""")
                .findAll(hierarchy)
                .map { it.groupValues[1] }
                .distinct()
                .take(40)
                .toList()
        return "Package ${device.currentPackageName} shows: ${labels.joinToString(" | ")}"
    }

    /** Holds the screen still long enough for a viewer to read it. */
    private fun hold(millis: Long = 1_200) = SystemClock.sleep(millis)
}
