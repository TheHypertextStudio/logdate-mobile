package studio.hypertext.atproto.oauth.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * When to renew a session's access token.
 */
public enum class OAuthRefreshMode {
    /** Renew only when the access token has expired or is about to. */
    AUTO,

    /** Renew now, even if the access token is still valid. */
    FORCE,

    /** Never renew; return the stored tokens as they are. */
    NEVER,
}

/**
 * Loads, renews, and ends stored sessions. Shared by [AtprotoOAuthClient] and [OAuthSession].
 *
 * Reading a session takes no lock. The [OAuthSessionLock] is taken only to renew one, and the
 * session is read again inside it, so callers that queued behind another renewal use its result
 * instead of spending the single-use refresh token a second time.
 */
internal class OAuthSessionManager(
    private val config: OAuthClientConfig,
    private val resolver: OAuthServerResolver,
    private val httpClient: HttpClient,
    private val crypto: OAuthCrypto,
    private val nonces: DpopNonceCache,
    private val sessionStore: OAuthSessionStore,
    private val sessionLock: OAuthSessionLock,
    private val clock: Clock,
    private val strictDpopNonces: Boolean = false,
    private val requireAuthorizationAfterPdsChange: Boolean = false,
) {
    suspend fun session(
        did: AtprotoDid,
        mode: OAuthRefreshMode,
    ): OAuthStoredSession {
        val current = load(did)
        return when (mode) {
            OAuthRefreshMode.NEVER -> current
            OAuthRefreshMode.AUTO -> if (isStale(current)) renewUnlessReplaced(current) else current
            OAuthRefreshMode.FORCE -> renewUnlessReplaced(current)
        }
    }

    /**
     * Renews the session after a data server rejected [rejected]'s access token, unless another
     * caller has already replaced that token.
     */
    suspend fun renewAfterRejection(rejected: OAuthStoredSession): OAuthStoredSession = renewUnlessReplaced(rejected)

    /**
     * Runs [block] while holding the session lock for [did].
     */
    suspend fun <T> withLock(
        did: AtprotoDid,
        block: suspend () -> T,
    ): T = sessionLock.withLock(did, block)

    /**
     * Removes the session for [did] and throws [exception].
     */
    suspend fun end(
        did: AtprotoDid,
        exception: OAuthSessionEndedException,
    ): Nothing {
        val reason =
            when (exception) {
                is OAuthSessionExpiredException -> OAuthSessionEndReason.EXPIRED
                is OAuthSessionRejectedException -> OAuthSessionEndReason.REJECTED
                is OAuthSessionAuthorityChangedException -> OAuthSessionEndReason.AUTHORITY_CHANGED
                is OAuthSessionRevokedException ->
                    if (exception.cause is OAuthIssuerMismatchException) {
                        OAuthSessionEndReason.AUTHORITY_CHANGED
                    } else {
                        OAuthSessionEndReason.REVOKED
                    }
            }
        sessionStore.delete(did, reason)
        throw exception
    }

    /** A delayed response can end only the grant it actually rejected, never a newer callback. */
    suspend fun endAfterRejection(
        rejected: OAuthStoredSession,
        exception: OAuthSessionEndedException,
    ): Nothing {
        sessionLock.withLock(rejected.did) {
            if (sessionStore.get(rejected.did) == rejected) end(rejected.did, exception)
        }
        throw exception
    }

    /**
     * Revokes the session's tokens at its authorization server and removes it locally. The local
     * session is removed even when revocation fails.
     */
    suspend fun signOut(did: AtprotoDid) {
        sessionLock.withLock(did) { signOutLocked(did) }
    }

    suspend fun signOutIf(
        did: AtprotoDid,
        beforeRevoke: suspend () -> Boolean,
        removeLocal: suspend () -> Unit,
    ): OAuthConditionalRevocation =
        sessionLock.withLock(did) {
            if (!beforeRevoke()) OAuthConditionalRevocation.SKIPPED else signOutLocked(did, removeLocal)
        }

    /**
     * [signOut] for a caller that already holds the session lock for [did].
     */
    suspend fun signOutLocked(
        did: AtprotoDid,
        removeLocal: (suspend () -> Unit)? = null,
    ): OAuthConditionalRevocation {
        val complete = removeLocal ?: { sessionStore.delete(did) }
        var present = false
        val failure =
            try {
                val stored = sessionStore.get(did)
                if (stored != null) {
                    present = true
                    agentFor(stored, resolver.authorizationServerMetadata(stored.issuer))
                        .revoke(stored.refreshToken ?: stored.accessToken)
                }
                null
            } catch (exception: CancellationException) {
                withContext(NonCancellable) { completeKeeping(complete, exception) }
                throw exception
            } catch (exception: Exception) {
                exception
            }
        if (present || failure != null || removeLocal != null) completeKeeping(complete, failure)
        if (failure != null) throw OAuthRevocationException(did, failure)
        return if (present) OAuthConditionalRevocation.CONFIRMED else OAuthConditionalRevocation.NO_SESSION
    }

    /**
     * Removes the session for [did]. If that fails, [earlier] is attached to the delete failure as
     * suppressed, so neither is lost.
     */
    private suspend fun completeKeeping(
        complete: suspend () -> Unit,
        earlier: Throwable?,
    ) {
        try {
            complete()
        } catch (exception: Exception) {
            earlier?.let(exception::addSuppressed)
            throw exception
        }
    }

    fun agentFor(
        metadata: OAuthAuthorizationServerMetadata,
        dpopKey: JsonWebKey,
        clientKey: OAuthClientKey,
    ): OAuthServerAgent =
        OAuthServerAgent(
            metadata = metadata,
            dpopKey = dpopKey,
            clientKey = clientKey,
            clientId = config.clientId,
            httpClient = httpClient,
            crypto = crypto,
            nonces = nonces,
            clock = clock,
            strictDpopNonces = strictDpopNonces,
        )

    private fun agentFor(
        stored: OAuthStoredSession,
        metadata: OAuthAuthorizationServerMetadata,
    ): OAuthServerAgent {
        val clientKey = config.key(stored.clientKeyId) ?: throw OAuthClientKeyRemovedException(stored.clientKeyId)
        return agentFor(metadata, stored.dpopKey, clientKey)
    }

    private suspend fun load(did: AtprotoDid): OAuthStoredSession = sessionStore.get(did) ?: throw OAuthSessionNotFoundException(did)

    private suspend fun renewUnlessReplaced(seen: OAuthStoredSession): OAuthStoredSession =
        sessionLock.withLock(seen.did) {
            val latest = load(seen.did)
            if (latest.accessToken != seen.accessToken) latest else refresh(latest)
        }

    private fun isStale(stored: OAuthStoredSession): Boolean {
        val expiresAt = stored.expiresAt ?: return false
        val margin = REFRESH_MARGIN + Random.nextLong(REFRESH_JITTER.inWholeMilliseconds).milliseconds
        return expiresAt < clock.now() + margin
    }

    private suspend fun refresh(stored: OAuthStoredSession): OAuthStoredSession {
        val did = stored.did
        val refreshToken = stored.refreshToken ?: return withoutRefreshToken(stored)
        val clientKey =
            config.key(stored.clientKeyId)
                ?: end(
                    did,
                    OAuthSessionRevokedException(did, "the client key ${stored.clientKeyId} that started it is no longer configured"),
                )

        val (pdsUrl, metadata) = resolver.resolveForDid(did)
        if (requireAuthorizationAfterPdsChange && (pdsUrl != stored.pdsUrl || metadata.issuer != stored.issuer)) {
            val mismatch = if (metadata.issuer != stored.issuer) OAuthIssuerMismatchException(stored.issuer, metadata.issuer) else null
            val ended = OAuthSessionAuthorityChangedException(did, mismatch)
            revokeQuietly(ended) {
                agentFor(resolver.authorizationServerMetadata(stored.issuer), stored.dpopKey, clientKey).revoke(refreshToken)
            }
            end(did, ended)
        }
        if (metadata.issuer != stored.issuer) {
            val mismatch = OAuthIssuerMismatchException(stored.issuer, metadata.issuer)
            val ended = OAuthSessionRevokedException(did, "the account's authorization server changed", mismatch)
            revokeQuietly(ended) {
                agentFor(resolver.authorizationServerMetadata(stored.issuer), stored.dpopKey, clientKey).revoke(refreshToken)
            }
            end(did, ended)
        }

        val agent = agentFor(metadata, stored.dpopKey, clientKey)
        val tokens =
            try {
                agent.refresh(refreshToken)
            } catch (exception: OAuthServerResponseException) {
                if (exception.error != "invalid_grant") throw exception
                return recoverFromRejectedRefresh(stored, exception)
            }

        if (tokens.sub != did.toString()) {
            val mismatch = OAuthSubjectMismatchException(did, tokens.sub)
            val ended = OAuthSessionRevokedException(did, "the refreshed tokens belong to ${tokens.sub}", mismatch)
            revokeQuietly(ended) { agent.revoke(tokens.refreshToken ?: tokens.accessToken) }
            end(did, ended)
        }

        if (!scopesAreSubset(tokens.scope, stored.scope)) {
            val ended = OAuthSessionRevokedException(did, "the authorization server expanded the granted permissions")
            revokeQuietly(ended) { agent.revoke(tokens.refreshToken ?: tokens.accessToken) }
            end(did, ended)
        }

        val renewed =
            stored.copy(
                pdsUrl = pdsUrl,
                scope = tokens.scope,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: refreshToken,
                expiresAt = tokens.expiresAt(clock.now()),
            )
        sessionStore.save(renewed)
        return renewed
    }

    private suspend fun withoutRefreshToken(stored: OAuthStoredSession): OAuthStoredSession {
        if (!stored.isExpired(clock.now())) return stored
        end(stored.did, OAuthSessionExpiredException(stored.did, "the access token expired and no refresh token was issued"))
    }

    /**
     * Handles `invalid_grant` from a refresh. Another instance may have refreshed the session just
     * before, and may still be saving its result, so the store is read again after a short wait. If
     * it holds newer tokens, they are returned. Otherwise the session has ended.
     *
     * This is a mitigation, not a substitute for a shared [OAuthSessionLock]: a server with refresh
     * token reuse detection, the reference one included, has already ended the whole session by
     * then, so the newer tokens fail on their next use. It helps only with servers without reuse
     * detection, or when a shared lock's lease expired mid-refresh.
     *
     * `invalid_grant` does not say whether the session expired or was revoked, so it is reported
     * as [OAuthSessionRejectedException] rather than guessed from the description.
     */
    private suspend fun recoverFromRejectedRefresh(
        stored: OAuthStoredSession,
        exception: OAuthServerResponseException,
    ): OAuthStoredSession {
        val did = stored.did
        delay(REJECTED_REFRESH_GRACE)
        val latest = sessionStore.get(did)
        if (latest != null && (latest.accessToken != stored.accessToken || latest.refreshToken != stored.refreshToken)) {
            return latest
        }
        end(did, OAuthSessionRejectedException(did, exception))
    }

    /**
     * Runs [revoke], recording a failure on [primary] instead of throwing it: the session is
     * ending anyway, and the tokens expire on their own.
     */
    private suspend fun revokeQuietly(
        primary: Exception,
        revoke: suspend () -> Unit,
    ) {
        try {
            revoke()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            primary.addSuppressed(exception)
        }
    }

    private companion object {
        val REFRESH_MARGIN = 10.seconds
        val REFRESH_JITTER = 30.seconds
        val REJECTED_REFRESH_GRACE = 1.seconds
    }
}

internal fun TokenResponse.expiresAt(now: Instant): Instant? = expiresInSeconds?.let { now + it.seconds }

internal fun OAuthStoredSession.isExpired(now: Instant): Boolean = expiresAt?.let { it <= now } ?: false
