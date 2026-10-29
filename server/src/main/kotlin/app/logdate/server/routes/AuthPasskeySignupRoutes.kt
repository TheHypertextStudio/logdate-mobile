@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.audit.AuditCategory
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountLinkEvent
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.SessionType
import app.logdate.server.auth.TemporarySession
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
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val METRIC_AUTH_SIGNUP_PASSKEY_BEGIN = "auth.signup.passkey.begin"
private const val METRIC_AUTH_SIGNUP_PASSKEY_COMPLETE = "auth.signup.passkey.complete"
private const val GOOGLE_AUTH_NOT_CONFIGURED_CODE = "GOOGLE_AUTH_NOT_CONFIGURED"
private const val GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE = "Google sign-in is not configured on this server"

internal fun Route.registerSignupPasskeyBeginRoute(
    accountRepository: AccountRepository,
    sessionManager: SessionManager,
    webAuthnService: WebAuthnPasskeyService,
    metrics: AuthMetricsRegistry,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    post("/begin", AuthDocs.beginPasskeySignup) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (!call.enforceRateLimit(rateLimiter, "signup.passkey.begin", SIGNUP_RATE_LIMIT, metrics)) {
                return@post
            }
            val request = call.receive<SignupPasskeyBeginRequest>()
            val requestedOwnerId = validatePasskeySignup(call, request, accountRepository, metrics) ?: return@post

            val registrationOptions =
                webAuthnService.generateRegistrationOptions(
                    userId = requestedOwnerId,
                    username = request.username,
                    displayName = request.displayName,
                )

            val session =
                sessionManager.createAccountCreationSession(
                    temporaryUserId = requestedOwnerId,
                    username = request.username,
                    displayName = request.displayName,
                    challenge = registrationOptions.challenge,
                    deviceInfo = null,
                    bio = request.bio,
                    originalBio = request.originalBio,
                )

            call.respond(
                HttpStatusCode.OK,
                SignupPasskeyBeginResponse(
                    success = true,
                    data = SignupPasskeyBeginData(session.id, registrationOptions),
                ),
            )
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to begin passkey signup", e)
            call.respondForRequestException(e, "Failed to begin passkey signup", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNUP_PASSKEY_BEGIN, System.currentTimeMillis() - start, success)
        }
    }
}

internal fun Route.registerSignupPasskeyCompleteRoute(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    sessionManager: SessionManager,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    newAccountStorageMode: AccountStorageMode,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    post("/complete", AuthDocs.completePasskeySignup) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (!call.enforceRateLimit(rateLimiter, "signup.passkey.complete", SIGNUP_RATE_LIMIT, metrics)) {
                return@post
            }
            val request = call.receive<SignupPasskeyCompleteRequest>()
            val session = resolvePasskeySignupSession(call, sessionManager, request.sessionToken, metrics) ?: return@post

            val verified =
                verifyAuthPasskeyRegistration(
                    call,
                    session.temporaryUserId,
                    session.challenge,
                    request.credential,
                    request.nickname,
                    webAuthnService,
                    metrics,
                ) ?: return@post

            val bindingClaims = validateSignupEmailBinding(call, request, googleIdTokenVerifier, identityRepository, metrics)
            if (request.emailBinding != null && bindingClaims == null) return@post

            val account =
                createPasskeySignupAccount(
                    call,
                    session,
                    bindingClaims,
                    newAccountStorageMode,
                    accountRepository,
                    webAuthnService,
                    verified,
                    metrics,
                )
                    ?: return@post

            sessionManager.markSessionUsed(request.sessionToken)

            recordSignupIdentities(call, account, verified, bindingClaims, identityRepository)

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
            call.recordAuthSuccess(AuditCategory.AUTH_SIGNUP_PASSKEY_SUCCESS, account.id)
            call.respond(HttpStatusCode.Created, response)
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to complete passkey signup", e)
            call.respondForRequestException(e, "Failed to complete passkey signup", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNUP_PASSKEY_COMPLETE, System.currentTimeMillis() - start, success)
        }
    }
}

private suspend fun validatePasskeySignup(
    call: ApplicationCall,
    request: SignupPasskeyBeginRequest,
    accountRepository: AccountRepository,
    metrics: AuthMetricsRegistry,
): Uuid? {
    val usernameError = validateUsername(request.username)
    if (usernameError != null) {
        return call.rejectAuthRequest(HttpStatusCode.BadRequest, "VALIDATION_ERROR", usernameError, metrics)
    }
    val displayNameError = validateDisplayName(request.displayName)
    if (displayNameError != null) {
        return call.rejectAuthRequest(HttpStatusCode.BadRequest, "VALIDATION_ERROR", displayNameError, metrics)
    }
    // The owner check comes first: a device that already has an account here
    // (someone moving back to a server they used before) must be told to sign
    // in, not that their own username is taken.
    val requestedOwnerId =
        call.parseRequiredCanonicalOwnerId(request.requestedOwnerId, metrics)
            ?: return null
    if (accountRepository.findById(requestedOwnerId) != null) {
        return call.rejectAuthRequest(
            HttpStatusCode.Conflict,
            "CANONICAL_OWNER_ID_TAKEN",
            "This device identity is already associated with an account",
            metrics,
        )
    }
    if (accountRepository.usernameExists(request.username)) {
        return call.rejectAuthRequest(
            HttpStatusCode.Conflict,
            "USERNAME_TAKEN",
            "Username is already taken",
            metrics,
        )
    }
    return requestedOwnerId
}

private suspend fun validateSignupEmailBinding(
    call: ApplicationCall,
    request: SignupPasskeyCompleteRequest,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    identityRepository: AccountIdentityRepository,
    metrics: AuthMetricsRegistry,
): GoogleIdTokenClaims? {
    if (
        request.emailBinding?.source == EMAIL_BINDING_SOURCE_GOOGLE &&
        !googleIdTokenVerifier.isConfigured()
    ) {
        return call.rejectAuthRequest(
            HttpStatusCode.ServiceUnavailable,
            GOOGLE_AUTH_NOT_CONFIGURED_CODE,
            GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE,
            metrics,
        )
    }

    val bindingClaims = resolveEmailBinding(request.emailBinding, googleIdTokenVerifier)
    if (request.emailBinding != null && bindingClaims == null) {
        return call.rejectAuthRequest(
            HttpStatusCode.BadRequest,
            "EMAIL_BINDING_INVALID",
            "Email binding could not be verified",
            metrics,
        )
    }

    if (bindingClaims != null) {
        val existingGoogleIdentity =
            identityRepository.findByProviderSubject(IdentityProvider.GOOGLE, bindingClaims.subject)
        if (existingGoogleIdentity != null) {
            return call.rejectAuthRequest(
                HttpStatusCode.Conflict,
                "ACCOUNT_LINK_CONFLICT",
                "Google identity is already linked to another account",
                metrics,
            )
        }
    }
    return bindingClaims
}

private suspend fun createPasskeySignupAccount(
    call: ApplicationCall,
    session: TemporarySession,
    bindingClaims: GoogleIdTokenClaims?,
    newAccountStorageMode: AccountStorageMode,
    accountRepository: AccountRepository,
    webAuthnService: WebAuthnPasskeyService,
    verified: WebAuthnPasskeyService.VerifiedRegistration,
    metrics: AuthMetricsRegistry,
): Account? {
    val now = Clock.System.now()
    // Keep account ID aligned with the WebAuthn user ID used during signup begin.
    // This ensures passkey ownership resolves to the created account on signin.
    val accountId = session.temporaryUserId
    val initialAccount =
        Account(
            id = accountId,
            username = session.username,
            displayName = session.displayName,
            bio = session.bio,
            originalBio = session.originalBio ?: session.bio,
            createdAt = now,
            lastSignInAt = now,
            isActive = true,
            email = bindingClaims?.email?.lowercase(),
            emailVerified = bindingClaims?.emailVerified ?: false,
            storageMode = newAccountStorageMode,
        )

    // Account row first so the passkey FK to accounts(id) holds. Passkey
    // store is a single repository call right after — if it fails we delete
    // the account, but the failure window is two adjacent DB writes rather
    // than the verify+save+store surface the previous flow had.
    if (!accountRepository.createOnly(initialAccount)) {
        return call.rejectAuthRequest(
            HttpStatusCode.Conflict,
            "CANONICAL_OWNER_ID_TAKEN",
            "This device identity is already associated with an account",
            metrics,
        )
    }
    val account = initialAccount
    val passkeyStored = webAuthnService.storeVerifiedPasskey(account.id, verified)
    if (!passkeyStored) {
        runCatching { accountRepository.deleteAccount(account.id) }
            .onFailure { Napier.w("Failed to roll back orphan account ${account.id}", it) }
        return call.rejectAuthRequest(
            HttpStatusCode.InternalServerError,
            "PASSKEY_STORE_FAILED",
            "Failed to store passkey",
            metrics,
        )
    }
    return account
}

private suspend fun recordSignupIdentities(
    call: ApplicationCall,
    account: Account,
    verified: WebAuthnPasskeyService.VerifiedRegistration,
    bindingClaims: GoogleIdTokenClaims?,
    identityRepository: AccountIdentityRepository,
) {
    val passkeySubject = verified.credentialId
    identityRepository.save(
        AccountIdentity(
            id = Uuid.random(),
            accountId = account.id,
            provider = IdentityProvider.PASSKEY,
            providerSubject = passkeySubject,
            email = account.email,
            emailVerified = account.emailVerified,
            createdAt = account.createdAt,
        ),
    )

    if (bindingClaims != null) {
        val googleIdentity =
            identityRepository.save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = account.id,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = bindingClaims.subject,
                    email = bindingClaims.email.lowercase(),
                    emailVerified = bindingClaims.emailVerified,
                    createdAt = account.createdAt,
                    lastSignInAt = account.createdAt,
                ),
            )
        identityRepository.saveLinkEvent(
            AccountLinkEvent(
                id = Uuid.random(),
                accountId = account.id,
                provider = IdentityProvider.GOOGLE,
                providerSubject = googleIdentity.providerSubject,
                reason = "passkey_signup_google_binding",
                ipHash = call.hashRemoteIp(),
                userAgentHash = call.hashUserAgent(),
                createdAt = account.createdAt,
            ),
        )
    }
}

private suspend fun resolvePasskeySignupSession(
    call: ApplicationCall,
    sessionManager: SessionManager,
    sessionToken: String,
    metrics: AuthMetricsRegistry,
): TemporarySession? {
    val session = sessionManager.validateSession(sessionToken, SessionType.ACCOUNT_CREATION)
    if (session == null) {
        return call.rejectAuthRequest(
            HttpStatusCode.Unauthorized,
            "INVALID_SESSION_TOKEN",
            "Session token is invalid or expired",
            metrics,
        )
    }
    return session
}
