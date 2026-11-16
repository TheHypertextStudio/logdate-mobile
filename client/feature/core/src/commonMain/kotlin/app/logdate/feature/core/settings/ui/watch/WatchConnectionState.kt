package app.logdate.feature.core.settings.ui.watch

/**
 * Describes the current connection state between the phone and a Wear OS watch.
 */
sealed interface WatchConnectionState {
    val nodeId: String? get() = null

    /**
     * No Wear OS watch is currently available to associate or connect.
     */
    data object NoPairedWatch : WatchConnectionState

    /**
     * A reachable watch exists, but LogDate has not been associated with it yet.
     */
    data class NeedsAssociation(
        val watchName: String,
        override val nodeId: String? = null,
    ) : WatchConnectionState

    /**
     * The system association flow is currently in progress.
     */
    data object AssociationPending : WatchConnectionState

    /**
     * A watch is paired but LogDate is not installed on it.
     */
    data class AppNotInstalled(
        val watchName: String,
        override val nodeId: String? = null,
    ) : WatchConnectionState

    /**
     * LogDate is installed on the watch and communication is possible.
     */
    data class Connected(
        val watchName: String,
        override val nodeId: String? = null,
    ) : WatchConnectionState

    /**
     * The watch was previously connected but is currently out of range.
     */
    data class OutOfRange(
        val watchName: String,
        override val nodeId: String? = null,
    ) : WatchConnectionState

    /**
     * Still determining the connection state.
     */
    data object Loading : WatchConnectionState

    /** Discovery failed; this says nothing about pairing or installation. */
    data object Unavailable : WatchConnectionState

    /** The watch is reachable, but a missing advertisement cannot establish package absence. */
    data class AppUnknown(
        val watchName: String,
        override val nodeId: String? = null,
        val knownInstalled: Boolean = false,
    ) : WatchConnectionState
}
