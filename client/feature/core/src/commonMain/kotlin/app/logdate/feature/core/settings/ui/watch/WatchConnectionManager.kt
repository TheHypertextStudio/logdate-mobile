package app.logdate.feature.core.settings.ui.watch

import app.logdate.client.sync.datalayer.v2.WatchPingInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Outcome of probing a connected watch node over [app.logdate.client.sync.datalayer.v2.WearPaths.WATCH_PING]. */
sealed interface WatchPingProbe {
    data class Supported(
        val info: WatchPingInfo,
    ) : WatchPingProbe

    /** Answered with the legacy pre-versioning 1-byte ack (`[1]`). */
    data object LegacyAck : WatchPingProbe

    /** Responded to a test/legacy boolean ping without version metadata. */
    data object ReachableUnknown : WatchPingProbe

    data object Unreachable : WatchPingProbe
}

/**
 * Manages the connection between the phone and a paired Wear OS watch.
 *
 * Provides reactive observation of the watch connection state and
 * actions for triggering sync and managing the watch app.
 */
interface WatchConnectionManager {
    /**
     * Observes the current watch connection state.
     */
    fun observeConnectionState(): Flow<WatchConnectionState>

    /** Observes lightweight version metadata returned by [WATCH_PING] for [nodeId]. */
    fun observePeerPing(nodeId: String): Flow<WatchPingProbe?> = flowOf(null)

    /** Chooses which reachable watch setup targets when more than one watch is paired. */
    fun selectWatch(nodeId: String) = Unit

    suspend fun refresh() = Unit

    /** Clears cached presence and selection for a watch that was just removed. */
    suspend fun onWatchRemoved(nodeId: String) = Unit

    /**
     * Starts the system flow to associate LogDate with a watch.
     */
    suspend fun beginAssociation()

    /** Explicitly permits setup of a watch the person previously removed. */
    suspend fun prepareSetup(nodeId: String?) = Unit

    /**
     * Triggers an immediate sync with the connected watch.
     */
    suspend fun requestSync()

    suspend fun requestSync(nodeId: String?) = requestSync()

    /**
     * Opens LogDate's Play Store listing on a connected watch without LogDate so it can be installed.
     */
    suspend fun installAppOnWatch(): WatchRequestResult

    suspend fun installAppOnWatch(nodeId: String?): WatchRequestResult = installAppOnWatch()

    /** Called only after the person confirms that LogDate is missing on this exact watch. */
    suspend fun confirmMissingAppAndInstall(nodeId: String): WatchRequestResult = installAppOnWatch(nodeId)

    /**
     * Opens LogDate's Play Store listing on a connected watch even when an older version is already
     * installed, so the user can update the watch app.
     */
    suspend fun updateAppOnWatch(nodeId: String? = null): WatchRequestResult = installAppOnWatch(nodeId)

    /**
     * Opens LogDate at Today on a connected watch that has it installed.
     */
    suspend fun openAppOnWatch(): WatchRequestResult

    suspend fun openAppOnWatch(nodeId: String?): WatchRequestResult = openAppOnWatch()
}
