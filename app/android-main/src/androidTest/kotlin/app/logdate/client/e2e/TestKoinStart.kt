package app.logdate.client.e2e

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

/**
 * Starts Koin for a test if the app has not started it yet.
 *
 * The app starts Koin itself, on the main thread, a moment after its process starts. A test that
 * checked from its own thread could find Koin not started at the same moment the app did, and
 * whichever of the two started it second crashed the process. Checking and starting on the main
 * thread, where the app does both, means only one of them can find it not started.
 */
internal fun startKoinIfAppHasNot(context: Context) {
    markDeviceSetUp(context)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(context)
                modules(appModule)
            }
        }
    }
}

/**
 * Marks the device as one LogDate has already been set up on, as the app does on its first normal
 * launch. A test that opens a screen directly skips that launch. Without the mark the app takes
 * journal content it finds on a later launch for a restore whose privacy settings are missing, and
 * refuses to open it.
 */
internal fun markDeviceSetUp(context: Context) {
    PostRestoreDetector(context).markDeviceInitialized()
}
