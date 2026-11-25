package app.logdate.feature.core.settings.ui.watch

import kotlinx.coroutines.flow.Flow

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

    /** Chooses which reachable watch setup targets when more than one watch is paired. */
    fun selectWatch(nodeId: String) = Unit

    suspend fun refresh() = Unit

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
     * Opens LogDate's Play Store listing on a connected watch: one without LogDate so it can be
     * installed, or else one that has it so it can be updated.
     */
    suspend fun installAppOnWatch(): WatchRequestResult

    suspend fun installAppOnWatch(nodeId: String?): WatchRequestResult = installAppOnWatch()

    /** Called only after the person confirms that LogDate is missing on this exact watch. */
    suspend fun confirmMissingAppAndInstall(nodeId: String): WatchRequestResult = installAppOnWatch(nodeId)

    /**
     * Opens LogDate at Today on a connected watch that has it installed.
     */
    suspend fun openAppOnWatch(): WatchRequestResult

    suspend fun openAppOnWatch(nodeId: String?): WatchRequestResult = openAppOnWatch()
}
