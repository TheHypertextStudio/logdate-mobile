package app.logdate.client.e2e

import android.app.KeyguardManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.isAppUnlocked
import app.logdate.shared.model.user.AppSecurityLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RestoredAppLockEnrollmentRuntimeTest {
    private var dismissedBluetoothCrash = false

    @Test
    fun canceledDestinationSecuritySetupKeepsTheRestoredJournalLocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
        val original = runBlocking { preferences.userData.first() }
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim(), "Emulator-only security fixture")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            runBlocking {
                preferences.setShouldHideOnboarding(true)
                preferences.setShouldShowBiometric(true)
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            // The automatic launch challenge never opens system settings; enrollment starts from the person's tap.
            val setup =
                assertNotNull(
                    device.wait(Until.findObject(By.text("Set up device security").enabled(true)), 30_000),
                    "The restored app lock must offer destination security setup",
                )
            setup.click()
            assertTrue(
                awaitNativeEnrollment(device),
                "The restored app lock must offer native destination security enrollment",
            )
            captureEvidence(device, "native-enrollment")
            device.pressBack()
            assertTrue(
                device.wait(Until.hasObject(By.text("Set up device security")), 30_000),
                "Canceled enrollment must return to the protected journal",
            )
            assertFalse(device.hasObject(By.pkg("com.android.settings")))
            assertEquals(AppSecurityLevel.BIOMETRIC, runBlocking { preferences.userData.first().securityLevel })
            captureEvidence(device, "enrollment-canceled")
            device.pressHome()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName))
            assertTrue(device.wait(Until.hasObject(By.text("Set up device security")), 30_000))
            assertFalse(device.hasObject(By.pkg("com.android.settings")), "Returning must not reopen canceled security setup")
            assertEquals(AppSecurityLevel.BIOMETRIC, runBlocking { preferences.userData.first().securityLevel })
        } catch (failure: Throwable) {
            captureFailure(device, failure)
            throw failure
        } finally {
            runBlocking {
                preferences.setShouldShowBiometric(original.securityLevel == AppSecurityLevel.BIOMETRIC)
                preferences.setShouldHideOnboarding(original.isOnboarded)
            }
            scenario?.close()
        }
    }

    @Test
    fun destinationPinUnlocksAgainAfterBackgroundingAndActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim(), "Emulator-only security fixture")
        assumeFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
        val original = runBlocking { preferences.userData.first() }
        var scenario: ActivityScenario<MainActivity>? = null
        var pinInstalled = false
        try {
            val setup = device.executeShellCommand("locksettings set-pin 135790")
            pinInstalled = setup.contains("Pin set", ignoreCase = true)
            assertTrue(pinInstalled, "The emulator must accept its temporary security fixture")
            runBlocking {
                preferences.setShouldHideOnboarding(true)
                preferences.setShouldShowBiometric(true)
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            completeSystemPin(device, "initial")
            assertJournalUnlocked(scenario)
            assertTrue(device.wait(Until.gone(By.text("Use passcode")), 30_000))
            captureEvidence(device, "initial-unlocked")

            device.pressHome()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName))
            // A fresh system challenge is required; a retained successful state cannot satisfy it.
            completeSystemPin(device, "foreground")
            assertJournalUnlocked(scenario)
            assertTrue(device.wait(Until.gone(By.text("Use passcode")), 30_000))
            captureEvidence(device, "foreground-unlocked")

            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            completeSystemPin(device, "recreated")
            assertJournalUnlocked(scenario)
            assertTrue(device.wait(Until.gone(By.text("Use passcode")), 30_000))
            captureEvidence(device, "recreated-unlocked")
        } catch (failure: Throwable) {
            captureFailure(device, failure)
            throw failure
        } finally {
            runBlocking {
                preferences.setShouldShowBiometric(original.securityLevel == AppSecurityLevel.BIOMETRIC)
                preferences.setShouldHideOnboarding(original.isOnboarded)
            }
            scenario?.close()
            if (pinInstalled) {
                val cleanup = device.executeShellCommand("locksettings clear --old 135790")
                assertTrue(cleanup.contains("cleared", ignoreCase = true), "The emulator security fixture must be removed")
            }
        }
    }

    private fun awaitNativeEnrollment(device: UiDevice): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.wait(Until.hasObject(By.pkg("com.android.settings")), 250)) return true
            dismissEmulatorBluetoothCrash(device)
        }
        return false
    }

    private fun dismissEmulatorBluetoothCrash(device: UiDevice) {
        if (dismissedBluetoothCrash ||
            !device.hasObject(By.res("android:id/alertTitle").text("Bluetooth keeps stopping"))
        ) {
            return
        }
        captureEvidence(device, "emulator-bluetooth-crash")
        val close = assertNotNull(device.findObject(By.res("android:id/aerr_close")))
        close.click()
        dismissedBluetoothCrash = true
        Napier.w("Dismissed the emulator Bluetooth system crash dialog before native security acceptance")
    }

    private fun captureFailure(
        device: UiDevice,
        failure: Throwable,
    ) {
        runCatching { captureEvidence(device, "failure-${System.nanoTime()}") }.onFailure(failure::addSuppressed)
    }

    private fun captureEvidence(
        device: UiDevice,
        stage: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory =
            File(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: requireNotNull(instrumentation.targetContext.externalCacheDir).path,
                "restored-app-lock",
            )
        check(directory.isDirectory || directory.mkdirs())
        assertTrue(device.takeScreenshot(File(directory, "$stage.png")))
        device.dumpWindowHierarchy(File(directory, "$stage.xml"))
        val user =
            runBlocking {
                GlobalContext
                    .get()
                    .get<LogdatePreferencesDataSource>()
                    .userData
                    .first()
            }
        val secure = instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isDeviceSecure
        File(directory, "$stage-state.txt").writeText(
            "isOnboarded=${user.isOnboarded}, securityLevel=${user.securityLevel}, deviceSecure=$secure",
        )
    }

    private fun completeSystemPin(
        device: UiDevice,
        stage: String,
    ) {
        val selector = By.clazz("android.widget.EditText").pkg(Pattern.compile("com\\.android\\.(settings|systemui)"))
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var input: UiObject2? = null
        var requestedUnlock = false
        while (input == null && SystemClock.elapsedRealtime() < deadline) {
            dismissEmulatorBluetoothCrash(device)
            input = device.wait(Until.findObject(selector), 250)
            if (input == null && !requestedUnlock) {
                val action = device.findObject(By.text("Use passcode"))
                if (action != null) {
                    try {
                        action.click()
                        requestedUnlock = true
                    } catch (_: StaleObjectException) {
                        // Automatic authentication can replace the action with the system prompt.
                    }
                }
            }
        }
        val credential = assertNotNull(input, "Native device credential authentication must be offered")
        captureEvidence(device, "$stage-pin-challenge")
        credential.text = "135790"
        device.pressEnter()
    }

    private fun assertJournalUnlocked(scenario: ActivityScenario<MainActivity>) {
        lateinit var viewModel: AppViewModel
        scenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        runBlocking {
            withTimeout(30_000) {
                val state = viewModel.uiState.filterIsInstance<GlobalAppUiLoadedState>().first { it.isAppUnlocked }
                assertTrue(state.isOnboarded, "PIN acceptance must return to a restored journal, not onboarding")
            }
        }
    }
}
