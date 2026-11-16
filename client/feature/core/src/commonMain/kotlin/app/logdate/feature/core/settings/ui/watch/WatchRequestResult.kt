package app.logdate.feature.core.settings.ui.watch

/** What happened when the phone asked a watch to open something. */
sealed interface WatchRequestResult {
    /** The watch took the request. */
    data object Sent : WatchRequestResult

    /** The request didn't go through, for [reason]. */
    data class Failed(
        val reason: WatchRequestFailure,
    ) : WatchRequestResult
}

/** Why a request to the watch didn't go through, in terms of what the person can do about it. */
enum class WatchRequestFailure {
    /** The phone can't see a connected watch. */
    NO_WATCH_CONNECTED,

    LOOKUP_UNAVAILABLE,
    ALREADY_INSTALLED,
    INSTALLATION_UNKNOWN,
    OPEN_FAILED,

    /** No connected watch has LogDate installed, so there is nothing to open. */
    NOT_INSTALLED_ON_WATCH,

    /** The watch was asked and didn't open what was sent, or never answered. */
    WATCH_DID_NOT_RESPOND,
}
