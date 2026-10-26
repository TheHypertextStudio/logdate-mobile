package app.logdate.client.sync

import app.logdate.client.sync.metadata.UploadScope
import kotlin.time.Clock

/** Local retry queues cannot tell us whether another device has written to the server. */
internal class RemoteSyncRefreshSchedule(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var lastScope: UploadScope? = null
    private var lastAttempt: Long? = null

    fun isDue(scope: UploadScope): Boolean {
        val previous = lastAttempt ?: return true
        val elapsed = now() - previous
        return scope != lastScope || elapsed < 0 || elapsed >= REFRESH_INTERVAL_MS
    }

    fun recordAttempt(scope: UploadScope) {
        lastScope = scope
        lastAttempt = now()
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }
}
