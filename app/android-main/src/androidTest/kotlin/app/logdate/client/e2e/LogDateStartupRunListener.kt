package app.logdate.client.e2e

import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.LogDateApplication
import app.logdate.client.awaitLogDateDependencies
import io.github.aakira.napier.Napier
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * Holds every test until the app process has started Koin.
 *
 * Instrumentation starts tests right after `Application.onCreate`, but [LogDateApplication] starts
 * Koin a moment later on the main thread, after a short clean-up off it. Tests that read
 * `GlobalContext` first failed with "KoinApplication has not been started" whenever they won that
 * race. The app reaches Koin within moments either way, so waiting removes only the race.
 *
 * It waits only in a normal [LogDateApplication] process on an unlocked device: a process Android
 * created for a restore agent, or a locked device, never starts Koin by itself, and the tests that
 * cover those states start what they need. Registered in the androidTest manifest, so it applies to
 * Gradle and `am instrument` runs alike.
 */
class LogDateStartupRunListener : RunListener() {
    override fun testRunStarted(description: Description?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (context.applicationContext !is LogDateApplication) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        if (!awaitLogDateDependencies(KOIN_START_TIMEOUT_MILLIS)) {
            Napier.w("LogDate did not start Koin within $KOIN_START_TIMEOUT_MILLIS ms of instrumentation starting")
        }
    }

    private companion object {
        const val KOIN_START_TIMEOUT_MILLIS = 60_000L
    }
}
