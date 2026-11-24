package app.logdate.client.sync

import android.app.ActivityManager
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures at most one [AndroidLogDateSyncWorker] instance executes sync work at a time across
 * both `immediate_sync` and `periodic_sync` queues.
 */
internal class SyncWorkerExecutionGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }

    inline fun <T> withGate(
        onBusy: () -> T,
        block: () -> T,
    ): T {
        if (!tryAcquire()) return onBusy()
        return try {
            block()
        } finally {
            release()
        }
    }
}

internal object SyncForegroundPolicy {
    fun shouldPromoteToForeground(
        isManualRequest: Boolean,
        hasMobileDataConsent: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
        isAppInForeground: () -> Boolean = ::isProcessInForeground,
    ): Boolean =
        isManualRequest ||
            hasMobileDataConsent ||
            sdkInt < Build.VERSION_CODES.S ||
            isAppInForeground()

    fun isProcessInForeground(): Boolean =
        runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
}
