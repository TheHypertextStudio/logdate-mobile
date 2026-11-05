package app.logdate.server.bluesky

import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.VerifiedIdentityAdoptionOAuth
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** App proof is separate from provider PKCE; a browser callback alone cannot sign an app in. */
internal interface BlueskySignInAttemptStore {
    suspend fun createAdoption(
        id: String,
        challenge: String,
        expiresAt: Instant,
        binding: IdentityAdoptionOAuthBinding,
    ) {
        error("Identity adoption storage is unavailable")
    }

    /** Idempotent only within this exact proposal and app proof; never an enrollment continuation. */
    suspend fun exchangeAdoption(
        id: String,
        verifier: String,
        binding: IdentityAdoptionOAuthBinding,
    ): VerifiedIdentityAdoptionOAuth? = null

    @OptIn(ExperimentalUuidApi::class)
    suspend fun createReconnect(
        id: String,
        challenge: String,
        expiresAt: Instant,
        accountId: Uuid,
        expectedDid: AtprotoDid,
    ) {
        error("Reconnect storage is unavailable")
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun consumeReconnect(
        id: String,
        verifier: String,
        accountId: Uuid,
        expectedDid: AtprotoDid,
    ): VerifiedBlueskySignIn? = null

    suspend fun create(
        id: String,
        challenge: String,
        expiresAt: Instant,
        accountId: String? = null,
    )

    suspend fun complete(
        id: String,
        did: AtprotoDid,
    ): Boolean

    suspend fun fail(
        id: String,
        kind: BlueskySignInFailureKind,
    ): Boolean

    suspend fun consume(
        id: String,
        verifier: String,
    ): VerifiedBlueskySignIn?
}

internal data class VerifiedBlueskySignIn(
    val did: AtprotoDid,
    val accountId: String?,
)
