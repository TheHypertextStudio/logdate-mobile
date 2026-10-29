package app.logdate.server.bluesky.connection

internal enum class ConnectionFailureKind(
    val code: String,
    val description: String,
) {
    UNAVAILABLE("CONNECTION_UNAVAILABLE", "The connection could not be checked"),
    INELIGIBLE("CONNECTION_INELIGIBLE", "This account does not use an external connection"),
    CHANGED("CONNECTION_CHANGED", "The connection changed. Review its current status"),
    PROOF_INVALID("CONNECTION_PROOF_INVALID", "Confirm this action with a registered passkey"),
    PROOF_EXPIRED("CONNECTION_PROOF_EXPIRED", "The confirmation expired"),
    LAST_SIGNIN_FACTOR("LAST_SIGNIN_FACTOR", "Keep a usable sign-in method before disconnecting"),
    RECONNECT_INVALID("CONNECTION_RECONNECT_INVALID", "This reconnect request is invalid or expired"),
}

internal class AtprotoConnectionFailure(
    val kind: ConnectionFailureKind,
) : IllegalStateException(kind.description)
