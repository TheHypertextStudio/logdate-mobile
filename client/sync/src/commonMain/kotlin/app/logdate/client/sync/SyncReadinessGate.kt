package app.logdate.client.sync

import app.logdate.client.device.crypto.IdentityKeyNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/** Device failures stop the pass; individual records keep their retry budgets. */
class SyncReadinessGate(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val blocked = MutableStateFlow<SyncPausedReason?>(null)
    val reason = blocked.asStateFlow()
    private var nextProbeAt = 0L
    private var probeDelay = 1_000L

    fun pause(error: Throwable): Boolean {
        val reason = deviceBlockReason(error) ?: return false
        blocked.value = reason
        nextProbeAt = now() + probeDelay
        probeDelay = (probeDelay * 2).coerceAtMost(60_000L)
        return true
    }

    fun canProbe(): Boolean = blocked.value == null || now() >= nextProbeAt

    fun allowProbe(manual: Boolean = false): Boolean {
        if (blocked.value == null) return true
        if (!manual && now() < nextProbeAt) return false
        blocked.value = null
        return true
    }

    fun ready() {
        blocked.value = null
        probeDelay = 1_000L
    }
}

/** Only positively identified device-wide failures bypass record retry accounting. */
fun deviceBlockReason(error: Throwable): SyncPausedReason? {
    var cause: Throwable? = error
    repeat(12) {
        val current = cause ?: return null
        when {
            current is IdentityKeyNotFoundException -> {
                return SyncPausedReason.NEEDS_RECOVERY_PHRASE
            }

            current is SyncStorageUnavailableException ||
                current::class.simpleName in setOf("SQLiteFullException", "SQLiteCantOpenDatabaseException") ||
                (
                    current::class.simpleName == "SQLiteException" &&
                        current.message?.let {
                            it.contains("database or disk is full", ignoreCase = true) ||
                                it.contains("unable to open database file", ignoreCase = true)
                        } == true
                ) ||
                current.message?.let { it.contains("SQLITE_FULL") || it.contains("ENOSPC") || it.contains("No space left on device") } ==
                true -> {
                return SyncPausedReason.LOCAL_STORAGE_UNAVAILABLE
            }
        }
        cause = current.cause.takeUnless { it === current }
    }
    return null
}

class SyncStorageUnavailableException : Exception("Local storage is unavailable")

internal fun throwIfDeviceBlocked(error: Throwable) {
    if (deviceBlockReason(error) != null) throw error
}
