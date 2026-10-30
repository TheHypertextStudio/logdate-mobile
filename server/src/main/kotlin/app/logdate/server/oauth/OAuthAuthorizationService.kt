package app.logdate.server.oauth

import io.github.aakira.napier.Napier
import studio.hypertext.atproto.pds.AuthorizationCodeTokenRequest
import studio.hypertext.atproto.pds.AuthorizationDecisionRequest
import studio.hypertext.atproto.pds.AuthorizationPrompt
import studio.hypertext.atproto.pds.OAuthRevokeRequest
import studio.hypertext.atproto.pds.OAuthTokenResponse
import studio.hypertext.atproto.pds.PdsOAuthService
import studio.hypertext.atproto.pds.PushedAuthorizationRequest
import studio.hypertext.atproto.pds.PushedAuthorizationResponse
import studio.hypertext.atproto.pds.RefreshTokenGrantRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Stores PAR requests, authorization codes, and refresh tokens for the server OAuth flow.
 */
class OAuthAuthorizationService(
    private val clientMetadataResolver: OAuthClientMetadataResolver,
    private val dpopVerifier: OAuthDpopVerifier,
    private val accessTokenService: OAuthAccessTokenService,
    private val nonceService: OAuthNonceService,
    private val authorizationServerIssuer: String,
    private val runtimeStateRepository: OAuthRuntimeStateRepository = InMemoryOAuthRuntimeStateRepository(),
    private val clock: Clock = Clock.System,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val trustedClientIds: Set<String> = emptySet(),
    private val usedCodeChallenges: OAuthReplayStore = InMemoryOAuthReplayStore(clock),
) : PdsOAuthService,
    OAuthGrantRevoker {
    /**
     * Validates and stores a pushed authorization request.
     */
    suspend fun createPushedAuthorizationRequest(
        clientId: String,
        redirectUri: String,
        scope: String,
        responseType: String,
        codeChallenge: String,
        codeChallengeMethod: String,
        state: String?,
        loginHint: String?,
        clientAssertionType: String? = null,
        clientAssertion: String? = null,
        dpopProof: String,
        htu: String,
    ): PushedAuthorizationResponse {
        if (responseType != "code") {
            throw OAuthInvalidRequestException("response_type must be code")
        }
        if (codeChallenge.isBlank()) {
            throw OAuthInvalidRequestException("code_challenge is required")
        }
        if (codeChallengeMethod != "S256") {
            throw OAuthInvalidRequestException("code_challenge_method must be S256")
        }
        val requestedScopes = OAuthScopes.parse(scope)
        if (OAuthScopes.ATPROTO !in requestedScopes) {
            throw OAuthInvalidRequestException("scope must include atproto")
        }

        // The proof is checked before the client's metadata is fetched, so a request that cannot
        // prove possession of a key never makes the server fetch anything.
        val proof = dpopVerifier.verify(dpopProof, method = "POST", htu = htu).getOrElse { throw it }
        val authenticatedClient =
            clientMetadataResolver
                .authenticateClient(
                    clientId = clientId,
                    clientAssertionType = clientAssertionType,
                    clientAssertion = clientAssertion,
                    authorizationServerIssuer = authorizationServerIssuer,
                ).getOrElse { throw it }
        val clientMetadata = authenticatedClient.metadata
        if (!clientMetadata.supportsRedirect(redirectUri)) {
            throw OAuthInvalidClientException("redirect_uri is not declared by the client metadata")
        }
        val declaredScopes = clientMetadata.scopeSet()
        if (declaredScopes.isNotEmpty() && !declaredScopes.containsAll(requestedScopes)) {
            throw OAuthInvalidClientException("Requested scopes exceed the client metadata document scope")
        }

        if (!usedCodeChallenges.firstUse("pkce:${codeChallenge.trim()}", CODE_CHALLENGE_MEMORY)) {
            throw OAuthInvalidRequestException("code_challenge has already been used; generate a new PKCE verifier")
        }
        val requestUri = "urn:ietf:params:oauth:request_uri:${randomToken()}"
        runtimeStateRepository.saveAuthorizationRequest(
            StoredAuthorizationRequest(
                requestUri = requestUri,
                clientId = clientId,
                clientName = clientMetadata.client_name ?: clientId,
                redirectUri = redirectUri,
                scope = OAuthScopes.granted(requestedScopes).joinToString(" "),
                state = state?.trim()?.takeIf(String::isNotEmpty),
                loginHint = loginHint?.trim()?.takeIf(String::isNotEmpty),
                codeChallenge = codeChallenge.trim(),
                dpopKeyThumbprint = proof.keyThumbprint,
                clientAuthKeyId = authenticatedClient.clientKeyId,
                clientAuthKeyThumbprint = authenticatedClient.clientKeyThumbprint,
                expiresAt = clock.now() + REQUEST_URI_TTL,
            ),
        )
        purgeExpiredStateIfDue()
        return PushedAuthorizationResponse(
            requestUri = requestUri,
            expiresInSeconds = REQUEST_URI_TTL.inWholeSeconds,
            dpopNonce = nonceService.currentNonce(),
        )
    }

    /**
     * Returns prompt details for a stored authorization request.
     */
    suspend fun describeAuthorizationRequest(requestUri: String): AuthorizationPrompt {
        val stored = runtimeStateRepository.requireValidRequest(requestUri, clock)
        return AuthorizationPrompt(
            requestUri = stored.requestUri,
            clientId = stored.clientId,
            clientName = stored.clientName,
            redirectUri = stored.redirectUri,
            scope = stored.scope,
            state = stored.state,
            loginHint = stored.loginHint,
        )
    }

    /**
     * Approves or denies the stored authorization request.
     */
    suspend fun completeAuthorization(
        requestUri: String,
        subjectDid: String,
        subjectHandle: String,
        approved: Boolean,
    ): String {
        val stored = runtimeStateRepository.requireValidRequest(requestUri, clock)
        runtimeStateRepository.claimAuthorizationRequest(requestUri)

        if (!loginHintMatches(stored.loginHint, subjectDid = subjectDid, subjectHandle = subjectHandle)) {
            throw OAuthInvalidRequestException("login_hint does not match the authenticated account")
        }
        if (!approved) return deniedRedirect(stored)

        val code = randomToken()
        runtimeStateRepository.saveAuthorizationCode(
            StoredAuthorizationCode(
                code = code,
                clientId = stored.clientId,
                redirectUri = stored.redirectUri,
                subjectDid = subjectDid,
                subjectHandle = subjectHandle,
                scope = stored.scope,
                codeChallenge = stored.codeChallenge,
                dpopKeyThumbprint = stored.dpopKeyThumbprint,
                clientAuthKeyId = stored.clientAuthKeyId,
                clientAuthKeyThumbprint = stored.clientAuthKeyThumbprint,
                expiresAt = clock.now() + AUTHORIZATION_CODE_TTL,
                grantedAt = clock.now(),
            ),
        )
        return appendQuery(
            stored.redirectUri,
            buildMap {
                put("code", code)
                stored.state?.let { put("state", it) }
                put("iss", authorizationServerIssuer)
            },
        )
    }

    /**
     * Denies the stored authorization request without a signed-in account and returns the
     * client's redirect carrying `error=access_denied`.
     *
     * Only the browser that started the request knows its `request_uri`, and denying it affects
     * nothing else, so cancelling does not need a sign-in.
     */
    suspend fun denyAuthorization(requestUri: String): String {
        val stored = runtimeStateRepository.requireValidRequest(requestUri, clock)
        runtimeStateRepository.claimAuthorizationRequest(requestUri)
        return deniedRedirect(stored)
    }

    private fun deniedRedirect(stored: StoredAuthorizationRequest): String =
        appendQuery(
            stored.redirectUri,
            buildMap {
                put("error", "access_denied")
                stored.state?.let { put("state", it) }
                put("iss", authorizationServerIssuer)
            },
        )

    /**
     * Exchanges an authorization code for a DPoP-bound token pair.
     */
    suspend fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        clientId: String,
        codeVerifier: String,
        clientAssertionType: String? = null,
        clientAssertion: String? = null,
        dpopProof: String,
        htu: String,
    ): OAuthTokenResponse {
        val stored =
            runtimeStateRepository
                .takeAuthorizationCode(code)
                ?.takeIf { clock.now() < it.expiresAt }
                ?: throw OAuthInvalidGrantException("Authorization code is invalid or expired")
        if (stored.clientId != clientId || stored.redirectUri != redirectUri) {
            throw OAuthInvalidGrantException("Authorization code does not match the client or redirect_uri")
        }
        val authenticatedClient =
            clientMetadataResolver
                .authenticateClient(
                    clientId = clientId,
                    clientAssertionType = clientAssertionType,
                    clientAssertion = clientAssertion,
                    authorizationServerIssuer = authorizationServerIssuer,
                ).getOrElse { throw it }
        authenticatedClient.requireBinding(
            keyId = stored.clientAuthKeyId,
            keyThumbprint = stored.clientAuthKeyThumbprint,
        )
        if (pkceChallenge(codeVerifier) != stored.codeChallenge) {
            throw OAuthInvalidGrantException("code_verifier did not match the authorization request")
        }

        val expectedNonce = nonceService.currentNonce()
        val proof =
            dpopVerifier
                .verify(
                    proof = dpopProof,
                    method = "POST",
                    htu = htu,
                    expectedNonce = expectedNonce,
                ).getOrElse { throw it }
        if (proof.keyThumbprint != stored.dpopKeyThumbprint) {
            throw OAuthInvalidGrantException("DPoP key did not match the original pushed authorization request")
        }

        val issued =
            accessTokenService.issueAccessToken(
                subjectDid = stored.subjectDid,
                clientId = stored.clientId,
                scope = stored.scope,
                keyThumbprint = proof.keyThumbprint,
            )
        val refreshToken = randomToken()
        val saved =
            runtimeStateRepository.saveGrantedRefreshToken(
                StoredRefreshToken(
                    token = refreshToken,
                    clientId = stored.clientId,
                    subjectDid = stored.subjectDid,
                    scope = stored.scope,
                    dpopKeyThumbprint = proof.keyThumbprint,
                    clientAuthKeyId = stored.clientAuthKeyId,
                    clientAuthKeyThumbprint = stored.clientAuthKeyThumbprint,
                    expiresAt = clock.now() + refreshTokenLifetime(stored.clientId),
                    grantedAt = stored.grantedAt,
                ),
            )
        if (!saved) throw OAuthInvalidGrantException("The account revoked this grant")
        return OAuthTokenResponse(
            access_token = issued.token,
            token_type = "DPoP",
            expires_in = issued.expiresInSeconds,
            refresh_token = refreshToken,
            sub = stored.subjectDid,
            scope = stored.scope,
        )
    }

    /**
     * Exchanges a refresh token for a new DPoP-bound token pair.
     */
    suspend fun exchangeRefreshToken(
        refreshToken: String,
        clientId: String,
        clientAssertionType: String? = null,
        clientAssertion: String? = null,
        dpopProof: String,
        htu: String,
    ): OAuthTokenResponse {
        val stored =
            runtimeStateRepository
                .findRefreshToken(refreshToken)
                ?.takeIf { it.revokedAt == null && clock.now() < it.expiresAt }
                ?: throw OAuthInvalidGrantException("Refresh token is invalid or expired")
        if (stored.clientId != clientId) {
            throw OAuthInvalidGrantException("Refresh token does not belong to this client")
        }
        val authenticatedClient =
            clientMetadataResolver
                .authenticateClient(
                    clientId = clientId,
                    clientAssertionType = clientAssertionType,
                    clientAssertion = clientAssertion,
                    authorizationServerIssuer = authorizationServerIssuer,
                ).getOrElse { throw it }
        authenticatedClient.requireBinding(
            keyId = stored.clientAuthKeyId,
            keyThumbprint = stored.clientAuthKeyThumbprint,
        )

        val expectedNonce = nonceService.currentNonce()
        val proof =
            dpopVerifier
                .verify(
                    proof = dpopProof,
                    method = "POST",
                    htu = htu,
                    expectedNonce = expectedNonce,
                ).getOrElse { throw it }
        if (proof.keyThumbprint != stored.dpopKeyThumbprint) {
            throw OAuthInvalidGrantException("Refresh token DPoP key did not match the original grant")
        }

        val issued =
            accessTokenService.issueAccessToken(
                subjectDid = stored.subjectDid,
                clientId = stored.clientId,
                scope = stored.scope,
                keyThumbprint = stored.dpopKeyThumbprint,
            )
        val rotatedRefreshToken = rotate(refreshToken, stored)
        return OAuthTokenResponse(
            access_token = issued.token,
            token_type = "DPoP",
            expires_in = issued.expiresInSeconds,
            refresh_token = rotatedRefreshToken,
            sub = stored.subjectDid,
            scope = stored.scope,
        )
    }

    /**
     * Revokes [refreshToken]. Repeated revocations are treated as success.
     */
    suspend fun revokeRefreshToken(
        refreshToken: String,
        clientId: String,
        clientAssertionType: String? = null,
        clientAssertion: String? = null,
        dpopProof: String,
        htu: String,
    ) {
        val stored = runtimeStateRepository.findRefreshToken(refreshToken) ?: return
        if (stored.clientId != clientId) {
            throw OAuthInvalidGrantException("Refresh token does not belong to this client")
        }
        val authenticatedClient =
            clientMetadataResolver
                .authenticateClient(
                    clientId = clientId,
                    clientAssertionType = clientAssertionType,
                    clientAssertion = clientAssertion,
                    authorizationServerIssuer = authorizationServerIssuer,
                ).getOrElse { throw it }
        authenticatedClient.requireBinding(
            keyId = stored.clientAuthKeyId,
            keyThumbprint = stored.clientAuthKeyThumbprint,
        )

        val expectedNonce = nonceService.currentNonce()
        val proof =
            dpopVerifier
                .verify(
                    proof = dpopProof,
                    method = "POST",
                    htu = htu,
                    expectedNonce = expectedNonce,
                ).getOrElse { throw it }
        if (proof.keyThumbprint != stored.dpopKeyThumbprint) {
            throw OAuthInvalidGrantException("Refresh token DPoP key did not match the original grant")
        }

        runtimeStateRepository.revokeRefreshToken(refreshToken, clock.now())
    }

    /**
     * Returns the currently valid server nonce for DPoP responses.
     */
    override fun nonce(): String = nonceService.currentNonce()

    override suspend fun createPushedAuthorizationRequest(request: PushedAuthorizationRequest): Result<PushedAuthorizationResponse> =
        runCatching {
            createPushedAuthorizationRequest(
                clientId = request.clientId,
                redirectUri = request.redirectUri,
                scope = request.scope,
                responseType = request.responseType,
                codeChallenge = request.codeChallenge,
                codeChallengeMethod = request.codeChallengeMethod,
                state = request.state,
                loginHint = request.loginHint,
                clientAssertionType = request.clientAssertionType,
                clientAssertion = request.clientAssertion,
                dpopProof = request.dpopProof,
                htu = request.htu,
            )
        }

    override fun loadAuthorizationPrompt(requestUri: String): Result<AuthorizationPrompt> =
        runCatching { kotlinx.coroutines.runBlocking { describeAuthorizationRequest(requestUri) } }

    override fun completeAuthorization(request: AuthorizationDecisionRequest): Result<String> =
        runCatching {
            kotlinx.coroutines.runBlocking {
                completeAuthorization(
                    requestUri = request.requestUri,
                    subjectDid = request.subjectDid,
                    subjectHandle = request.subjectHandle,
                    approved = request.approved,
                )
            }
        }

    override suspend fun exchangeAuthorizationCode(request: AuthorizationCodeTokenRequest): Result<OAuthTokenResponse> =
        runCatching {
            exchangeAuthorizationCode(
                code = request.code,
                redirectUri = request.redirectUri,
                clientId = request.clientId,
                codeVerifier = request.codeVerifier,
                clientAssertionType = request.clientAssertionType,
                clientAssertion = request.clientAssertion,
                dpopProof = request.dpopProof,
                htu = request.htu,
            )
        }

    override suspend fun exchangeRefreshToken(request: RefreshTokenGrantRequest): Result<OAuthTokenResponse> =
        runCatching {
            exchangeRefreshToken(
                refreshToken = request.refreshToken,
                clientId = request.clientId,
                clientAssertionType = request.clientAssertionType,
                clientAssertion = request.clientAssertion,
                dpopProof = request.dpopProof,
                htu = request.htu,
            )
        }

    override suspend fun revokeRefreshToken(request: OAuthRevokeRequest): Result<Unit> =
        runCatching {
            revokeRefreshToken(
                refreshToken = request.refreshToken,
                clientId = request.clientId,
                clientAssertionType = request.clientAssertionType,
                clientAssertion = request.clientAssertion,
                dpopProof = request.dpopProof,
                htu = request.htu,
            )
        }

    private fun pkceChallenge(codeVerifier: String): String {
        if (codeVerifier.isBlank()) {
            throw OAuthInvalidGrantException("code_verifier is required")
        }
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(codeVerifier.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Revokes every refresh token issued for [subjectDid], so no app can keep acting for the
     * account once its access tokens expire. Returns how many were revoked.
     */
    override suspend fun revokeAllForSubject(subjectDid: String): Int {
        val revoked = runtimeStateRepository.revokeRefreshTokensForSubject(subjectDid, clock.now())
        Napier.i("Revoked $revoked OAuth refresh tokens for an account")
        return revoked
    }

    /**
     * Replaces [refreshToken] with a new one and returns it. The read of [stored] may be stale: another
     * refresh or a revocation can land after it, so the rotation re-checks everything and replaces the
     * token in one transaction. The caller only returns tokens when this succeeds.
     */
    private suspend fun rotate(
        refreshToken: String,
        stored: StoredRefreshToken,
    ): String {
        val rotatedRefreshToken = randomToken()
        val rotated =
            runtimeStateRepository.rotateRefreshToken(
                oldToken = refreshToken,
                replacement =
                    stored.copy(
                        token = rotatedRefreshToken,
                        expiresAt = clock.now() + refreshTokenLifetime(stored.clientId),
                        revokedAt = null,
                    ),
                now = clock.now(),
            )
        if (!rotated) throw OAuthInvalidGrantException("Refresh token is invalid or expired")
        return rotatedRefreshToken
    }

    /**
     * Deletes expired requests, codes and refresh tokens at most once per
     * [RUNTIME_STATE_PURGE_INTERVAL]. New requests are what fill the store, so they also empty it.
     * A failed purge is logged and retried at the next interval; it never fails the request.
     */
    private suspend fun purgeExpiredStateIfDue() {
        val now = clock.now()
        val due = nextPurgeAt.get()
        if (now < due || !nextPurgeAt.compareAndSet(due, now + RUNTIME_STATE_PURGE_INTERVAL)) return
        try {
            val removed = runtimeStateRepository.purgeExpired(now)
            if (removed > 0) Napier.i("Removed $removed expired OAuth authorization requests, codes and refresh tokens")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("Could not remove expired OAuth authorization state", error)
        }
    }

    private fun refreshTokenLifetime(clientId: String) =
        if (clientId in trustedClientIds) TRUSTED_REFRESH_TOKEN_TTL else UNTRUSTED_REFRESH_TOKEN_TTL

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private val nextPurgeAt = AtomicReference(Instant.DISTANT_PAST)

    private companion object {
        private val RUNTIME_STATE_PURGE_INTERVAL = 10.minutes
        private val REQUEST_URI_TTL = 5.minutes
        private val AUTHORIZATION_CODE_TTL = 1.minutes
        private val TRUSTED_REFRESH_TOKEN_TTL = 30.days
        private val UNTRUSTED_REFRESH_TOKEN_TTL = 14.days
        private val CODE_CHALLENGE_MEMORY = 1.hours
        private const val TOKEN_BYTES = 32
        private const val SHA_256_ALGORITHM = "SHA-256"
    }
}

private fun AuthenticatedOAuthClient.requireBinding(
    keyId: String?,
    keyThumbprint: String?,
) {
    if (clientKeyId != keyId || clientKeyThumbprint != keyThumbprint) {
        throw OAuthInvalidGrantException("Client authentication did not match the original grant")
    }
}

private suspend fun OAuthRuntimeStateRepository.requireValidRequest(
    requestUri: String,
    clock: Clock,
): StoredAuthorizationRequest {
    val stored = findAuthorizationRequest(requestUri) ?: throw OAuthInvalidRequestException("request_uri is invalid or expired")
    if (clock.now() >= stored.expiresAt) {
        deleteAuthorizationRequest(requestUri)
        throw OAuthInvalidRequestException("request_uri is invalid or expired")
    }
    return stored
}

/**
 * Removes [requestUri] so it can be decided once. Two decisions racing for the same request
 * cannot both succeed: only the one that actually removed it continues.
 */
private suspend fun OAuthRuntimeStateRepository.claimAuthorizationRequest(requestUri: String) {
    if (!deleteAuthorizationRequest(requestUri)) {
        throw OAuthInvalidRequestException("request_uri is invalid or expired")
    }
}

/**
 * Revokes the OAuth grants an account has given to apps. Removing a passkey and deleting the
 * account call it, so apps lose access when the account's own sign-in changes.
 */
fun interface OAuthGrantRevoker {
    suspend fun revokeAllForSubject(subjectDid: String): Int
}

/**
 * Returns `true` when an authorization request's [loginHint] names the account with
 * [subjectDid] and [subjectHandle], or when there is no hint.
 */
internal fun loginHintMatches(
    loginHint: String?,
    subjectDid: String,
    subjectHandle: String,
): Boolean {
    val hint = loginHint?.trim()?.removePrefix("@")?.lowercase() ?: return true
    return hint == subjectHandle.lowercase() || hint == subjectDid.lowercase()
}

private fun appendQuery(
    redirectUri: String,
    parameters: Map<String, String>,
): String {
    val separator = if ('?' in redirectUri) '&' else '?'
    return buildString {
        append(redirectUri)
        append(separator)
        append(parameters.entries.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" })
    }
}

private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
