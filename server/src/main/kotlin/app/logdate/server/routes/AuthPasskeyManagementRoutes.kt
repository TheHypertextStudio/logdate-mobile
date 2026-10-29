@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.TokenService
import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.AccountSignInMethods
import app.logdate.server.oauth.OAuthGrantRevoker
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.docs.AccountDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun Route.registerListPasskeysRoute(
    accountRepository: AccountRepository,
    webAuthnService: WebAuthnPasskeyService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    get("/me/passkeys", AccountDocs.listPasskeys) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@get
            call.respond(
                HttpStatusCode.OK,
                PasskeyListResponse(
                    success = true,
                    data = webAuthnService.getPasskeysForUser(account.id),
                ),
            )
        } catch (e: Exception) {
            Napier.e("Failed to list passkeys", e)
            call.respondForRequestException(e, "Failed to list passkeys", metrics)
        }
    }
}

internal fun Route.registerAddPasskeyBeginRoute(
    accountRepository: AccountRepository,
    webAuthnService: WebAuthnPasskeyService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    post("/me/passkeys/begin", AccountDocs.beginAddPasskey) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@post
            val options =
                webAuthnService.generateRegistrationOptions(
                    userId = account.id,
                    username = account.username,
                    displayName = account.displayName,
                )
            call.respond(HttpStatusCode.OK, RestoreRegisterBeginResponse(success = true, data = options))
        } catch (e: Exception) {
            Napier.e("Failed to begin passkey registration", e)
            call.respondForRequestException(e, "Failed to begin passkey registration", metrics)
        }
    }
}

internal fun Route.registerAddPasskeyCompleteRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    post("/me/passkeys/complete", AccountDocs.completeAddPasskey) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@post
            val request = call.receive<AddPasskeyCompleteRequest>()
            val verified =
                when (
                    val outcome =
                        webAuthnService.verifyRegistrationOnly(
                            userId = account.id,
                            challenge = request.challenge,
                            registrationResponse = request.credential.toPasskeyRegistrationResponse(),
                        )
                ) {
                    is WebAuthnPasskeyService.VerificationOutcome.Failure ->
                        return@post call.respondApiError(
                            HttpStatusCode.BadRequest,
                            "PASSKEY_VERIFICATION_FAILED",
                            outcome.error,
                            metrics,
                        )
                    is WebAuthnPasskeyService.VerificationOutcome.Success -> outcome.data.withNickname(request.nickname)
                }

            if (!webAuthnService.storeVerifiedPasskey(account.id, verified)) {
                return@post call.respondApiError(
                    HttpStatusCode.InternalServerError,
                    "PASSKEY_STORE_FAILED",
                    "Failed to store passkey",
                    metrics,
                )
            }

            identityRepository.save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = account.id,
                    provider = IdentityProvider.PASSKEY,
                    providerSubject = verified.credentialId,
                    email = account.email,
                    emailVerified = account.emailVerified,
                    createdAt = Clock.System.now(),
                ),
            )

            call.respond(HttpStatusCode.Created, PasskeyResponse(success = true, data = verified.passkey))
        } catch (e: Exception) {
            Napier.e("Failed to complete passkey registration", e)
            call.respondForRequestException(e, "Failed to complete passkey registration", metrics)
        }
    }
}

internal fun Route.registerDeletePasskeyRoute(
    accountRepository: AccountRepository,
    webAuthnService: WebAuthnPasskeyService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    oauthGrantRevoker: OAuthGrantRevoker?,
    credentialMutationLock: AccountCredentialMutationLock,
    signInMethods: AccountSignInMethods,
) {
    delete("/me/passkeys/{credentialId}", AccountDocs.deletePasskey) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@delete
            val credentialId = call.parameters["credentialId"]?.trim().orEmpty()
            if (credentialId.isBlank()) {
                return@delete call.respondApiError(
                    HttpStatusCode.BadRequest,
                    "VALIDATION_ERROR",
                    "Credential ID is required",
                    metrics,
                )
            }

            credentialMutationLock.withLock(account.id) {
                val current = resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) ?: return@withLock
                if (!current.isActive || current.moderation != null) {
                    return@withLock call.respondApiError(
                        HttpStatusCode.Unauthorized,
                        "INVALID_TOKEN",
                        "This account cannot start a session",
                        metrics,
                    )
                }
                if (!webAuthnService.credentialBelongsToUser(credentialId, current.id)) {
                    return@withLock call.respondApiError(HttpStatusCode.NotFound, "PASSKEY_NOT_FOUND", "Passkey not found", metrics)
                }
                if (webAuthnService.getPasskeysForUser(current.id).none { it.credentialId == credentialId }) {
                    return@withLock call.respond(HttpStatusCode.NoContent)
                }
                if (!signInMethods.hasUsableMethod(current.id, excludingCredentialId = credentialId)) {
                    return@withLock call.respondApiError(
                        HttpStatusCode.Conflict,
                        "LAST_SIGNIN_FACTOR",
                        "Cannot delete the last sign-in factor",
                        metrics,
                    )
                }
                webAuthnService.deletePasskey(credentialId, current.id)
                // A removed passkey may be the one that approved an app, so every app loses access.
                current.did?.let { did -> oauthGrantRevoker?.revokeAllForSubject(did) }
                call.respond(HttpStatusCode.NoContent)
            }
        } catch (e: Exception) {
            Napier.e("Failed to delete passkey", e)
            call.respondForRequestException(e, "Failed to delete passkey", metrics)
        }
    }
}
