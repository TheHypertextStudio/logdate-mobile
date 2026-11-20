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
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.testing.onboarding.DEBUG_SKIP_ONBOARDING_EXTRA
import app.logdate.client.testing.onboarding.OnboardingTestFixture
import app.logdate.client.testing.onboarding.putOnboardingTestFixture
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
            await(By.textContains("Welcome to LogDate"), "the welcome screen")
            hold(4_000)
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
            hold()
            device.executeShellCommand("input text park")
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
        ActivityScenario.launch<MainActivity>(intent).use {
            if (skipOnboarding) create()
            ManagedEditorRecording(device, output, "$flow.mp4").use { recording ->
                hold(700)
                steps()
                recording.assertStillRecording()
            }
        }
    }

    /** The Home creation action, which proves the workspace has loaded. */
    private fun create(): UiObject2 =
        device.wait(Until.findObject(By.desc("Add a memory")), 25_000)
            ?: await(By.text("Create Entry"), "the Home creation action")

    private fun await(
        selector: BySelector,
        what: String,
        timeoutMillis: Long = 15_000,
    ): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), timeoutMillis)) { "Timed out waiting for $what" }

    /** Holds the screen still long enough for a viewer to read it. */
    private fun hold(millis: Long = 1_200) = SystemClock.sleep(millis)
}
