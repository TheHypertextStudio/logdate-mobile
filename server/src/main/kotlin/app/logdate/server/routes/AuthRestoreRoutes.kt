@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.RestoreCredentialService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.docs.AuthSessionDocs
import app.logdate.shared.model.PasskeyCredentialResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

internal fun Route.registerRestoreRegisterBeginRoute(
    accountRepository: AccountRepository,
    restoreCredentialService: RestoreCredentialService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    post("/register/begin", AuthSessionDocs.beginRestoreCredentialRegistration) {
        try {
            val account = resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) ?: return@post
            val options =
                restoreCredentialService.generateRegistrationOptions(
                    userId = account.id,
                    username = account.username,
                    displayName = account.displayName,
                )
            call.respond(HttpStatusCode.OK, RestoreRegisterBeginResponse(success = true, data = options))
        } catch (e: Exception) {
            Napier.e("Failed to begin restore key registration", e)
            call.respondForRequestException(e, "Failed to begin restore key registration", metrics)
        }
    }
}

internal fun Route.registerRestoreRegisterCompleteRoute(
    accountRepository: AccountRepository,
    restoreCredentialService: RestoreCredentialService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    post("/register/complete", AuthSessionDocs.completeRestoreCredentialRegistration) {
        try {
            val account = resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) ?: return@post
            val body = call.receive<RestoreRegisterCompleteRequest>()
            val registrationResponse =
                kotlinx.serialization.json.Json
                    .decodeFromString<PasskeyCredentialResponse>(body.credentialJson)
                    .toPasskeyRegistrationResponse()
            val result = restoreCredentialService.verifyRegistration(account.id, body.challenge, registrationResponse)
            if (!result.success) {
                return@post call.respondApiError(
                    HttpStatusCode.BadRequest,
                    "RESTORE_KEY_REGISTRATION_FAILED",
                    result.error ?: "Failed to verify restore key",
                    metrics,
                )
            }
            call.respond(HttpStatusCode.OK, mapOf("success" to true))
        } catch (e: Exception) {
            Napier.e("Failed to complete restore key registration", e)
            call.respondForRequestException(e, "Failed to complete restore key registration", metrics)
        }
    }
}

internal fun Route.registerRestoreBeginRoute(
    restoreCredentialService: RestoreCredentialService,
    metrics: AuthMetricsRegistry,
) {
    post("/begin", AuthSessionDocs.beginRestoreSignin) {
        try {
            val options = restoreCredentialService.generateAuthOptions()
            call.respond(
                HttpStatusCode.OK,
                SigninPasskeyBeginResponse(
                    success = true,
                    data =
                        SigninPasskeyBeginData(
                            challenge = options.challenge,
                            rpId = restoreCredentialService.relyingPartyId,
                            allowCredentials = emptyList(),
                            timeout = options.timeout,
                            userVerification = "discouraged",
                        ),
                ),
            )
        } catch (e: Exception) {
            Napier.e("Failed to begin restore sign-in", e)
            call.respondForRequestException(e, "Failed to begin restore sign-in", metrics)
        }
    }
}

internal fun Route.registerRestoreCompleteRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    restoreCredentialService: RestoreCredentialService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    post("/complete", AuthSessionDocs.completeRestoreSignin) {
        try {
            val request = call.receive<SigninPasskeyCompleteRequest>()
            val result =
                restoreCredentialService.verifyAuthentication(
                    challenge = request.challenge,
                    authenticationResponse = request.credential.toPasskeyAuthenticationResponse(),
                )
            val accountId = result.userId
            if (!result.success || accountId == null) {
                return@post call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    "RESTORE_FAILED",
                    result.error ?: "Restore sign-in failed",
                    metrics,
                )
            }
            val account =
                accountRepository.findById(accountId)
                    ?: return@post call.respondApiError(HttpStatusCode.NotFound, "ACCOUNT_NOT_FOUND", "Account not found", metrics)
            accountRepository.updateLastSignIn(account.id)
            val credentialSubject = result.credentialId ?: request.credential.id
            recordPasskeySignIn(account, credentialSubject, identityRepository)
            val response =
                issueAuthResponse(
                    account,
                    accountRepository,
                    identityRepository,
                    webAuthnService,
                    atprotoIdentityService,
                    tokenService,
                )
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            Napier.e("Failed to complete restore sign-in", e)
            call.respondForRequestException(e, "Failed to complete restore sign-in", metrics)
        }
    }
}
