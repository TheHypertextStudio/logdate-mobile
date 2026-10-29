@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.audit.AuditCategory
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.docs.AuthDocs
import app.logdate.shared.model.AccountStorageMode
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private const val METRIC_AUTH_SIGNUP_GOOGLE = "auth.signup.google"
private const val METRIC_AUTH_SIGNIN_GOOGLE = "auth.signin.google"
private const val GOOGLE_AUTH_NOT_CONFIGURED_CODE = "GOOGLE_AUTH_NOT_CONFIGURED"
private const val GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE = "Google sign-in is not configured on this server"

internal fun Route.registerSignupGoogleRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    newAccountStorageMode: AccountStorageMode,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    post("/google", AuthDocs.signupWithGoogle) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (!call.enforceRateLimit(rateLimiter, "signup.google", SIGNUP_RATE_LIMIT, metrics)) {
                return@post
            }
            val (request, claims) = verifiedGoogleRequest(call, googleIdTokenVerifier, metrics) ?: return@post

            val requestedOwnerId =
                call.parseRequiredCanonicalOwnerId(request.requestedOwnerId, metrics)
                    ?: return@post

            val resolution =
                resolveGoogleAccount(
                    claims = claims,
                    accountRepository = accountRepository,
                    identityRepository = identityRepository,
                    allowCreate = true,
                    requestedUsername = request.username,
                    requestedDisplayName = request.displayName,
                    requestedOwnerId = requestedOwnerId,
                    newAccountStorageMode = newAccountStorageMode,
                    call = call,
                )
            if (resolution == null) {
                return@post call.respondApiError(
                    HttpStatusCode.Conflict,
                    "ACCOUNT_LINK_CONFLICT",
                    "Google account could not be linked automatically",
                    metrics,
                )
            }
            accountRepository.updateLastSignIn(resolution.account.id)

            val response =
                issueAuthResponse(
                    resolution.account,
                    accountRepository,
                    identityRepository,
                    webAuthnService,
                    atprotoIdentityService,
                    tokenService,
                )
            call.recordAuthSuccess(AuditCategory.AUTH_SIGNUP_GOOGLE_SUCCESS, resolution.account.id)
            call.respond(HttpStatusCode.OK, response)
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to complete Google signup", e)
            call.respondForRequestException(e, "Failed to complete Google signup", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNUP_GOOGLE, System.currentTimeMillis() - start, success)
        }
    }
}

internal fun Route.registerSigninGoogleRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    newAccountStorageMode: AccountStorageMode,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    post("/google", AuthDocs.signinWithGoogle) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (!call.enforceRateLimit(rateLimiter, "signin.google", SIGNIN_RATE_LIMIT, metrics)) {
                return@post
            }
            val (request, claims) = verifiedGoogleRequest(call, googleIdTokenVerifier, metrics) ?: return@post

            val resolution =
                resolveGoogleAccount(
                    claims = claims,
                    accountRepository = accountRepository,
                    identityRepository = identityRepository,
                    allowCreate = false,
                    requestedUsername = request.username,
                    requestedDisplayName = request.displayName,
                    requestedOwnerId = null,
                    newAccountStorageMode = newAccountStorageMode,
                    call = call,
                )
                    ?: return@post call.respondApiError(
                        HttpStatusCode.NotFound,
                        "ACCOUNT_NOT_FOUND_SIGNUP_REQUIRED",
                        "No account found. Use Google signup first.",
                        metrics,
                    )
            accountRepository.updateLastSignIn(resolution.account.id)

            val response =
                issueAuthResponse(
                    resolution.account,
                    accountRepository,
                    identityRepository,
                    webAuthnService,
                    atprotoIdentityService,
                    tokenService,
                )
            call.recordAuthSuccess(AuditCategory.AUTH_SIGNIN_GOOGLE_SUCCESS, resolution.account.id)
            call.respond(HttpStatusCode.OK, response)
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to complete Google signin", e)
            call.respondForRequestException(e, "Failed to complete Google signin", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNIN_GOOGLE, System.currentTimeMillis() - start, success)
        }
    }
}

private suspend fun verifiedGoogleRequest(
    call: ApplicationCall,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
): Pair<GoogleAuthRequest, GoogleIdTokenClaims>? {
    if (!googleIdTokenVerifier.isConfigured()) {
        return call.rejectAuthRequest(
            HttpStatusCode.ServiceUnavailable,
            GOOGLE_AUTH_NOT_CONFIGURED_CODE,
            GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE,
            metrics,
        )
    }
    val request = call.receive<GoogleAuthRequest>()
    val claims = googleIdTokenVerifier.verify(request.idToken, request.nonce)
    if (claims == null) {
        return call.rejectAuthRequest(
            HttpStatusCode.Unauthorized,
            "GOOGLE_TOKEN_INVALID",
            "Google token is invalid",
            metrics,
        )
    }
    if (!claims.emailVerified) {
        return call.rejectAuthRequest(
            HttpStatusCode.BadRequest,
            "GOOGLE_EMAIL_UNVERIFIED",
            "Google account email must be verified",
            metrics,
        )
    }
    return request to claims
}
