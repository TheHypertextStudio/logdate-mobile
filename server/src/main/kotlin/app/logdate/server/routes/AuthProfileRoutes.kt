@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AccountSessionUnavailableException
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.ensureLogDateSessionIdentity
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.docs.AccountDocs
import app.logdate.shared.model.AccountInfoResponse
import app.logdate.shared.model.UpdateAccountProfileRequest
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

internal fun Route.registerCurrentAccountRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    accessTokenVerifier: AccessTokenVerifier,
) {
    get("/me", AccountDocs.getCurrentAccount) {
        try {
            val (account, claims) =
                resolveAuthenticatedSession(call, accountRepository, accessTokenVerifier, metrics)
                    ?: return@get
            val response =
                issueAuthResponse(
                    account = account,
                    accountRepository = accountRepository,
                    identityRepository = identityRepository,
                    webAuthnService = webAuthnService,
                    atprotoIdentityService = atprotoIdentityService,
                    tokenService = tokenService,
                    deviceSessionId = claims.sessionId,
                )
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            Napier.e("Failed to get current account", e)
            call.respondForRequestException(e, "Failed to get current account", metrics)
        }
    }
}

internal fun Route.registerUpdateProfileRoute(
    accountRepository: AccountRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    put("/me", AccountDocs.updateProfile) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@put
            val request = call.receive<UpdateAccountProfileRequest>()
            val validationError = profileUpdateError(request, account, accountRepository)
            if (validationError != null) {
                return@put call.respondApiError(validationError.status, validationError.code, validationError.message, metrics)
            }

            val saved =
                accountRepository.save(
                    account.copy(
                        username = request.username ?: account.username,
                        displayName = request.displayName ?: account.displayName,
                        bio = request.bio ?: account.bio,
                        originalBio = request.originalBio ?: account.originalBio,
                    ),
                )
            val ensuredAccount =
                ensureLogDateSessionIdentity(saved, atprotoIdentityService) ?: throw AccountSessionUnavailableException()

            call.respond(
                HttpStatusCode.OK,
                AccountInfoResponse(
                    success = true,
                    data =
                        app.logdate.shared.model.LogDateAccount(
                            id = ensuredAccount.id,
                            username = ensuredAccount.username,
                            displayName = ensuredAccount.displayName,
                            did = ensuredAccount.did,
                            handle = ensuredAccount.handle,
                            bio = ensuredAccount.bio,
                            originalBio = ensuredAccount.originalBio,
                            passkeyCredentialIds = webAuthnService.getPasskeysForUser(ensuredAccount.id).map { it.credentialId },
                            createdAt = ensuredAccount.createdAt,
                            updatedAt = ensuredAccount.lastSignInAt ?: ensuredAccount.createdAt,
                            email = ensuredAccount.email,
                            emailVerified = ensuredAccount.emailVerified,
                            emailVerifiedAt = ensuredAccount.emailVerifiedAt,
                            storageMode = ensuredAccount.storageMode,
                            identityKind = ensuredAccount.identityKind,
                        ),
                ),
            )
        } catch (e: Exception) {
            Napier.e("Failed to update profile", e)
            call.respondForRequestException(e, "Failed to update profile", metrics)
        }
    }
}

internal fun Route.registerDeleteAccountRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    accountDeletionService: AccountDeletionService?,
) {
    delete("/me", AccountDocs.deleteAccount) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@delete
            val deletionService =
                accountDeletionService ?: return@delete call.respondApiError(
                    HttpStatusCode.ServiceUnavailable,
                    "DELETION_UNAVAILABLE",
                    "Account deletion is not configured on this server",
                    metrics,
                )

            val summary = deletionService.deleteAccount(account.id)
            if (!summary.accountRowDeleted) {
                return@delete call.respondApiError(
                    HttpStatusCode.InternalServerError,
                    "DELETION_FAILED",
                    "Account could not be deleted",
                    metrics,
                )
            }
            Napier.i(
                "Account ${account.id} deleted: media=${summary.mediaBlobsDeleted}, " +
                    "backup=${summary.backupBlobsDeleted}",
            )
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            Napier.e("Failed to delete account", e)
            call.respondForRequestException(e, "Failed to delete account", metrics)
        }
    }
}

internal fun Route.registerListIdentitiesRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    get("/me/identities", AccountDocs.listIdentities) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@get
            val identities = identityRepository.findByAccountId(account.id)
            val payload =
                identities.map {
                    IdentityView(
                        provider = it.provider.name.lowercase(),
                        providerSubject = it.providerSubject,
                        email = it.email,
                        emailVerified = it.emailVerified,
                        createdAt = it.createdAt.toString(),
                        lastSignInAt = it.lastSignInAt?.toString(),
                    )
                }
            call.respond(HttpStatusCode.OK, IdentityListResponse(true, payload))
        } catch (e: Exception) {
            Napier.e("Failed to get identities", e)
            call.respondForRequestException(e, "Failed to get identities", metrics)
        }
    }
}

private data class ProfileUpdateError(
    val status: HttpStatusCode,
    val code: String,
    val message: String,
)

private suspend fun profileUpdateError(
    request: UpdateAccountProfileRequest,
    account: app.logdate.server.auth.Account,
    accountRepository: AccountRepository,
): ProfileUpdateError? {
    if (request.displayName == null && request.username == null && request.bio == null && request.originalBio == null) {
        return ProfileUpdateError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "At least one field must be provided")
    }
    request.username?.let { username ->
        validateUsername(username)?.let { error ->
            return ProfileUpdateError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", error)
        }
        if (!username.equals(account.username, ignoreCase = false) && accountRepository.usernameExists(username)) {
            return ProfileUpdateError(HttpStatusCode.Conflict, "USERNAME_TAKEN", "Username is already taken")
        }
    }
    request.displayName?.let { displayName ->
        validateDisplayName(displayName)?.let { error ->
            return ProfileUpdateError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", error)
        }
    }
    return null
}
