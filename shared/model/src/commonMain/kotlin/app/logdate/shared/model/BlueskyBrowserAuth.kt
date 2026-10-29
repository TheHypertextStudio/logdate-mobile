package app.logdate.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class BlueskyStartRequest(
    val handle: String,
    val challenge: String,
)

@Serializable
data class BlueskyStartResponse(
    val attemptId: String,
    val authorizationUrl: String,
)

/** The browser returns no credentials: this identifier still needs the originating app's verifier. */
object BlueskyBrowserReturn {
    const val PATH = "/auth/bluesky/return"
    const val URL = "https://app.logdate.app$PATH"
    const val ERROR_URL = "https://app.logdate.app/auth/bluesky/error"
    const val NATIVE_URL = "studio.hypertext.logdate://auth/bluesky/return"
}
