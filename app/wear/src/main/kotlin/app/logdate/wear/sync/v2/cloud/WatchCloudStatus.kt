package app.logdate.wear.sync.v2.cloud

import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncPausedReason
import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.peer.link.LinkProblem
import app.logdate.wear.sync.v2.link.WatchLinkRecord
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Whether the watch's own cloud engine speaks for its sync. */
enum class WatchCloudPath {
    /** The watch backs up only through its phone; the engine's status says nothing about it. */
    UNUSED,

    /** The watch is linked to an account and may back up on its own. */
    ACTIVE,

    /** The watch moved to another phone or its account link failed; it waits for its phone to link it again. */
    NEEDS_LINK,
}

/**
 * The cloud path for [link] while the new watch app is [newAppOn]. An account link that is still being
 * installed is left out: the engine has no session yet, and that is not a problem to report. Nor is a
 * sign-in that ran out, which the phone renews on its own.
 */
fun watchCloudPathOf(
    newAppOn: Boolean,
    link: WatchLinkRecord?,
): WatchCloudPath {
    if (!newAppOn || link == null) return WatchCloudPath.UNUSED
    if (link.stale) return WatchCloudPath.NEEDS_LINK
    if (link.problem == LinkProblem.SESSION_EXPIRED.name) return WatchCloudPath.UNUSED
    if (link.problem != null) return WatchCloudPath.NEEDS_LINK
    if (link.mode != LinkMode.CLOUD || link.installing) return WatchCloudPath.UNUSED
    return WatchCloudPath.ACTIVE
}

/**
 * The cloud engine's status while [path] says it applies, and null otherwise. [engine] is built only
 * once the path is active, so a watch that never links never starts the engine for its status.
 *
 * An engine that cannot be built or followed, or a path that cannot be read, is reported as a failed
 * backup rather than thrown into the scope that shares the status.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun watchCloudStatus(
    path: Flow<WatchCloudPath>,
    engine: () -> SyncManager,
): Flow<SyncStatus?> =
    path
        .distinctUntilChanged()
        .flatMapLatest { current ->
            when (current) {
                WatchCloudPath.UNUSED -> flowOf(null)
                WatchCloudPath.ACTIVE -> flow { emitAll(engine().syncStatusFlow) }.catch { emit(unreadableStatus(it)) }
                WatchCloudPath.NEEDS_LINK -> flowOf(NEEDS_LINK_STATUS)
            }
        }.catch { emit(unreadableStatus(it)) }

private fun unreadableStatus(error: Throwable): SyncStatus {
    Napier.e("The watch's own backup status could not be read", error)
    return SyncStatus(
        isEnabled = true,
        lastSyncTime = null,
        pendingUploads = 0,
        isSyncing = false,
        hasErrors = true,
        lastError =
            SyncError(
                type = SyncErrorType.UNKNOWN_ERROR,
                message = "The watch's own backup status could not be read",
                cause = error,
                retryable = false,
            ),
    )
}

private val NEEDS_LINK_STATUS =
    SyncStatus(
        isEnabled = false,
        lastSyncTime = null,
        pendingUploads = 0,
        isSyncing = false,
        hasErrors = false,
        pausedReason = SyncPausedReason.NEEDS_DEVICE_LINK,
    )
