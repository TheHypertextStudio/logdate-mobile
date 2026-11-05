package app.logdate.client.e2e

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.feature.core.AndroidBiometricGatekeeper
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseScreen
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseViewModel
import app.logdate.ui.theme.LogDateTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertNotNull

/** Run only on the owned emulator after its disposable screen lock has been configured. */
@RunWith(AndroidJUnit4::class)
class RecoveryPhraseNativeAuthenticationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun deviceCredentialRevealsThePhraseWithoutReplacingThePage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recoveryNativeAuth") == "true")
        require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val words =
            listOf("orbit", "canvas", "meadow", "lantern", "harbor", "velvet", "summit", "pepper", "gravel", "whisper", "copper", "tundra")
        compose.runOnUiThread {
            val gatekeeper = AndroidBiometricGatekeeper().apply { setActivity(compose.activity) }
            val model = RecoveryPhraseViewModel(gatekeeper, loadPhrase = { words })
            compose.activity.setContent { LogDateTheme(dynamicColor = false) { RecoveryPhraseScreen({}, {}, model) } }
        }
        capture("hidden")
        compose.onNodeWithText("Show recovery phrase").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pin = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertNotNull(pin, "Device credential entry did not appear")
        pin.text = "1234"
        device.pressEnter()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("orbit"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("orbit").assertIsDisplayed()
        compose.onNodeWithText("Recovery phrase").assertIsDisplayed()
        compose.onNodeWithText("Confirm recovery copy").assertDoesNotExist()
        compose.onNodeWithText("A private recovery copy is required before encrypted Cloud backup can run.").assertDoesNotExist()
        capture("revealed")
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("orbit").assertDoesNotExist()
        compose.onNodeWithText("How recovery works").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Journal backup to another device").assertIsDisplayed()
        capture("about")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "recovery-ui/native-window").apply { mkdirs() }
        compose.waitForIdle()
        SystemClock.sleep(600)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
