package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.clientAddress
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.oauth.OAuthAuthorizationService
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthException
import app.logdate.server.oauth.OAuthInvalidRequestException
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.oauth.OAuthPasskeyApprovalRequest
import app.logdate.server.oauth.OAuthPasskeyApprovalResponse
import app.logdate.server.oauth.OAuthPasskeyOptionsRequest
import app.logdate.server.oauth.OAuthPasskeyPage
import app.logdate.server.oauth.OAuthPasskeySignInService
import app.logdate.server.oauth.OAuthUseDpopNonceException
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.routes.docs.OAuthDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.pds.AuthorizationCodeTokenRequest
import studio.hypertext.atproto.pds.AuthorizationDecisionRequest
import studio.hypertext.atproto.pds.AuthorizationPrompt
import studio.hypertext.atproto.pds.AuthorizationPromptResponse
import studio.hypertext.atproto.pds.OAuthErrorResponse
import studio.hypertext.atproto.pds.OAuthRevokeRequest
import studio.hypertext.atproto.pds.OAuthTokenResponse
import studio.hypertext.atproto.pds.PdsDiscoveryService
import studio.hypertext.atproto.pds.PushedAuthorizationBody
import studio.hypertext.atproto.pds.PushedAuthorizationRequest
import studio.hypertext.atproto.pds.RefreshTokenGrantRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Serves OAuth discovery metadata and AT Protocol-compatible OAuth authorization endpoints.
 */
@OptIn(ExperimentalUuidApi::class)
fun Route.oauthRoutes(
    config: OAuthConfig,
    keyService: OAuthKeyService,
    discoveryService: PdsDiscoveryService? = null,
    authorizationService: OAuthAuthorizationService? = null,
    accountRepository: AccountRepository? = null,
    tokenService: TokenService? = null,
    identityService: AtprotoIdentityService? = null,
    passkeySignIn: OAuthPasskeySignInService? = null,
    passkeyRateLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(),
) {
    get("/.well-known/oauth-authorization-server", OAuthDocs.getAuthorizationServerMetadata) {
        call.respond(HttpStatusCode.OK, discoveryService?.authorizationServerMetadata() ?: config.authorizationServerMetadata())
    }

    get("/.well-known/oauth-protected-resource", OAuthDocs.getProtectedResourceMetadata) {
        call.respond(HttpStatusCode.OK, discoveryService?.protectedResourceMetadata() ?: config.protectedResourceMetadata())
    }

    get("/oauth/jwks", OAuthDocs.getJwks) {
        call.respond(HttpStatusCode.OK, keyService.jwks())
    }

    post("/oauth/par", OAuthDocs.pushAuthorizationRequest) {
        val service =
            authorizationService ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                OAuthErrorResponse("server_error", "OAuth PAR is not configured"),
            )
        val parameters = call.receiveParameters()
        val dpopProof =
            call.request
                .header(DPOP_HEADER)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@post call.respondOAuthError(OAuthInvalidRequestException("DPoP proof is required"))

        runCatching {
            service
                .createPushedAuthorizationRequest(
                    PushedAuthorizationRequest(
                        clientId = parameters.requireValue("client_id"),
                        redirectUri = parameters.requireValue("redirect_uri"),
                        scope = parameters.requireValue("scope"),
                        responseType = parameters.requireValue("response_type"),
                        codeChallenge = parameters.requireValue("code_challenge"),
                        codeChallengeMethod = parameters.requireValue("code_challenge_method"),
                        state = parameters["state"],
                        loginHint = parameters["login_hint"],
                        clientAssertionType = parameters["client_assertion_type"],
                        clientAssertion = parameters["client_assertion"],
                        dpopProof = dpopProof,
                        htu = call.endpointUrl(config),
                    ),
                ).getOrThrow()
        }.onSuccess { response ->
            call.response.header(DPOP_NONCE_HEADER, response.dpopNonce)
            call.respond(
                HttpStatusCode.Created,
                PushedAuthorizationBody(requestUri = response.requestUri, expiresInSeconds = response.expiresInSeconds),
            )
        }.onFailure { error ->
            call.respondOAuthError(error)
        }
    }

    get("/oauth/authorize", OAuthDocs.getAuthorizationPrompt) {
        val service =
            authorizationService ?: return@get call.respond(
                HttpStatusCode.NotImplemented,
                OAuthErrorResponse("server_error", "OAuth authorization is not configured"),
            )
        val requestUri =
            call.request.queryParameters["request_uri"]
                ?.trim()
                .orEmpty()
        if (passkeySignIn != null && call.isBrowserWithoutLogDateSession()) {
            val page =
                if (requestUri.isBlank()) {
                    OAuthPasskeyPage.RequestExpired
                } else {
                    try {
                        passkeySignIn.page(requestUri, clientId = call.request.queryParameters["client_id"]?.trim())
                    } catch (error: Exception) {
                        Napier.e("Could not load the authorization page", error)
                        OAuthPasskeyPage.ServerError
                    }
                }
            call.respondAuthorizationPage(page)
            return@get
        }
        if (requestUri.isBlank()) {
            call.respondOAuthError(OAuthInvalidRequestException("request_uri is required"))
            return@get
        }

        val account =
            resolveOAuthAccount(
                call = call,
                accountRepository = accountRepository,
                tokenService = tokenService,
                identityService = identityService,
            ) ?: return@get

        runCatching {
            service.loadAuthorizationPrompt(requestUri).getOrThrow().toResponse(account)
        }.onSuccess { prompt ->
            call.respond(HttpStatusCode.OK, prompt)
        }.onFailure { error ->
            call.respondOAuthError(error)
        }
    }

    post("/oauth/authorize", OAuthDocs.submitAuthorizationDecision) {
        val service =
            authorizationService ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                OAuthErrorResponse("server_error", "OAuth authorization is not configured"),
            )
        if (passkeySignIn != null && call.isBrowserWithoutLogDateSession()) {
            call.respondBrowserDecision(passkeySignIn, call.receiveParameters())
            return@post
        }
        val account =
            resolveOAuthAccount(
                call = call,
                accountRepository = accountRepository,
                tokenService = tokenService,
                identityService = identityService,
            ) ?: return@post
        val parameters = call.receiveParameters()
        val requestUri = parameters["request_uri"]?.trim().orEmpty()
        val decision = parameters["decision"]?.trim().orEmpty()
        if (requestUri.isBlank()) {
            call.respondOAuthError(OAuthInvalidRequestException("request_uri is required"))
            return@post
        }
        if (decision != "approve" && decision != "deny") {
            call.respondOAuthError(OAuthInvalidRequestException("decision must be approve or deny"))
            return@post
        }

        runCatching {
            service
                .completeAuthorization(
                    AuthorizationDecisionRequest(
                        requestUri = requestUri,
                        subjectDid = requireNotNull(account.did),
                        subjectHandle = requireNotNull(account.handle),
                        approved = decision == "approve",
                    ),
                ).getOrThrow()
        }.onSuccess { redirectUri ->
            call.respondRedirect(redirectUri, permanent = false)
        }.onFailure { error ->
            call.respondOAuthError(error)
        }
    }

    post("/oauth/authorize/passkey/options", OAuthDocs.getPasskeyOptions) {
        val signIn = passkeySignIn ?: return@post call.respondPasskeySignInDisabled()
        if (!call.allowPasskeyAttempt(passkeyRateLimiter, "options")) return@post
        val request =
            runCatching { call.receive<OAuthPasskeyOptionsRequest>() }
                .getOrElse { return@post call.respondOAuthError(OAuthInvalidRequestException("request_uri is required")) }
        runCatching { signIn.options(request.requestUri.trim()) }
            .onSuccess { options ->
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.OK, options)
            }.onFailure { error -> call.respondOAuthError(error) }
    }

    post("/oauth/authorize/passkey", OAuthDocs.approveWithPasskey) {
        val signIn = passkeySignIn ?: return@post call.respondPasskeySignInDisabled()
        if (!call.allowPasskeyAttempt(passkeyRateLimiter, "approve")) return@post
        val request =
            runCatching { call.receive<OAuthPasskeyApprovalRequest>() }
                .getOrElse {
                    return@post call.respondOAuthError(OAuthInvalidRequestException("request_uri, challenge and credential are required"))
                }
        runCatching { signIn.approve(request.requestUri.trim(), request.challenge, request.credential) }
            .onSuccess { redirectUri ->
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.OK, OAuthPasskeyApprovalResponse(redirectUri))
            }.onFailure { error -> call.respondOAuthError(error) }
    }

    post("/oauth/token", OAuthDocs.exchangeToken) {
        val service =
            authorizationService ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                OAuthErrorResponse("server_error", "OAuth token exchange is not configured"),
            )
        val parameters = call.receiveParameters()
        val dpopProof =
            call.request
                .header(DPOP_HEADER)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@post call.respondOAuthError(OAuthInvalidRequestException("DPoP proof is required"))

        runCatching {
            when (val grantType = parameters.requireValue("grant_type")) {
                "authorization_code" -> {
                    service
                        .exchangeAuthorizationCode(
                            AuthorizationCodeTokenRequest(
                                code = parameters.requireValue("code"),
                                redirectUri = parameters.requireValue("redirect_uri"),
                                clientId = parameters.requireValue("client_id"),
                                codeVerifier = parameters.requireValue("code_verifier"),
                                clientAssertionType = parameters["client_assertion_type"],
                                clientAssertion = parameters["client_assertion"],
                                dpopProof = dpopProof,
                                htu = call.endpointUrl(config),
                            ),
                        ).getOrThrow()
                }

                "refresh_token" -> {
                    service
                        .exchangeRefreshToken(
                            RefreshTokenGrantRequest(
                                refreshToken = parameters.requireValue("refresh_token"),
                                clientId = parameters.requireValue("client_id"),
                                clientAssertionType = parameters["client_assertion_type"],
                                clientAssertion = parameters["client_assertion"],
                                dpopProof = dpopProof,
                                htu = call.endpointUrl(config),
                            ),
                        ).getOrThrow()
                }

                else -> {
                    throw app.logdate.server.oauth
                        .OAuthUnsupportedGrantTypeException("Unsupported grant_type: $grantType")
                }
            }
        }.onSuccess { response ->
            call.respondOAuthToken(response, service.nonce())
        }.onFailure { error ->
            call.respondOAuthError(error)
        }
    }

    post("/oauth/revoke", OAuthDocs.revokeToken) {
        val service =
            authorizationService ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                OAuthErrorResponse("server_error", "OAuth revocation is not configured"),
            )
        val parameters = call.receiveParameters()
        val dpopProof =
            call.request
                .header(DPOP_HEADER)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@post call.respondOAuthError(OAuthInvalidRequestException("DPoP proof is required"))

        runCatching {
            service
                .revokeRefreshToken(
                    OAuthRevokeRequest(
                        refreshToken = parameters.requireValue("token"),
                        clientId = parameters.requireValue("client_id"),
                        clientAssertionType = parameters["client_assertion_type"],
                        clientAssertion = parameters["client_assertion"],
                        dpopProof = dpopProof,
                        htu = call.endpointUrl(config),
                    ),
                ).getOrThrow()
        }.onSuccess {
            call.response.header(DPOP_NONCE_HEADER, authorizationService.nonce())
            call.respond(HttpStatusCode.OK)
        }.onFailure { error ->
            call.respondOAuthError(error)
        }
    }
}

/**
 * Documentation-only view of the `application/x-www-form-urlencoded` body that
 * `POST /oauth/token` reads with `receiveParameters()`. Field names mirror RFC 6749 exactly.
 */
@Serializable
internal data class OAuthTokenForm(
    @SerialName("grant_type") val grantType: String,
    val code: String? = null,
    @SerialName("redirect_uri") val redirectUri: String? = null,
    @SerialName("client_id") val clientId: String,
    @SerialName("code_verifier") val codeVerifier: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("client_assertion_type") val clientAssertionType: String? = null,
    @SerialName("client_assertion") val clientAssertion: String? = null,
)

/** Documentation-only view of the form `POST /oauth/par` reads with `receiveParameters()`. */
@Serializable
internal data class OAuthParForm(
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String,
    val scope: String,
    @SerialName("response_type") val responseType: String,
    @SerialName("code_challenge") val codeChallenge: String,
    @SerialName("code_challenge_method") val codeChallengeMethod: String,
    val state: String? = null,
    @SerialName("login_hint") val loginHint: String? = null,
    @SerialName("client_assertion_type") val clientAssertionType: String? = null,
    @SerialName("client_assertion") val clientAssertion: String? = null,
)

/** Documentation-only view of the form `POST /oauth/authorize` reads with `receiveParameters()`. */
@Serializable
internal data class OAuthAuthorizationDecisionForm(
    @SerialName("request_uri") val requestUri: String,
    val decision: String,
)

/** Documentation-only view of the form `POST /oauth/revoke` reads with `receiveParameters()`. */
@Serializable
internal data class OAuthRevokeForm(
    val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_assertion_type") val clientAssertionType: String? = null,
    @SerialName("client_assertion") val clientAssertion: String? = null,
)

private suspend fun ApplicationCall.respondOAuthToken(
    tokenResponse: OAuthTokenResponse,
    nonce: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(HttpHeaders.Pragma, "no-cache")
    response.header(DPOP_NONCE_HEADER, nonce)
    respond(HttpStatusCode.OK, tokenResponse)
}

private suspend fun ApplicationCall.respondOAuthError(error: Throwable) {
    val oauthError = error as? OAuthException
    if (oauthError is OAuthUseDpopNonceException) {
        response.header(DPOP_NONCE_HEADER, oauthError.nonce)
    }

    if (oauthError != null) {
        respond(oauthError.status, OAuthErrorResponse(oauthError.error, oauthError.message))
        return
    }
    respondServerFailure(error)
}

/**
 * Answers a failure that is the server's, not the caller's. The details go to the log; the caller
 * gets a stable error code and no internal text such as SQL.
 */
private suspend fun ApplicationCall.respondServerFailure(error: Throwable) {
    Napier.e("OAuth request failed on the server", error)
    if (error.isDatabaseFailure()) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            OAuthErrorResponse("temporarily_unavailable", "The server could not reach its database. Try again shortly."),
        )
        return
    }
    respond(HttpStatusCode.InternalServerError, OAuthErrorResponse("server_error", "The server could not complete this request."))
}

private fun Throwable.isDatabaseFailure(): Boolean = generateSequence(this) { it.cause }.any { it is java.sql.SQLException }

/** A browser arriving from another app has no LogDate bearer token; the LogDate apps always send one. */
private fun ApplicationCall.isBrowserWithoutLogDateSession(): Boolean = request.header(HttpHeaders.Authorization) == null

/**
 * Handles the authorization page's own form post. Only cancelling is possible without a bearer
 * token; approving goes through a passkey sign-in.
 */
private suspend fun ApplicationCall.respondBrowserDecision(
    passkeySignIn: OAuthPasskeySignInService,
    parameters: io.ktor.http.Parameters,
) {
    val requestUri = parameters["request_uri"]?.trim().orEmpty()
    if (parameters["decision"]?.trim() != "deny") {
        respond(HttpStatusCode.Unauthorized, OAuthErrorResponse("login_required", "Sign in with a passkey to approve this request"))
        return
    }
    if (requestUri.isBlank()) {
        respondAuthorizationPage(OAuthPasskeyPage.RequestExpired)
        return
    }
    runCatching { passkeySignIn.deny(requestUri) }
        .onSuccess { redirectUri -> respondRedirect(redirectUri, permanent = false) }
        .onFailure { error ->
            if (error is OAuthInvalidRequestException) {
                respondAuthorizationPage(OAuthPasskeyPage.RequestExpired)
            } else {
                respondOAuthError(error)
            }
        }
}

/**
 * Applies the passkey sign-in limit per client address, like the app sign-in endpoints, and
 * answers `429` with `Retry-After` once it is spent.
 */
private suspend fun ApplicationCall.allowPasskeyAttempt(
    limiter: SlidingWindowRateLimiter,
    operation: String,
): Boolean {
    val key = "oauth.passkey.$operation:${clientAddress().ifBlank { "unknown" }.sha256Hex()}"
    if (limiter.allow(key, SIGNIN_RATE_LIMIT)) return true
    response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(key, SIGNIN_RATE_LIMIT).toString())
    respond(
        HttpStatusCode.TooManyRequests,
        OAuthErrorResponse("temporarily_unavailable", "Too many sign-in attempts from this network. Wait a minute and try again."),
    )
    return false
}

private fun String.sha256Hex(): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

private suspend fun ApplicationCall.respondPasskeySignInDisabled() =
    respond(
        HttpStatusCode.NotImplemented,
        OAuthErrorResponse("server_error", "Passkey sign-in on the authorization page is not enabled on this server"),
    )

private fun AuthorizationPrompt.toResponse(account: Account): AuthorizationPromptResponse =
    AuthorizationPromptResponse(
        clientId = clientId,
        clientName = clientName,
        redirectUri = redirectUri,
        scope = scope,
        state = state,
        loginHint = loginHint,
        did = requireNotNull(account.did),
        handle = requireNotNull(account.handle),
    )

private fun io.ktor.http.Parameters.requireValue(name: String): String {
    val value = this[name]?.trim().orEmpty()
    if (value.isBlank()) {
        throw OAuthInvalidRequestException("$name is required")
    }
    return value
}

/**
 * The URL a client called, built from the configured issuer origin and the request path. A DPoP proof's
 * `htu` is compared against it, so nothing a client sends, such as `X-Forwarded-Host`, can change it.
 */
private fun ApplicationCall.endpointUrl(config: OAuthConfig): String = config.issuerOrigin + request.path()

@OptIn(ExperimentalUuidApi::class)
private suspend fun resolveOAuthAccount(
    call: ApplicationCall,
    accountRepository: AccountRepository?,
    tokenService: TokenService?,
    identityService: AtprotoIdentityService?,
): Account? {
    if (accountRepository == null || tokenService == null || identityService == null) {
        call.respond(HttpStatusCode.NotImplemented, OAuthErrorResponse("server_error", "OAuth account resolution is not configured"))
        return null
    }

    val authHeader = call.request.header(HttpHeaders.Authorization)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        call.respond(HttpStatusCode.Unauthorized, OAuthErrorResponse("login_required", "LogDate bearer authentication is required"))
        return null
    }

    val accountId =
        tokenService
            .validateAccessToken(authHeader.removePrefix("Bearer ").trim())
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    if (accountId == null) {
        call.respond(HttpStatusCode.Unauthorized, OAuthErrorResponse("login_required", "Invalid LogDate bearer token"))
        return null
    }

    val account = accountRepository.findById(accountId)?.let { identityService.ensureIdentity(it) }
    if (account == null) {
        call.respond(HttpStatusCode.Unauthorized, OAuthErrorResponse("login_required", "Authenticated LogDate account was not found"))
        return null
    }
    return account
}

private const val DPOP_HEADER = "DPoP"
private const val DPOP_NONCE_HEADER = "DPoP-Nonce"
