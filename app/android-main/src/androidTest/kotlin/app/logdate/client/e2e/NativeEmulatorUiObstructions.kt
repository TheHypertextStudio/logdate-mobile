package app.logdate.client.e2e

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Only observed emulator system-app dialogs are recoverable; a LogDate failure stays visible. */
internal fun dismissNativeEmulatorPlatformDialog(
    device: UiDevice,
    timeoutMillis: Long = 5_000,
): Boolean {
    require(timeoutMillis in 1..5_000)
    val obstruction =
        listOf(
            "Bluetooth keeps stopping" to "android:id/aerr_close",
            "Digital Wellbeing isn't responding" to "android:id/aerr_close",
            "System UI isn't responding" to "android:id/aerr_wait",
            "Process system isn't responding" to "android:id/aerr_wait",
            "Pixel Launcher isn't responding" to "android:id/aerr_wait",
        ).map { (text, action) -> By.pkg("android").res("android:id/alertTitle").text(text) to action }
            .firstOrNull { (title, _) -> device.hasObject(title) } ?: return false
    val action = checkNotNull(device.findObject(By.pkg("android").res(obstruction.second)))
    action.click()
    check(device.wait(Until.gone(obstruction.first), timeoutMillis)) { "The emulator platform dialog remained in front of the app" }
    return true
}
