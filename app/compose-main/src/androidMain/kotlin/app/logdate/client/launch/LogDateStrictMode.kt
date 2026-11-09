package app.logdate.client.launch

import android.content.pm.ApplicationInfo
import android.os.StrictMode
import io.github.aakira.napier.Napier

/**
 * Surfaces main-thread disk reads / writes, network, slow custom calls, and leaked closables
 * in debug builds. Violations are logged but don't crash — too aggressive for normal dev
 * iteration. The whole block is no-op in release.
 */
fun installStrictModeIfDebuggable(applicationInfo: ApplicationInfo) {
    val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!isDebuggable) return
    runCatching {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }.onFailure { error ->
        Napier.w("Failed to install StrictMode policies", error)
    }
}
