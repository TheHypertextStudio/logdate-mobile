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
     *
     * @property linking Null while the system's pairing prompt is needed. Otherwise LogDate links the watch
     * by itself, and this says how that is going.
     */
    data class NeedsAssociation(
        val watchName: String,
        override val nodeId: String? = null,
        val linking: WatchLinking? = null,
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

/**
 * LogDate's own link with a watch that has the app, while it is not working yet.
 *
 * @property problem Null while the phone is linking, or about to; otherwise why the last attempt failed.
 */
data class WatchLinking(
    val problem: WatchLinkProblem? = null,
)

/** Why LogDate could not link a watch, in terms of what the person can do about it. */
enum class WatchLinkProblem {
    /** The watch did not answer, or the attempt failed in a way that usually passes. The phone retries by itself. */
    RETRYING,

    /** The watch is set up with another LogDate account. */
    ANOTHER_ACCOUNT,

    /** The account has as many devices as it allows. */
    DEVICE_LIMIT,

    /** The watch could not install the link and needs to be set up again. */
    SET_UP_AGAIN,

    /** The person removed LogDate from this watch, so the phone does not link it until they ask. */
    REMOVED,
}
