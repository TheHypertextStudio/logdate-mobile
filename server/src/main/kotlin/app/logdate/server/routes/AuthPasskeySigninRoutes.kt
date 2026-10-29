@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.audit.AuditCategory
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.docs.AuthDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private const val METRIC_AUTH_SIGNIN_PASSKEY_BEGIN = "auth.signin.passkey.begin"
private const val METRIC_AUTH_SIGNIN_PASSKEY_COMPLETE = "auth.signin.passkey.complete"

internal fun Route.registerSigninPasskeyBeginRoute(
    accountRepository: AccountRepository,
    webAuthnService: WebAuthnPasskeyService,
    metrics: AuthMetricsRegistry,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    post("/begin", AuthDocs.beginPasskeySignin) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (!call.enforceRateLimit(rateLimiter, "signin.passkey.begin", SIGNIN_RATE_LIMIT, metrics)) {
                return@post
            }
            val request = call.receive<SigninPasskeyBeginRequest>()
            val account = request.username?.let { accountRepository.findByUsername(it) }
            val allowCredentials = account?.let { webAuthnService.getUserCredentials(it.id) } ?: emptyList()
            val authOptions =
                webAuthnService.generateAuthenticationOptions(
                    userId = account?.id,
                    allowedCredentials = allowCredentials,
                )

            call.respond(
                HttpStatusCode.OK,
                SigninPasskeyBeginResponse(
                    success = true,
                    data =
                        SigninPasskeyBeginData(
                            challenge = authOptions.challenge,
                            rpId = webAuthnService.relyingPartyId,
                            allowCredentials = authOptions.allowCredentials.map { PasskeyAllowCredentialDto(id = it) },
                            timeout = authOptions.timeout,
                            userVerification = "required",
                        ),
                ),
            )
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to begin passkey signin", e)
            call.respondForRequestException(e, "Failed to begin passkey signin", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNIN_PASSKEY_BEGIN, System.currentTimeMillis() - start, success)
        }
    }
}

internal fun Route.registerSigninPasskeyCompleteRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    post("/complete", AuthDocs.completePasskeySignin) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (!call.enforceRateLimit(rateLimiter, "signin.passkey.complete", SIGNIN_RATE_LIMIT, metrics)) {
                return@post
            }
            val request = call.receive<SigninPasskeyCompleteRequest>()
            val verificationResult =
                webAuthnService.verifyAuthentication(
                    challenge = request.challenge,
                    authenticationResponse = request.credential.toPasskeyAuthenticationResponse(),
                )
            val accountId = verificationResult.userId
            if (!verificationResult.success || accountId == null) {
                return@post call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    "AUTHENTICATION_FAILED",
                    verificationResult.error ?: "Failed to verify passkey",
                    metrics,
                )
            }

            val account = accountRepository.findById(accountId)
            if (account == null) {
                return@post call.respondApiError(
                    HttpStatusCode.NotFound,
                    "ACCOUNT_NOT_FOUND",
                    "Account not found",
                    metrics,
                )
            }

            accountRepository.updateLastSignIn(account.id)
            val passkeySubject = verificationResult.credentialId ?: request.credential.id
            recordPasskeySignIn(account, passkeySubject, identityRepository)

            val response =
                issueAuthResponse(
                    account = account,
                    accountRepository = accountRepository,
                    identityRepository = identityRepository,
                    webAuthnService = webAuthnService,
                    atprotoIdentityService = atprotoIdentityService,
                    tokenService = tokenService,
                    passkeyConfirmed = true,
                )
            call.recordAuthSuccess(AuditCategory.AUTH_SIGNIN_PASSKEY_SUCCESS, account.id, passkeySubject)
            call.respond(HttpStatusCode.OK, response)
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to complete passkey signin", e)
            call.respondForRequestException(e, "Failed to complete passkey signin", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNIN_PASSKEY_COMPLETE, System.currentTimeMillis() - start, success)
        }
    }
}
