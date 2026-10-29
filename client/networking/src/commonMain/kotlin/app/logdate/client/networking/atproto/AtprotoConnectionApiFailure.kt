package app.logdate.client.networking.atproto

enum class AtprotoConnectionApiFailure {
    UNAUTHORIZED,
    CHANGED,
    PROOF_INVALID,
    PROOF_EXPIRED,
    LAST_SIGN_IN_FACTOR,
    RATE_LIMITED,
    INELIGIBLE,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

/** Discards provider text and platform exception causes, which may contain credentials. */
class AtprotoConnectionApiException(
    val kind: AtprotoConnectionApiFailure,
) : IllegalStateException("The account connection could not be checked")
