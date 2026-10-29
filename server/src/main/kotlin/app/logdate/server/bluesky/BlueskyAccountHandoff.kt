package app.logdate.server.bluesky

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Instant

/** Only server-verified identity proof can mint these account continuations. */
@Serializable
internal sealed interface BlueskyAccountHandoff {
    @Serializable
    @SerialName("identity")
    data class Identity(
        val did: AtprotoDid,
        val accountId: String?,
    ) : BlueskyAccountHandoff

    @Serializable
    @SerialName("registration")
    data class Registration(
        val did: AtprotoDid,
        val ownerId: String,
        val username: String,
        val displayName: String,
        val bio: String?,
        val originalBio: String?,
        val challenge: String,
    ) : BlueskyAccountHandoff

    @Serializable
    @SerialName("authentication")
    data class Authentication(
        val did: AtprotoDid,
        val accountId: String,
        val challenge: String,
    ) : BlueskyAccountHandoff
}

internal interface BlueskyAccountHandoffStore {
    suspend fun issue(
        value: BlueskyAccountHandoff,
        expiresAt: Instant,
    ): String

    /** Atomically spends the token, including expired or damaged values. */
    suspend fun take(token: String): BlueskyAccountHandoff?
}
