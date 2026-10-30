package app.logdate.server.oauth

import kotlin.time.Instant

/**
 * Durable storage boundary for OAuth runtime state used by the authorization server.
 *
 * Single-node production needs these records to survive restarts even if the server process
 * restarts between PAR, code exchange, refresh, and revoke operations.
 *
 * Every grant records when the person approved it (`grantedAt`). Revoking an account's grants
 * records the revocation time, and a grant approved at or before that time can never be used
 * again. That rule holds even for a refresh that was already in progress when the revocation
 * happened, because the check and the token change happen in one transaction.
 *
 * The PostgreSQL store takes both times from the database clock, ignoring the times passed in, so
 * clock differences between server instances cannot let a grant outlive its revocation.
 */
interface OAuthRuntimeStateRepository {
    suspend fun saveAuthorizationRequest(request: StoredAuthorizationRequest): StoredAuthorizationRequest

    suspend fun findAuthorizationRequest(requestUri: String): StoredAuthorizationRequest?

    suspend fun deleteAuthorizationRequest(requestUri: String): Boolean

    suspend fun saveAuthorizationCode(code: StoredAuthorizationCode): StoredAuthorizationCode

    /**
     * Removes [code] and returns it, or returns `null` when it is not there. Two concurrent calls
     * for the same code cannot both return it.
     */
    suspend fun takeAuthorizationCode(code: String): StoredAuthorizationCode?

    suspend fun saveRefreshToken(token: StoredRefreshToken): StoredRefreshToken

    /**
     * Saves the first refresh token of a grant, unless the subject's grants were revoked at or
     * after [StoredRefreshToken.grantedAt]. Returns `false` when they were.
     */
    suspend fun saveGrantedRefreshToken(token: StoredRefreshToken): Boolean

    /**
     * Replaces [oldToken] with [replacement] in one transaction. Returns `false`, and changes
     * nothing, when [oldToken] is missing, revoked or expired at [now], when its grant was revoked
     * since it was approved, or when another refresh already replaced it.
     */
    suspend fun rotateRefreshToken(
        oldToken: String,
        replacement: StoredRefreshToken,
        now: Instant,
    ): Boolean

    suspend fun findRefreshToken(token: String): StoredRefreshToken?

    suspend fun revokeRefreshToken(
        token: String,
        revokedAt: Instant,
    ): Boolean

    suspend fun deleteRefreshToken(token: String): Boolean

    /**
     * Revokes every grant of [subjectDid] approved at or before [revokedAt]: it records the
     * revocation time, revokes the unrevoked refresh tokens and deletes unredeemed authorization
     * codes. Returns how many refresh tokens were revoked.
     */
    suspend fun revokeRefreshTokensForSubject(
        subjectDid: String,
        revokedAt: Instant,
    ): Int

    /**
     * Deletes the authorization requests, authorization codes and refresh tokens that expired
     * before [now], revoked or not, and returns how many it deleted. None of them can be used once
     * expired, so this only frees space.
     */
    suspend fun purgeExpired(now: Instant): Int
}

data class StoredAuthorizationRequest(
    val requestUri: String,
    val clientId: String,
    val clientName: String,
    val redirectUri: String,
    val scope: String,
    val state: String?,
    val loginHint: String?,
    val codeChallenge: String,
    val dpopKeyThumbprint: String,
    val clientAuthKeyId: String?,
    val clientAuthKeyThumbprint: String?,
    val expiresAt: Instant,
)

data class StoredAuthorizationCode(
    /** The SHA-256 of the code, base64url-encoded; the code itself is only given to the app. */
    val code: String,
    val clientId: String,
    val redirectUri: String,
    val subjectDid: String,
    val subjectHandle: String,
    val scope: String,
    val codeChallenge: String,
    val dpopKeyThumbprint: String,
    val clientAuthKeyId: String?,
    val clientAuthKeyThumbprint: String?,
    val expiresAt: Instant,
    /** When the person approved the request this code came from. */
    val grantedAt: Instant,
)

data class StoredRefreshToken(
    /** The SHA-256 of the token, base64url-encoded; the token itself is only given to the app. */
    val token: String,
    val clientId: String,
    val subjectDid: String,
    val scope: String,
    val dpopKeyThumbprint: String,
    val clientAuthKeyId: String?,
    val clientAuthKeyThumbprint: String?,
    val expiresAt: Instant,
    /** When the person approved the grant; rotation keeps it. */
    val grantedAt: Instant,
    val revokedAt: Instant? = null,
)

class InMemoryOAuthRuntimeStateRepository : OAuthRuntimeStateRepository {
    private val lock = Any()
    private val requestUris = linkedMapOf<String, StoredAuthorizationRequest>()
    private val authorizationCodes = linkedMapOf<String, StoredAuthorizationCode>()
    private val refreshTokens = linkedMapOf<String, StoredRefreshToken>()
    private val subjectRevocations = mutableMapOf<String, Instant>()

    override suspend fun purgeExpired(now: Instant): Int =
        synchronized(lock) {
            val before = requestUris.size + authorizationCodes.size + refreshTokens.size
            requestUris.values.removeAll { it.expiresAt < now }
            authorizationCodes.values.removeAll { it.expiresAt < now }
            refreshTokens.values.removeAll { it.expiresAt < now }
            before - (requestUris.size + authorizationCodes.size + refreshTokens.size)
        }

    override suspend fun saveAuthorizationRequest(request: StoredAuthorizationRequest): StoredAuthorizationRequest =
        synchronized(lock) {
            requestUris[request.requestUri] = request
            request
        }

    override suspend fun findAuthorizationRequest(requestUri: String): StoredAuthorizationRequest? =
        synchronized(lock) { requestUris[requestUri] }

    override suspend fun deleteAuthorizationRequest(requestUri: String): Boolean =
        synchronized(lock) { requestUris.remove(requestUri) != null }

    override suspend fun saveAuthorizationCode(code: StoredAuthorizationCode): StoredAuthorizationCode =
        synchronized(lock) {
            authorizationCodes[code.code] = code
            code
        }

    override suspend fun takeAuthorizationCode(code: String): StoredAuthorizationCode? =
        synchronized(lock) { authorizationCodes.remove(code) }

    override suspend fun saveRefreshToken(token: StoredRefreshToken): StoredRefreshToken =
        synchronized(lock) {
            refreshTokens[token.token] = token
            token
        }

    override suspend fun saveGrantedRefreshToken(token: StoredRefreshToken): Boolean =
        synchronized(lock) {
            if (revokedSince(token.subjectDid, token.grantedAt)) return@synchronized false
            refreshTokens[token.token] = token
            true
        }

    override suspend fun rotateRefreshToken(
        oldToken: String,
        replacement: StoredRefreshToken,
        now: Instant,
    ): Boolean =
        synchronized(lock) {
            val old = refreshTokens[oldToken] ?: return@synchronized false
            if (old.revokedAt != null || old.expiresAt <= now || revokedSince(old.subjectDid, old.grantedAt)) {
                return@synchronized false
            }
            refreshTokens.remove(oldToken)
            refreshTokens[replacement.token] = replacement
            true
        }

    override suspend fun findRefreshToken(token: String): StoredRefreshToken? = synchronized(lock) { refreshTokens[token] }

    override suspend fun revokeRefreshToken(
        token: String,
        revokedAt: Instant,
    ): Boolean =
        synchronized(lock) {
            val existing = refreshTokens[token] ?: return@synchronized false
            refreshTokens[token] = existing.copy(revokedAt = revokedAt)
            true
        }

    override suspend fun deleteRefreshToken(token: String): Boolean = synchronized(lock) { refreshTokens.remove(token) != null }

    override suspend fun revokeRefreshTokensForSubject(
        subjectDid: String,
        revokedAt: Instant,
    ): Int =
        synchronized(lock) {
            subjectRevocations[subjectDid] = maxOf(subjectRevocations[subjectDid] ?: revokedAt, revokedAt)
            authorizationCodes.values.removeIf { it.subjectDid == subjectDid }
            val active = refreshTokens.values.filter { it.subjectDid == subjectDid && it.revokedAt == null }
            active.forEach { refreshTokens[it.token] = it.copy(revokedAt = revokedAt) }
            active.size
        }

    private fun revokedSince(
        subjectDid: String,
        grantedAt: Instant,
    ): Boolean = subjectRevocations[subjectDid]?.let { it >= grantedAt } == true
}
