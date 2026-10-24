package app.logdate.server.routes

import app.logdate.server.audit.AuditCategory
import app.logdate.server.audit.AuditKey
import app.logdate.server.audit.formatAuditLog
import app.logdate.server.auth.AccessCheck
import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountLinkEvent
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.EmailVerificationService
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.SessionType
import app.logdate.server.auth.TokenScope
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.bearerToken
import app.logdate.server.clientAddress
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.devices.DeviceSessionRefresh
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.entitlements.EntitlementStatus
import app.logdate.server.entitlements.EntitlementTier
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.HandleLabels
import app.logdate.server.oauth.OAuthGrantRevoker
import app.logdate.server.passkeys.RestoreCredentialService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.routes.docs.AccountDocs
import app.logdate.server.routes.docs.AuthDocs
import app.logdate.server.routes.docs.AuthSessionDocs
import app.logdate.shared.model.AccountInfoResponse
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.BeginEmailVerificationResponse
import app.logdate.shared.model.CompleteEmailVerificationRequest
import app.logdate.shared.model.EmailVerificationConflictResponse
import app.logdate.shared.model.EmailVerificationErrorResponse
import app.logdate.shared.model.EmailVerifiedResponse
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyRegistrationResponse
import app.logdate.shared.model.UpdateAccountProfileRequest
import app.logdate.shared.model.UsernameAvailabilityData
import app.logdate.shared.model.UsernameAvailabilityResponse
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val USERNAME_MIN_LENGTH = 3
private const val USERNAME_MAX_LENGTH = 50
private const val DISPLAY_NAME_MAX_LENGTH = 100
private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]+$")
private const val EMAIL_BINDING_SOURCE_GOOGLE = "google_id_token"
internal val SIGNUP_RATE_LIMIT = RateLimitPolicy(maxRequests = 5, windowSeconds = 60 * 60)
internal val SIGNIN_RATE_LIMIT = RateLimitPolicy(maxRequests = 10, windowSeconds = 60)
private const val METRIC_AUTH_SIGNUP_USERNAME_AVAILABLE = "auth.signup.username.available"
private const val METRIC_AUTH_SIGNUP_PASSKEY_BEGIN = "auth.signup.passkey.begin"
private const val METRIC_AUTH_SIGNUP_PASSKEY_COMPLETE = "auth.signup.passkey.complete"
private const val METRIC_AUTH_SIGNUP_GOOGLE = "auth.signup.google"
private const val METRIC_AUTH_SIGNIN_PASSKEY_BEGIN = "auth.signin.passkey.begin"
private const val METRIC_AUTH_SIGNIN_PASSKEY_COMPLETE = "auth.signin.passkey.complete"
private const val METRIC_AUTH_SIGNIN_GOOGLE = "auth.signin.google"
private const val METRIC_AUTH_TOKEN_REFRESH = "auth.token.refresh"
private const val METRIC_AUTH_METRICS = "auth.metrics"
private const val METRIC_AUTH_METRICS_PROM = "auth.metrics.prometheus"
private const val GOOGLE_AUTH_NOT_CONFIGURED_CODE = "GOOGLE_AUTH_NOT_CONFIGURED"
private const val GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE = "Google sign-in is not configured on this server"

@Serializable
data class EmailBindingRequest(
    val source: String,
    val value: String,
    val nonce: String? = null,
)

@Serializable
data class SignupPasskeyBeginRequest(
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
data class SignupPasskeyBeginData(
    val sessionToken: String,
    val registrationOptions: app.logdate.shared.model.PasskeyRegistrationOptions,
)

@Serializable
data class SignupPasskeyBeginResponse(
    val success: Boolean,
    val data: SignupPasskeyBeginData,
)

@Serializable
data class SignupPasskeyCompleteRequest(
    val sessionToken: String,
    val credential: PasskeyCredentialResponse,
    val emailBinding: EmailBindingRequest? = null,
    /** The name of the device the passkey was created on, shown in the passkey list. */
    val nickname: String? = null,
)

@Serializable
data class SigninPasskeyBeginRequest(
    val username: String? = null,
)

@Serializable
data class SigninPasskeyBeginData(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<PasskeyAllowCredentialDto>,
    val timeout: Long,
    val userVerification: String,
)

@Serializable
data class SigninPasskeyBeginResponse(
    val success: Boolean,
    val data: SigninPasskeyBeginData,
)

@Serializable
data class PasskeyAllowCredentialDto(
    @EncodeDefault
    val type: String = "public-key",
    val id: String,
    val transports: List<String> = emptyList(),
)

@Serializable
data class SigninPasskeyCompleteRequest(
    val credential: PasskeyAssertionResponse,
    val challenge: String,
)

@Serializable
data class GoogleAuthRequest(
    val idToken: String,
    val username: String? = null,
    val displayName: String? = null,
    val nonce: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
data class AuthAccountView(
    val id: String,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val bio: String? = null,
    val originalBio: String? = null,
    val email: String? = null,
    val emailVerified: Boolean,
    val emailVerifiedAt: String? = null,
    val linkedProviders: List<String>,
    val passkeyCredentialIds: List<String>,
    val createdAt: String,
    val updatedAt: String,
    /** How the server keeps this account's data; apps choose how to sync from it. */
    val storageMode: AccountStorageMode,
)

@Serializable
data class AuthResponseData(
    val account: AuthAccountView,
    val tokens: AccountTokens,
    /**
     * Sent after a passkey sign-in or sign-up on a server with recovery keys: single-use proof, for
     * five minutes, that the person just confirmed with a passkey. The app spends it to register the
     * account's first recovery key without a second prompt.
     */
    val stepUpGrant: String? = null,
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val data: AuthResponseData,
)

@Serializable
data class RefreshTokenRequestV1(
    val refreshToken: String,
)

@Serializable
data class LogoutRequestV1(
    val refreshToken: String,
)

@Serializable
data class RefreshTokenDataV1(
    val accessToken: String,
    /** A new refresh token, sent only when a linked device's session was just renewed. Store it. */
    val refreshToken: String? = null,
)

@Serializable
data class RefreshTokenResponseV1(
    val success: Boolean,
    val data: RefreshTokenDataV1,
)

@Serializable
data class IdentityView(
    val provider: String,
    val providerSubject: String,
    val email: String? = null,
    val emailVerified: Boolean,
    val createdAt: String,
    val lastSignInAt: String? = null,
)

@Serializable
data class IdentityListResponse(
    val success: Boolean,
    val data: List<IdentityView>,
)

@Serializable
data class AuthMetricsResponse(
    val success: Boolean,
    val data: app.logdate.server.auth.AuthMetricsSnapshot,
)

@Serializable
data class RestoreRegisterBeginResponse(
    val success: Boolean,
    val data: PasskeyRegistrationOptions,
)

@Serializable
data class RestoreRegisterCompleteRequest(
    val credentialJson: String,
    val challenge: String,
)

@Serializable
data class AddPasskeyCompleteRequest(
    val challenge: String,
    val credential: PasskeyCredentialResponse,
    /** The name of the device the passkey was created on, shown in the passkey list. */
    val nickname: String? = null,
)

@Serializable
data class PasskeyListResponse(
    val success: Boolean,
    val data: List<app.logdate.shared.model.PasskeyInfo>,
)

@Serializable
data class PasskeyResponse(
    val success: Boolean,
    val data: app.logdate.shared.model.PasskeyInfo,
)

private data class GoogleResolution(
    val account: Account,
    val identity: AccountIdentity,
)

@OptIn(ExperimentalUuidApi::class)
fun Route.authV1Routes(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    sessionManager: SessionManager,
    webAuthnService: WebAuthnPasskeyService,
    restoreCredentialService: RestoreCredentialService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    refreshTokenRevocationRepository: RefreshTokenRevocationRepository,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    accountDeletionService: AccountDeletionService? = null,
    entitlementService: EntitlementService? = null,
    emailVerificationService: EmailVerificationService? = null,
    deviceLinkService: DeviceLinkService? = null,
    accessTokenVerifier: AccessTokenVerifier = AccessTokenVerifier(tokenService, DeviceSessionGuard.Disabled),
    oauthGrantRevoker: OAuthGrantRevoker? = null,
    newAccountStorageMode: AccountStorageMode = AccountStorageMode.LEGACY,
) {
    val rateLimiter = InMemoryAuthRateLimiter()

    route("/auth") {
        route("/signup") {
            get("/username/{username}/available", AuthDocs.checkUsernameAvailability) {
                val start = System.currentTimeMillis()
                var success = false
                try {
                    val username = call.parameters["username"]?.trim().orEmpty()
                    val validationError = validateUsername(username)
                    if (validationError != null) {
                        return@get call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", validationError, metrics)
                    }
                    val isAvailable = !accountRepository.usernameExists(username)
                    call.respond(
                        HttpStatusCode.OK,
                        UsernameAvailabilityResponse(
                            success = true,
                            data = UsernameAvailabilityData(username = username, available = isAvailable),
                        ),
                    )
                    success = true
                } catch (e: Exception) {
                    Napier.e("Failed to check username availability", e)
                    call.respondForRequestException(e, "Failed to check username availability", metrics)
                } finally {
                    metrics.recordOperation(METRIC_AUTH_SIGNUP_USERNAME_AVAILABLE, System.currentTimeMillis() - start, success)
                }
            }

            route("/passkey") {
                post("/begin", AuthDocs.beginPasskeySignup) {
                    val start = System.currentTimeMillis()
                    var success = false
                    try {
                        if (!call.enforceRateLimit(rateLimiter, "signup.passkey.begin", SIGNUP_RATE_LIMIT, metrics)) {
                            return@post
                        }
                        val request = call.receive<SignupPasskeyBeginRequest>()
                        val usernameError = validateUsername(request.username)
                        if (usernameError != null) {
                            return@post call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", usernameError, metrics)
                        }
                        val displayNameError = validateDisplayName(request.displayName)
                        if (displayNameError != null) {
                            return@post call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", displayNameError, metrics)
                        }
                        // The owner check comes first: a device that already has an account here
                        // (someone moving back to a server they used before) must be told to sign
                        // in, not that their own username is taken.
                        val requestedOwnerId =
                            call.parseRequiredCanonicalOwnerId(request.requestedOwnerId, metrics)
                                ?: return@post
                        if (accountRepository.findById(requestedOwnerId) != null) {
                            return@post call.respondApiError(
                                HttpStatusCode.Conflict,
                                "CANONICAL_OWNER_ID_TAKEN",
                                "This device identity is already associated with an account",
                                metrics,
                            )
                        }
                        if (accountRepository.usernameExists(request.username)) {
                            return@post call.respondApiError(
                                HttpStatusCode.Conflict,
                                "USERNAME_TAKEN",
                                "Username is already taken",
                                metrics,
                            )
                        }

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

                post("/complete", AuthDocs.completePasskeySignup) {
                    val start = System.currentTimeMillis()
                    var success = false
                    try {
                        if (!call.enforceRateLimit(rateLimiter, "signup.passkey.complete", SIGNUP_RATE_LIMIT, metrics)) {
                            return@post
                        }
                        val request = call.receive<SignupPasskeyCompleteRequest>()
                        val session = sessionManager.validateSession(request.sessionToken, SessionType.ACCOUNT_CREATION)
                        if (session == null) {
                            return@post call.respondApiError(
                                HttpStatusCode.Unauthorized,
                                "INVALID_SESSION_TOKEN",
                                "Session token is invalid or expired",
                                metrics,
                            )
                        }

                        // Verify the passkey cryptographically *before* writing anything. The
                        // verifier no longer touches the database, so a failed verify costs
                        // nothing and we never have to roll back an orphan account row.
                        val verifiedOutcome =
                            webAuthnService.verifyRegistrationOnly(
                                userId = session.temporaryUserId,
                                challenge = session.challenge,
                                registrationResponse = request.credential.toPasskeyRegistrationResponse(),
                            )
                        val verified =
                            when (verifiedOutcome) {
                                is WebAuthnPasskeyService.VerificationOutcome.Failure -> {
                                    return@post call.respondApiError(
                                        HttpStatusCode.BadRequest,
                                        "PASSKEY_VERIFICATION_FAILED",
                                        verifiedOutcome.error,
                                        metrics,
                                    )
                                }

                                is WebAuthnPasskeyService.VerificationOutcome.Success -> {
                                    verifiedOutcome.data.withNickname(request.nickname)
                                }
                            }

                        if (
                            request.emailBinding?.source == EMAIL_BINDING_SOURCE_GOOGLE &&
                            !googleIdTokenVerifier.isConfigured()
                        ) {
                            return@post call.respondApiError(
                                HttpStatusCode.ServiceUnavailable,
                                GOOGLE_AUTH_NOT_CONFIGURED_CODE,
                                GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE,
                                metrics,
                            )
                        }

                        val bindingClaims = resolveEmailBinding(request.emailBinding, googleIdTokenVerifier)
                        if (request.emailBinding != null && bindingClaims == null) {
                            return@post call.respondApiError(
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
                                return@post call.respondApiError(
                                    HttpStatusCode.Conflict,
                                    "ACCOUNT_LINK_CONFLICT",
                                    "Google identity is already linked to another account",
                                    metrics,
                                )
                            }
                        }

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
                            return@post call.respondApiError(
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
                            return@post call.respondApiError(
                                HttpStatusCode.InternalServerError,
                                "PASSKEY_STORE_FAILED",
                                "Failed to store passkey",
                                metrics,
                            )
                        }
                        sessionManager.markSessionUsed(request.sessionToken)

                        val passkeySubject = verified.credentialId
                        identityRepository.save(
                            AccountIdentity(
                                id = Uuid.random(),
                                accountId = account.id,
                                provider = IdentityProvider.PASSKEY,
                                providerSubject = passkeySubject,
                                email = account.email,
                                emailVerified = account.emailVerified,
                                createdAt = now,
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
                                        createdAt = now,
                                        lastSignInAt = now,
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
                                    createdAt = now,
                                ),
                            )
                        }

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
                        Napier.i(
                            formatAuditLog(
                                AuditCategory.AUTH_SIGNUP_PASSKEY_SUCCESS,
                                mapOf(
                                    AuditKey.ACCOUNT_ID to account.id.toString(),
                                    AuditKey.IP_HASH to call.hashRemoteIp(),
                                    AuditKey.USER_AGENT_HASH to call.hashUserAgent(),
                                ),
                            ),
                        )
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

            post("/google", AuthDocs.signupWithGoogle) {
                val start = System.currentTimeMillis()
                var success = false
                try {
                    if (!call.enforceRateLimit(rateLimiter, "signup.google", SIGNUP_RATE_LIMIT, metrics)) {
                        return@post
                    }
                    if (!googleIdTokenVerifier.isConfigured()) {
                        return@post call.respondApiError(
                            HttpStatusCode.ServiceUnavailable,
                            GOOGLE_AUTH_NOT_CONFIGURED_CODE,
                            GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE,
                            metrics,
                        )
                    }
                    val request = call.receive<GoogleAuthRequest>()
                    val claims = googleIdTokenVerifier.verify(request.idToken, request.nonce)
                    if (claims == null) {
                        return@post call.respondApiError(
                            HttpStatusCode.Unauthorized,
                            "GOOGLE_TOKEN_INVALID",
                            "Google token is invalid",
                            metrics,
                        )
                    }
                    if (!claims.emailVerified) {
                        return@post call.respondApiError(
                            HttpStatusCode.BadRequest,
                            "GOOGLE_EMAIL_UNVERIFIED",
                            "Google account email must be verified",
                            metrics,
                        )
                    }

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
                    Napier.i(
                        formatAuditLog(
                            AuditCategory.AUTH_SIGNUP_GOOGLE_SUCCESS,
                            mapOf(
                                AuditKey.ACCOUNT_ID to resolution.account.id.toString(),
                                AuditKey.IP_HASH to call.hashRemoteIp(),
                                AuditKey.USER_AGENT_HASH to call.hashUserAgent(),
                            ),
                        ),
                    )
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

        route("/signin") {
            route("/passkey") {
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
                        val existingPasskeyIdentity =
                            identityRepository.findByProviderSubject(IdentityProvider.PASSKEY, passkeySubject)
                        if (existingPasskeyIdentity == null) {
                            identityRepository.save(
                                AccountIdentity(
                                    id = Uuid.random(),
                                    accountId = account.id,
                                    provider = IdentityProvider.PASSKEY,
                                    providerSubject = passkeySubject,
                                    email = account.email,
                                    emailVerified = account.emailVerified,
                                    createdAt = Clock.System.now(),
                                    lastSignInAt = Clock.System.now(),
                                ),
                            )
                        } else {
                            identityRepository.touchLastSignIn(existingPasskeyIdentity.id)
                        }

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
                        Napier.i(
                            formatAuditLog(
                                AuditCategory.AUTH_SIGNIN_PASSKEY_SUCCESS,
                                mapOf(
                                    AuditKey.ACCOUNT_ID to account.id.toString(),
                                    AuditKey.CREDENTIAL_ID_HASH to passkeySubject.sha256(),
                                    AuditKey.IP_HASH to call.hashRemoteIp(),
                                    AuditKey.USER_AGENT_HASH to call.hashUserAgent(),
                                ),
                            ),
                        )
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

            post("/google", AuthDocs.signinWithGoogle) {
                val start = System.currentTimeMillis()
                var success = false
                try {
                    if (!call.enforceRateLimit(rateLimiter, "signin.google", SIGNIN_RATE_LIMIT, metrics)) {
                        return@post
                    }
                    if (!googleIdTokenVerifier.isConfigured()) {
                        return@post call.respondApiError(
                            HttpStatusCode.ServiceUnavailable,
                            GOOGLE_AUTH_NOT_CONFIGURED_CODE,
                            GOOGLE_AUTH_NOT_CONFIGURED_MESSAGE,
                            metrics,
                        )
                    }
                    val request = call.receive<GoogleAuthRequest>()
                    val claims = googleIdTokenVerifier.verify(request.idToken, request.nonce)
                    if (claims == null) {
                        return@post call.respondApiError(
                            HttpStatusCode.Unauthorized,
                            "GOOGLE_TOKEN_INVALID",
                            "Google token is invalid",
                            metrics,
                        )
                    }
                    if (!claims.emailVerified) {
                        return@post call.respondApiError(
                            HttpStatusCode.BadRequest,
                            "GOOGLE_EMAIL_UNVERIFIED",
                            "Google account email must be verified",
                            metrics,
                        )
                    }

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
                    Napier.i(
                        formatAuditLog(
                            AuditCategory.AUTH_SIGNIN_GOOGLE_SUCCESS,
                            mapOf(
                                AuditKey.ACCOUNT_ID to resolution.account.id.toString(),
                                AuditKey.IP_HASH to call.hashRemoteIp(),
                                AuditKey.USER_AGENT_HASH to call.hashUserAgent(),
                            ),
                        ),
                    )
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

        route("/restore") {
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
                    val existing = identityRepository.findByProviderSubject(IdentityProvider.PASSKEY, credentialSubject)
                    if (existing == null) {
                        identityRepository.save(
                            AccountIdentity(
                                id = Uuid.random(),
                                accountId = account.id,
                                provider = IdentityProvider.PASSKEY,
                                providerSubject = credentialSubject,
                                email = account.email,
                                emailVerified = account.emailVerified,
                                createdAt = Clock.System.now(),
                                lastSignInAt = Clock.System.now(),
                            ),
                        )
                    } else {
                        identityRepository.touchLastSignIn(existing.id)
                    }
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

        route("/token") {
            post("/refresh", AuthSessionDocs.refreshAccessToken) {
                val start = System.currentTimeMillis()
                var success = false
                try {
                    val request = call.receive<RefreshTokenRequestV1>()
                    if (request.refreshToken.isBlank()) {
                        return@post call.respondApiError(
                            HttpStatusCode.Unauthorized,
                            "INVALID_REFRESH_TOKEN",
                            "Refresh token is required",
                            metrics,
                        )
                    }
                    if (refreshTokenRevocationRepository.isRevoked(request.refreshToken)) {
                        return@post call.respondApiError(
                            HttpStatusCode.Unauthorized,
                            "REFRESH_TOKEN_REVOKED",
                            "Refresh token has been revoked",
                            metrics,
                        )
                    }
                    val accountId = tokenService.validateRefreshToken(request.refreshToken)
                    if (accountId == null) {
                        success = call.respondDeviceRefresh(request.refreshToken, tokenService, deviceLinkService, metrics)
                        return@post
                    }
                    val did =
                        runCatching { Uuid.parse(accountId) }
                            .getOrNull()
                            ?.let { accountRepository.findById(it)?.did }
                    val accessToken = tokenService.generateAccessToken(accountId, did)
                    call.respond(HttpStatusCode.OK, RefreshTokenResponseV1(true, RefreshTokenDataV1(accessToken)))
                    success = true
                } catch (e: Exception) {
                    Napier.e("Failed to refresh token", e)
                    call.respondForRequestException(e, "Failed to refresh token", metrics)
                } finally {
                    metrics.recordOperation(METRIC_AUTH_TOKEN_REFRESH, System.currentTimeMillis() - start, success)
                }
            }
        }

        post("/logout", AuthSessionDocs.logout) {
            try {
                val request = call.receive<LogoutRequestV1>()
                if (request.refreshToken.isBlank()) {
                    return@post call.respondApiError(
                        HttpStatusCode.BadRequest,
                        "INVALID_REFRESH_TOKEN",
                        "Refresh token is required",
                        metrics,
                    )
                }
                refreshTokenRevocationRepository.revoke(request.refreshToken)
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } catch (e: Exception) {
                Napier.e("Failed to log out", e)
                call.respondForRequestException(e, "Failed to log out", metrics)
            }
        }

        get("/metrics", { hidden = true }) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                if (resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) == null) {
                    return@get
                }
                call.respond(HttpStatusCode.OK, AuthMetricsResponse(success = true, data = metrics.snapshot()))
                success = true
            } catch (e: Exception) {
                Napier.e("Failed to render auth metrics", e)
                call.respondForRequestException(e, "Failed to render auth metrics", metrics)
            } finally {
                metrics.recordOperation(METRIC_AUTH_METRICS, System.currentTimeMillis() - start, success)
            }
        }

        get("/metrics/prometheus", { hidden = true }) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                if (resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) == null) {
                    return@get
                }
                call.respondText(metrics.snapshot().toPrometheus(), ContentType.Text.Plain)
                success = true
            } catch (e: Exception) {
                Napier.e("Failed to render auth metrics in Prometheus format", e)
                call.respondForRequestException(e, "Failed to render auth metrics", metrics)
            } finally {
                metrics.recordOperation(METRIC_AUTH_METRICS_PROM, System.currentTimeMillis() - start, success)
            }
        }

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

        put("/me", AccountDocs.updateProfile) {
            try {
                val account =
                    resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                        ?: return@put
                val request = call.receive<UpdateAccountProfileRequest>()
                if (request.displayName == null && request.username == null && request.bio == null && request.originalBio == null) {
                    return@put call.respondApiError(
                        HttpStatusCode.BadRequest,
                        "VALIDATION_ERROR",
                        "At least one field must be provided",
                        metrics,
                    )
                }

                request.username?.let {
                    val error = validateUsername(it)
                    if (error != null) {
                        return@put call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", error, metrics)
                    }
                    if (!it.equals(account.username, ignoreCase = false) && accountRepository.usernameExists(it)) {
                        return@put call.respondApiError(
                            HttpStatusCode.Conflict,
                            "USERNAME_TAKEN",
                            "Username is already taken",
                            metrics,
                        )
                    }
                }

                request.displayName?.let {
                    val error = validateDisplayName(it)
                    if (error != null) {
                        return@put call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", error, metrics)
                    }
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
                val ensuredAccount = atprotoIdentityService.ensureIdentity(saved)

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
                            ),
                    ),
                )
            } catch (e: Exception) {
                Napier.e("Failed to update profile", e)
                call.respondForRequestException(e, "Failed to update profile", metrics)
            }
        }

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
                        is WebAuthnPasskeyService.VerificationOutcome.Failure -> {
                            return@post call.respondApiError(
                                HttpStatusCode.BadRequest,
                                "PASSKEY_VERIFICATION_FAILED",
                                outcome.error,
                                metrics,
                            )
                        }

                        is WebAuthnPasskeyService.VerificationOutcome.Success -> {
                            outcome.data.withNickname(request.nickname)
                        }
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

                if (!webAuthnService.credentialBelongsToUser(credentialId, account.id)) {
                    return@delete call.respondApiError(HttpStatusCode.NotFound, "PASSKEY_NOT_FOUND", "Passkey not found", metrics)
                }
                val activePasskeys = webAuthnService.getPasskeysForUser(account.id)
                if (activePasskeys.none { it.credentialId == credentialId }) {
                    return@delete call.respond(HttpStatusCode.NoContent)
                }
                val hasOtherSignInProvider =
                    identityRepository
                        .findByAccountId(account.id)
                        .any { it.provider != IdentityProvider.PASSKEY }
                if (activePasskeys.size <= 1 && !hasOtherSignInProvider) {
                    return@delete call.respondApiError(
                        HttpStatusCode.Conflict,
                        "LAST_SIGNIN_FACTOR",
                        "Cannot delete the last sign-in factor",
                        metrics,
                    )
                }
                webAuthnService.deletePasskey(credentialId, account.id)
                // A removed passkey may be the one that approved an app, so every app loses access.
                account.did?.let { did -> oauthGrantRevoker?.revokeAllForSubject(did) }
                call.respond(HttpStatusCode.NoContent)
            } catch (e: Exception) {
                Napier.e("Failed to delete passkey", e)
                call.respondForRequestException(e, "Failed to delete passkey", metrics)
            }
        }

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

        post("/me/email/verify/begin", AccountDocs.beginEmailVerification) {
            try {
                val account =
                    resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                        ?: return@post
                val service =
                    emailVerificationService ?: return@post call.respondApiError(
                        HttpStatusCode.NotImplemented,
                        "EMAIL_VERIFICATION_UNAVAILABLE",
                        "Email verification is not configured on this server",
                        metrics,
                    )
                if (!isEmailVerificationFeatureEnabled(account.id, entitlementService)) {
                    return@post call.respondApiError(
                        HttpStatusCode.NotFound,
                        "FEATURE_DISABLED",
                        "Email verification is not enabled for this account",
                        metrics,
                    )
                }
                val challenge = service.begin(account.id)
                call.respond(
                    HttpStatusCode.OK,
                    BeginEmailVerificationResponse(
                        transactionId = challenge.transactionId.toString(),
                        nonce = challenge.nonce,
                        audience = EMAIL_VERIFICATION_AUDIENCE,
                    ),
                )
            } catch (e: Exception) {
                Napier.e("Failed to begin email verification", e)
                call.respondForRequestException(e, "Failed to begin email verification", metrics)
            }
        }

        post("/me/email/verify/complete", AccountDocs.completeEmailVerification) {
            try {
                val account =
                    resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                        ?: return@post
                val service =
                    emailVerificationService ?: return@post call.respondApiError(
                        HttpStatusCode.NotImplemented,
                        "EMAIL_VERIFICATION_UNAVAILABLE",
                        "Email verification is not configured on this server",
                        metrics,
                    )
                if (!isEmailVerificationFeatureEnabled(account.id, entitlementService)) {
                    return@post call.respondApiError(
                        HttpStatusCode.NotFound,
                        "FEATURE_DISABLED",
                        "Email verification is not enabled for this account",
                        metrics,
                    )
                }
                val request = call.receive<CompleteEmailVerificationRequest>()
                val txId =
                    runCatching { Uuid.parse(request.transactionId) }.getOrNull()
                        ?: return@post call.respondApiError(
                            HttpStatusCode.BadRequest,
                            "INVALID_TRANSACTION_ID",
                            "Malformed transaction id",
                            metrics,
                        )
                when (val result = service.complete(account.id, txId, request.credentialJson)) {
                    is EmailVerificationService.Result.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            EmailVerifiedResponse(
                                email = result.email,
                                emailVerifiedAt = result.emailVerifiedAt,
                            ),
                        )
                    }

                    is EmailVerificationService.Result.Conflict -> {
                        call.respond(
                            HttpStatusCode.Conflict,
                            EmailVerificationConflictResponse(
                                message = "This email is already attached to another LogDate account.",
                            ),
                        )
                    }

                    is EmailVerificationService.Result.Failed -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            EmailVerificationErrorResponse(reason = result.reason),
                        )
                    }
                }
            } catch (e: Exception) {
                Napier.e("Failed to complete email verification", e)
                call.respondForRequestException(e, "Failed to complete email verification", metrics)
            }
        }

        get("/me/entitlement", AccountDocs.getEntitlement) {
            try {
                val account =
                    resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                        ?: return@get
                val service =
                    entitlementService ?: return@get call.respond(
                        HttpStatusCode.OK,
                        // When billing isn't wired we expose the unlimited-self-host shape directly
                        // rather than 404-ing — the UI needs a value to render.
                        EntitlementResponse(
                            planId = "self_host_unlimited",
                            tier = EntitlementTierWire.UNLIMITED,
                            status = EntitlementStatusWire.SELF_HOST,
                            storageBytesLimit = null,
                            backupCountLimit = null,
                            transcriptionSecondsPerMonthLimit = null,
                            features =
                                mapOf(
                                    "cloud_transcription_realtime" to true,
                                    "cloud_transcript_refinement" to true,
                                    "email_verification" to true,
                                ),
                        ),
                    )
                val resolved =
                    service.resolve(java.util.UUID.fromString(account.id.toString()))
                call.respond(
                    HttpStatusCode.OK,
                    EntitlementResponse(
                        planId = resolved.planId,
                        tier = resolved.tier.toWire(),
                        status = resolved.status.toWire(),
                        storageBytesLimit = resolved.limits.storageBytes,
                        backupCountLimit = resolved.limits.backupCount,
                        transcriptionSecondsPerMonthLimit = resolved.limits.transcriptionSecondsPerMonth,
                        features = resolved.features,
                    ),
                )
            } catch (e: Exception) {
                Napier.e("Failed to resolve entitlement", e)
                call.respondForRequestException(e, "Failed to resolve entitlement", metrics)
            }
        }

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
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun resolveGoogleAccount(
    claims: GoogleIdTokenClaims,
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    allowCreate: Boolean,
    requestedUsername: String?,
    requestedDisplayName: String?,
    requestedOwnerId: Uuid?,
    newAccountStorageMode: AccountStorageMode,
    call: ApplicationCall,
): GoogleResolution? {
    val normalizedEmail = claims.email.lowercase()
    val now = Clock.System.now()

    val googleIdentity = identityRepository.findByProviderSubject(IdentityProvider.GOOGLE, claims.subject)
    if (googleIdentity != null) {
        val account = accountRepository.findById(googleIdentity.accountId) ?: return null
        if (requestedOwnerId != null && account.id != requestedOwnerId) return null
        identityRepository.touchLastSignIn(googleIdentity.id)
        return GoogleResolution(account, googleIdentity)
    }

    val emailMatches = accountRepository.findByVerifiedEmail(normalizedEmail)
    if (emailMatches.size > 1) {
        Napier.w("Google linking conflict for verified email $normalizedEmail: ${emailMatches.size} accounts")
        return null
    }

    if (emailMatches.size == 1) {
        val matchedAccount = emailMatches.first()
        if (requestedOwnerId != null && matchedAccount.id != requestedOwnerId) return null
        val identity =
            identityRepository.save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = matchedAccount.id,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = claims.subject,
                    email = normalizedEmail,
                    emailVerified = true,
                    createdAt = now,
                    lastSignInAt = now,
                    metadataJson = "{}",
                ),
            )

        identityRepository.saveLinkEvent(
            AccountLinkEvent(
                id = Uuid.random(),
                accountId = matchedAccount.id,
                provider = IdentityProvider.GOOGLE,
                providerSubject = claims.subject,
                reason = "implicit_verified_email",
                ipHash = call.hashRemoteIp(),
                userAgentHash = call.hashUserAgent(),
                createdAt = now,
            ),
        )

        if (matchedAccount.email != normalizedEmail || !matchedAccount.emailVerified) {
            accountRepository.save(
                matchedAccount.copy(
                    email = normalizedEmail,
                    emailVerified = true,
                ),
            )
        }

        Napier.i(
            formatAuditLog(
                AuditCategory.AUTH_LINK_GOOGLE_IMPLICIT,
                mapOf(
                    AuditKey.ACCOUNT_ID to matchedAccount.id.toString(),
                    AuditKey.PROVIDER_SUBJECT_HASH to claims.subject.sha256(),
                    AuditKey.IP_HASH to call.hashRemoteIp(),
                    AuditKey.USER_AGENT_HASH to call.hashUserAgent(),
                ),
            ),
        )
        return GoogleResolution(matchedAccount.copy(email = normalizedEmail, emailVerified = true), identity)
    }

    if (!allowCreate) {
        return null
    }

    val ownerId = requestedOwnerId ?: return null

    val username =
        resolveUniqueUsername(
            requestedUsername = requestedUsername,
            fallbackEmail = normalizedEmail,
            accountRepository = accountRepository,
        ) ?: return null
    val displayName = requestedDisplayName?.takeIf { it.isNotBlank() } ?: claims.name?.takeIf { it.isNotBlank() } ?: username

    val account =
        Account(
            id = ownerId,
            username = username,
            displayName = displayName,
            email = normalizedEmail,
            emailVerified = true,
            createdAt = now,
            lastSignInAt = now,
            isActive = true,
            storageMode = newAccountStorageMode,
        )
    if (!accountRepository.createOnly(account)) return null

    val identity =
        try {
            identityRepository.save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = account.id,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = claims.subject,
                    email = normalizedEmail,
                    emailVerified = true,
                    createdAt = now,
                    lastSignInAt = now,
                ),
            )
        } catch (error: Exception) {
            runCatching { accountRepository.deleteAccount(account.id) }
                .onFailure { Napier.w("Failed to roll back Google account ${account.id}", it) }
            throw error
        }

    identityRepository.saveLinkEvent(
        AccountLinkEvent(
            id = Uuid.random(),
            accountId = account.id,
            provider = IdentityProvider.GOOGLE,
            providerSubject = claims.subject,
            reason = "google_signup",
            ipHash = call.hashRemoteIp(),
            userAgentHash = call.hashUserAgent(),
            createdAt = now,
        ),
    )

    return GoogleResolution(account, identity)
}

private suspend fun resolveUniqueUsername(
    requestedUsername: String?,
    fallbackEmail: String,
    accountRepository: AccountRepository,
): String? {
    val requested = requestedUsername?.trim().orEmpty()
    val candidate = if (requested.isNotBlank()) requested else fallbackEmail.substringBefore('@').ifBlank { "user" }
    val sanitized = candidate.replace(Regex("[^a-zA-Z0-9_]"), "_").take(USERNAME_MAX_LENGTH)
    if (validateUsername(sanitized) == null && !accountRepository.usernameExists(sanitized)) {
        return sanitized
    }

    for (i in 1..1000) {
        val withSuffix = "${sanitized.take((USERNAME_MAX_LENGTH - 5).coerceAtLeast(1))}_${1000 + i}"
        if (validateUsername(withSuffix) == null && !accountRepository.usernameExists(withSuffix)) {
            return withSuffix
        }
    }

    return null
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun issueAuthResponse(
    account: Account,
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    deviceSessionId: String? = null,
    passkeyConfirmed: Boolean = false,
): AuthResponse {
    val fresh = atprotoIdentityService.ensureIdentity(accountRepository.findById(account.id) ?: account)
    val identities = identityRepository.findByAccountId(fresh.id)
    val linkedProviders = identities.map { it.provider.name.lowercase() }.distinct().sorted()
    val passkeyCredentialIds = webAuthnService.getPasskeysForUser(fresh.id).map { it.credentialId }

    val accountId = fresh.id.toString()
    // A linked device stays on its own session and scope, so this call can never turn it into a full sign-in.
    val tokens =
        if (deviceSessionId == null) {
            AccountTokens(
                accessToken = tokenService.generateAccessToken(accountId, fresh.did),
                refreshToken = tokenService.generateRefreshToken(accountId, fresh.did),
            )
        } else {
            AccountTokens(
                accessToken = tokenService.generateDeviceAccessToken(accountId, fresh.did, deviceSessionId),
                refreshToken = tokenService.generateDeviceRefreshToken(accountId, fresh.did, deviceSessionId),
            )
        }

    return AuthResponse(
        success = true,
        data =
            AuthResponseData(
                account =
                    AuthAccountView(
                        id = fresh.id.toString(),
                        username = fresh.username,
                        displayName = fresh.displayName,
                        did = fresh.did,
                        handle = fresh.handle,
                        bio = fresh.bio,
                        originalBio = fresh.originalBio,
                        email = fresh.email,
                        emailVerified = fresh.emailVerified,
                        emailVerifiedAt = fresh.emailVerifiedAt?.toString(),
                        linkedProviders = linkedProviders,
                        passkeyCredentialIds = passkeyCredentialIds,
                        createdAt = fresh.createdAt.toString(),
                        updatedAt = (fresh.lastSignInAt ?: fresh.createdAt).toString(),
                        storageMode = fresh.storageMode,
                    ),
                tokens = tokens,
                stepUpGrant =
                    if (passkeyConfirmed && atprotoIdentityService.config.supportsRecoveryKeys) {
                        issueStepUpGrant(webAuthnService, fresh.id)
                    } else {
                        null
                    },
            ),
    )
}

/**
 * A step-up grant for [accountId], or `null` when one cannot be stored. The sign-in itself has
 * already succeeded, so it goes ahead without a grant; the app then asks for the passkey again
 * before registering a recovery key.
 */
@OptIn(ExperimentalUuidApi::class)
private fun issueStepUpGrant(
    webAuthnService: WebAuthnPasskeyService,
    accountId: Uuid,
): String? =
    try {
        webAuthnService.issueStepUpGrant(accountId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Napier.e("Could not issue a step-up grant after a passkey sign-in", error)
        null
    }

private suspend fun resolveEmailBinding(
    binding: EmailBindingRequest?,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
): GoogleIdTokenClaims? {
    if (binding == null) {
        return null
    }

    if (binding.source != EMAIL_BINDING_SOURCE_GOOGLE) {
        return null
    }

    val claims = googleIdTokenVerifier.verify(binding.value, binding.nonce)
    if (claims?.emailVerified != true) {
        return null
    }

    return claims
}

private fun validateUsername(username: String): String? {
    if (username.isBlank()) {
        return "Username is required"
    }

    if (username.length < USERNAME_MIN_LENGTH || username.length > USERNAME_MAX_LENGTH) {
        return "Username must be 3-50 characters long"
    }

    if (!USERNAME_REGEX.matches(username)) {
        return "Username can only contain letters, numbers, and underscores"
    }

    if (HandleLabels.isReservedUsername(username)) {
        return "Username is reserved"
    }

    return null
}

private fun validateDisplayName(displayName: String): String? {
    if (displayName.isBlank()) {
        return "Display name is required"
    }

    if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
        return "Display name must be 1-100 characters long"
    }

    return null
}

private fun EntitlementTier.toWire(): EntitlementTierWire =
    when (this) {
        EntitlementTier.FREE -> EntitlementTierWire.FREE
        EntitlementTier.STANDARD -> EntitlementTierWire.STANDARD
        EntitlementTier.PRO -> EntitlementTierWire.PRO
        EntitlementTier.UNLIMITED -> EntitlementTierWire.UNLIMITED
    }

private fun EntitlementStatus.toWire(): EntitlementStatusWire =
    when (this) {
        EntitlementStatus.ACTIVE -> EntitlementStatusWire.ACTIVE
        EntitlementStatus.PAST_DUE -> EntitlementStatusWire.PAST_DUE
        EntitlementStatus.GRACE -> EntitlementStatusWire.GRACE
        EntitlementStatus.CANCELLED -> EntitlementStatusWire.CANCELLED
        EntitlementStatus.SELF_HOST -> EntitlementStatusWire.SELF_HOST
    }

private class InMemoryAuthRateLimiter {
    private val requestsByKey = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(
        key: String,
        policy: RateLimitPolicy,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        val windowStart = nowEpochMillis - (policy.windowSeconds * 1000L)
        val bucket = requestsByKey.computeIfAbsent(key) { ArrayDeque() }

        synchronized(bucket) {
            while (bucket.isNotEmpty() && bucket.first() < windowStart) {
                bucket.removeFirst()
            }
            if (bucket.size >= policy.maxRequests) {
                return false
            }
            bucket.addLast(nowEpochMillis)
            return true
        }
    }
}

private fun PasskeyCredentialResponse.toPasskeyRegistrationResponse(): PasskeyRegistrationResponse =
    PasskeyRegistrationResponse(
        id = id,
        rawId = rawId,
        response =
            AuthenticatorAttestationResponse(
                clientDataJSON = response.clientDataJSON,
                attestationObject = response.attestationObject,
            ),
        type = type,
    )

private fun PasskeyAssertionResponse.toPasskeyAuthenticationResponse(): PasskeyAuthenticationResponse =
    PasskeyAuthenticationResponse(
        id = id,
        rawId = rawId,
        response =
            AuthenticatorAssertionResponse(
                clientDataJSON = response.clientDataJSON,
                authenticatorData = response.authenticatorData,
                signature = response.signature,
                userHandle = response.userHandle,
            ),
        type = type,
    )

@OptIn(ExperimentalUuidApi::class)
private suspend fun resolveAuthenticatedAccount(
    call: ApplicationCall,
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry?,
): Account? {
    val authHeader = call.request.header(HttpHeaders.Authorization)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required", metrics)
        return null
    }

    val token = authHeader.removePrefix("Bearer ").trim()
    if (token.isBlank()) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required", metrics)
        return null
    }

    val accountId = tokenService.validateAccessToken(token)
    if (accountId == null) {
        if (tokenService.decodeAccessToken(token)?.scope == TokenScope.SYNC) {
            call.respondApiError(
                HttpStatusCode.Forbidden,
                DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED,
                "A linked device cannot do this",
                metrics,
            )
            return null
        }
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Invalid or expired access token", metrics)
        return null
    }

    val account = accountRepository.findById(Uuid.parse(accountId))
    if (account == null) {
        call.respondApiError(HttpStatusCode.NotFound, "ACCOUNT_NOT_FOUND", "Account not found", metrics)
        return null
    }

    return account
}

/** Like [resolveAuthenticatedAccount], but also accepts a linked device while its session is active. */
@OptIn(ExperimentalUuidApi::class)
private suspend fun resolveAuthenticatedSession(
    call: ApplicationCall,
    accountRepository: AccountRepository,
    verifier: AccessTokenVerifier,
    metrics: AuthMetricsRegistry?,
): Pair<Account, AccessTokenClaims>? {
    val token = bearerToken(call.request.header(HttpHeaders.Authorization))
    if (token.isNullOrBlank()) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required", metrics)
        return null
    }
    val claims =
        when (val check = verifier.verify(token, allowSyncScope = true)) {
            is AccessCheck.Granted -> {
                check.claims
            }

            AccessCheck.SessionRevoked -> {
                call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
                    "This device was removed from the account",
                    metrics,
                )
                return null
            }

            AccessCheck.Missing, AccessCheck.Invalid, AccessCheck.ScopeDenied -> {
                call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Invalid or expired access token", metrics)
                return null
            }
        }
    val account = runCatching { Uuid.parse(claims.accountId) }.getOrNull()?.let { accountRepository.findById(it) }
    if (account == null) {
        call.respondApiError(HttpStatusCode.NotFound, "ACCOUNT_NOT_FOUND", "Account not found", metrics)
        return null
    }
    return account to claims
}

/** Refreshes a linked device's tokens, or answers why it cannot. Returns whether it answered 200. */
private suspend fun ApplicationCall.respondDeviceRefresh(
    refreshToken: String,
    tokenService: TokenService,
    deviceLinkService: DeviceLinkService?,
    metrics: AuthMetricsRegistry,
): Boolean {
    val claims = tokenService.decodeRefreshToken(refreshToken)?.takeIf { it.sessionId != null }
    if (claims == null) {
        respondApiError(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Invalid or expired refresh token", metrics)
        return false
    }
    when (val refreshed = deviceLinkService?.refresh(claims) ?: DeviceSessionRefresh.Revoked) {
        DeviceSessionRefresh.Revoked -> {
            respondApiError(
                HttpStatusCode.Unauthorized,
                DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
                "This device was removed from the account",
                metrics,
            )
            return false
        }

        is DeviceSessionRefresh.Refreshed -> {
            respond(HttpStatusCode.OK, RefreshTokenResponseV1(true, RefreshTokenDataV1(refreshed.accessToken, refreshed.refreshToken)))
        }
    }
    return true
}

private suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    metrics: AuthMetricsRegistry?,
) {
    metrics?.recordError(code)
    respond(status, ApiErrorResponse(ApiError(code, message)))
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.parseRequiredCanonicalOwnerId(
    requestedOwnerId: String?,
    metrics: AuthMetricsRegistry,
): Uuid? {
    val rawOwnerId = requestedOwnerId?.trim().orEmpty()
    if (rawOwnerId.isEmpty()) {
        respondApiError(
            HttpStatusCode.BadRequest,
            "CANONICAL_OWNER_ID_REQUIRED",
            "A canonical device identity is required to create a Cloud account",
            metrics,
        )
        return null
    }

    return runCatching { Uuid.parse(rawOwnerId) }.getOrElse {
        respondApiError(
            HttpStatusCode.BadRequest,
            "CANONICAL_OWNER_ID_INVALID",
            "The canonical device identity must be a UUID",
            metrics,
        )
        return null
    }
}

private suspend fun ApplicationCall.respondForRequestException(
    error: Exception,
    fallbackMessage: String,
    metrics: AuthMetricsRegistry?,
) {
    when (error) {
        is ContentTransformationException,
        is ContentConvertException,
        is SerializationException,
        is BadRequestException,
        -> respondApiError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body is invalid", metrics)

        else -> respondApiError(HttpStatusCode.InternalServerError, "SERVER_ERROR", fallbackMessage, metrics)
    }
}

private suspend fun ApplicationCall.enforceRateLimit(
    rateLimiter: InMemoryAuthRateLimiter,
    operation: String,
    policy: RateLimitPolicy,
    metrics: AuthMetricsRegistry?,
): Boolean {
    val ipKey = hashRemoteIp() ?: clientAddress().ifBlank { "unknown" }
    val rateLimitKey = "$operation:$ipKey"
    if (rateLimiter.allow(rateLimitKey, policy)) {
        return true
    }
    metrics?.recordRateLimit(operation)
    respondApiError(
        HttpStatusCode.TooManyRequests,
        code = "RATE_LIMIT_EXCEEDED",
        message = "Too many requests. Please retry later.",
        metrics = metrics,
    )
    return false
}

private fun ApplicationCall.hashRemoteIp(): String? {
    val value = clientAddress().ifBlank { return null }
    return value.sha256()
}

private fun ApplicationCall.hashUserAgent(): String? {
    val value = request.headers[HttpHeaders.UserAgent] ?: return null
    if (value.isBlank()) return null
    return value.sha256()
}

private fun String.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(toByteArray())
    return hash.joinToString(separator = "") { "%02x".format(it) }
}

private fun app.logdate.server.auth.AuthMetricsSnapshot.toPrometheus(): String {
    val builder = StringBuilder()
    builder.appendLine("# HELP logdate_auth_operation_success_total Successful auth operations by type.")
    builder.appendLine("# TYPE logdate_auth_operation_success_total counter")
    builder.appendLine("# HELP logdate_auth_operation_error_total Failed auth operations by type.")
    builder.appendLine("# TYPE logdate_auth_operation_error_total counter")
    builder.appendLine("# HELP logdate_auth_operation_duration_ms_total Total auth operation duration by type.")
    builder.appendLine("# TYPE logdate_auth_operation_duration_ms_total counter")
    builder.appendLine("# HELP logdate_auth_error_total Auth API errors by error code.")
    builder.appendLine("# TYPE logdate_auth_error_total counter")
    builder.appendLine("# HELP logdate_auth_rate_limit_total Auth rate-limited requests by operation.")
    builder.appendLine("# TYPE logdate_auth_rate_limit_total counter")

    operations.forEach { operation ->
        val label = escapeAuthMetricLabel(operation.name)
        builder.appendLine("logdate_auth_operation_success_total{operation=\"$label\"} ${operation.successCount}")
        builder.appendLine("logdate_auth_operation_error_total{operation=\"$label\"} ${operation.errorCount}")
        builder.appendLine("logdate_auth_operation_duration_ms_total{operation=\"$label\"} ${operation.totalDurationMs}")
    }
    errorsByCode.forEach { (code, count) ->
        val label = escapeAuthMetricLabel(code)
        builder.appendLine("logdate_auth_error_total{code=\"$label\"} $count")
    }
    rateLimitedByOperation.forEach { (operation, count) ->
        val label = escapeAuthMetricLabel(operation)
        builder.appendLine("logdate_auth_rate_limit_total{operation=\"$label\"} $count")
    }

    return builder.toString()
}

private fun escapeAuthMetricLabel(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

/** Audience identifier the holder Key Binding JWT must declare. Surfaced to
 * clients via the /begin response so they don't need to hardcode it. */
private const val EMAIL_VERIFICATION_AUDIENCE = "https://logdate.app/auth/email"

/**
 * Returns true when the email_verification entitlement flag is on for [accountId].
 * Falls open (true) when no [entitlementService] is wired — matching the self-host
 * default exposed by [GET /me/entitlement] for installs without billing.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun isEmailVerificationFeatureEnabled(
    accountId: Uuid,
    entitlementService: EntitlementService?,
): Boolean {
    val service = entitlementService ?: return true
    return try {
        service.resolve(java.util.UUID.fromString(accountId.toString())).features["email_verification"] == true
    } catch (e: Exception) {
        io.github.aakira.napier.Napier
            .w("Failed to resolve email_verification entitlement; treating as disabled", e)
        false
    }
}
