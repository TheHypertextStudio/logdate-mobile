package app.logdate.client.networking

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.AccountInfoResponse
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAllowCredential
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.RefreshTokenRequest
import app.logdate.shared.model.UpdateAccountProfileRequest
import app.logdate.shared.model.UsernameAvailabilityData
import app.logdate.shared.model.UsernameAvailabilityResponse
import app.logdate.util.UuidSerializer
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Interface for passkey API operations.
 */
interface PasskeyApiClientContract {
    suspend fun checkUsernameAvailability(username: String): Result<UsernameAvailabilityData>

    suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData>

    suspend fun completeAccountCreation(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData>

    suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData>

    suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData>

    suspend fun getAccountInfo(accessToken: String): Result<LogDateAccount>

    /** Fetch the caller's current cloud entitlement (plan + quota + status). */
    suspend fun getEntitlement(accessToken: String): Result<EntitlementResponse>

    suspend fun updateAccountProfile(
        accessToken: String,
        displayName: String? = null,
        username: String? = null,
        bio: String? = null,
    ): Result<LogDateAccount>

    suspend fun updateAccountProfileWithOriginalBio(
        accessToken: String,
        bio: String?,
        originalBio: String?,
    ): Result<LogDateAccount> = Result.failure(UnsupportedOperationException("Cloud original bio updates unavailable"))

    suspend fun refreshToken(refreshToken: String): Result<String>

    /** Best-effort server-side refresh-token revocation for an explicit sign-out. */
    suspend fun logout(refreshToken: String): Result<Unit> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Remote logout is not supported by this client"))

    suspend fun deletePasskey(
        accessToken: String,
        credentialId: String,
    ): Result<Unit>

    /**
     * Lists the account's passkeys with the detail the account payload does not carry -- a
     * nickname, a device type, and when each was created and last used.
     *
     * The account itself only knows credential IDs, which cannot tell a person which of their
     * devices a credential belongs to.
     */
    suspend fun listPasskeys(accessToken: String): Result<List<PasskeyInfo>>

    /** Starts adding a passkey to the signed-in account; returns the options for the platform. */
    suspend fun beginAddPasskey(accessToken: String): Result<PasskeyRegistrationOptions> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Adding passkeys is not supported by this client"))

    /** Finishes adding a passkey with the credential the platform created. */
    suspend fun completeAddPasskey(
        accessToken: String,
        request: AddPasskeyRequest,
    ): Result<PasskeyInfo> = Result.failure(PasskeyApiException("NOT_SUPPORTED", "Adding passkeys is not supported by this client"))

    /**
     * Lists every way the account can sign in, passkeys included, as recorded by the server.
     */
    suspend fun listSignInIdentities(accessToken: String): Result<List<AccountSignInIdentity>> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Listing sign-in methods is not supported by this client"))

    /**
     * Begin restore key registration. Returns WebAuthn registration options for creating a restore key.
     * Requires an authenticated session.
     */
    suspend fun beginRestoreKeyRegistration(accessToken: String): Result<PasskeyRegistrationOptions>

    /**
     * Complete restore key registration by sending the credential JSON back to the server.
     * Requires an authenticated session.
     *
     * @param credentialJson The WebAuthn registration response JSON from [RestoreCredentialManager]
     * @param challenge The challenge that was used during registration (from [beginRestoreKeyRegistration])
     */
    suspend fun completeRestoreKeyRegistration(
        accessToken: String,
        credentialJson: String,
        challenge: String,
    ): Result<Unit>

    /**
     * Begin restore sign-in. Returns a WebAuthn authentication challenge.
     * Does not require authentication — called on first launch after device restore.
     */
    suspend fun beginRestoreSignIn(): Result<BeginAuthenticationData>

    /**
     * Complete restore sign-in. Verifies the restore credential and returns tokens.
     */
    suspend fun completeRestoreSignIn(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData>

    /**
     * Delete the authenticated account, including all sync data, media blobs, and backup blobs.
     *
     * Returns success on `204 No Content`. The caller should clear all local state immediately
     * after success — every other authenticated request will start returning 401 because the
     * server-side account no longer exists.
     *
     * Returns a `PasskeyApiException` with code `DELETION_UNAVAILABLE` (HTTP 503) when the
     * server hasn't been configured with an `AccountDeletionService`. Surface that as
     * "Account deletion is temporarily unavailable" rather than a generic error.
     */
    suspend fun deleteAccount(accessToken: String): Result<Unit>

    /**
     * Create an account from a verified Google ID token. The server validates the token against its
     * configured Google OIDC client IDs and returns the new account plus tokens. Default fails so
     * non-Google clients/fakes don't have to implement it.
     */
    suspend fun signUpWithGoogle(
        idToken: String,
        username: String? = null,
        displayName: String? = null,
        nonce: String? = null,
        requestedOwnerId: String? = null,
    ): Result<CompleteAccountCreationData> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Google sign-up is not supported by this client"))

    /** Sign in with a verified Google ID token. */
    suspend fun signInWithGoogle(
        idToken: String,
        nonce: String? = null,
    ): Result<CompleteAuthenticationData> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Google sign-in is not supported by this client"))
}

/**
 * API client for auth v1 passkey-based account creation and authentication.
 */
class PasskeyApiClient(
    private val httpClient: HttpClient,
    private val configRepository: LogDateConfigRepository,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            serializersModule =
                SerializersModule {
                    contextual(Uuid::class, UuidSerializer)
                }
        },
) : PasskeyApiClientContract {
    companion object {
        private const val AUTH_PATH = "/auth"

        /** How long a sign-in request may take before the person is told the server isn't responding. */
        private const val SIGN_IN_TIMEOUT_MS = 30_000L
    }

    private suspend fun getBaseUrl(): String = configRepository.apiBaseUrl.first()

    /**
     * The failure a non-success response describes. The server's own `{code, message}` envelope
     * wins; a server error without one (a gateway's timeout page, for instance) means the server is
     * not responding, and anything else unreadable is reported as such rather than as a network
     * problem.
     */
    private suspend fun HttpResponse.toPasskeyApiException(): PasskeyApiException {
        val envelope = runCatching { json.decodeFromString<ApiErrorResponse>(bodyAsText()) }.getOrNull()
        return when {
            envelope != null -> {
                PasskeyApiException(envelope.error.code, envelope.error.message)
            }

            status.value >= 500 -> {
                PasskeyApiException(PasskeyApiErrorCodes.SERVER_UNAVAILABLE, "The server answered HTTP ${status.value}")
            }

            else -> {
                PasskeyApiException(
                    PasskeyApiErrorCodes.UNEXPECTED_RESPONSE,
                    "The server answered HTTP ${status.value} without an error",
                )
            }
        }
    }

    /** A request that failed before any answer: a stalled server, or no connection at all. */
    private fun requestFailure(
        action: String,
        error: Exception,
    ): PasskeyApiException {
        val code =
            if (error.isTimeout()) {
                PasskeyApiErrorCodes.SERVER_UNAVAILABLE
            } else {
                PasskeyApiErrorCodes.NETWORK_ERROR
            }
        return PasskeyApiException(code, action, error)
    }

    override suspend fun checkUsernameAvailability(username: String): Result<UsernameAvailabilityData> =
        try {
            val baseUrl = getBaseUrl()
            val response = httpClient.get("$baseUrl$AUTH_PATH/signup/username/$username/available")

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<UsernameAvailabilityResponse>(response.bodyAsText())
                Result.success(apiResponse.data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to check username availability", e)
            Result.failure(requestFailure("Failed to check username availability", e))
        }

    override suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/signup/passkey/begin") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        SignupPasskeyBeginRequestDto(
                            username = request.username,
                            displayName = request.displayName,
                            bio = request.bio,
                            originalBio = request.originalBio,
                            requestedOwnerId = request.requestedOwnerId,
                        ),
                    )
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<SignupPasskeyBeginResponseDto>(response.bodyAsText())
                Result.success(
                    BeginAccountCreationData(
                        sessionToken = apiResponse.data.sessionToken,
                        registrationOptions = apiResponse.data.registrationOptions,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to begin account creation", e)
            Result.failure(requestFailure("Failed to begin account creation", e))
        }

    override suspend fun completeAccountCreation(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<AuthResponseDto>(response.bodyAsText())
                Result.success(
                    CompleteAccountCreationData(
                        account = apiResponse.data.account.toLogDateAccount(),
                        tokens = apiResponse.data.tokens,
                        stepUpGrant = apiResponse.data.stepUpGrant,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to complete account creation", e)
            Result.failure(requestFailure("Failed to complete account creation", e))
        }

    override suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/signin/passkey/begin") {
                    timeout { requestTimeoutMillis = SIGN_IN_TIMEOUT_MS }
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<SigninPasskeyBeginResponseDto>(response.bodyAsText())
                Result.success(
                    BeginAuthenticationData(
                        challenge = apiResponse.data.challenge,
                        rpId = apiResponse.data.rpId,
                        allowCredentials = apiResponse.data.allowCredentials,
                        timeout = apiResponse.data.timeout,
                        userVerification = apiResponse.data.userVerification,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to begin authentication", e)
            Result.failure(requestFailure("Failed to begin authentication", e))
        }

    override suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/signin/passkey/complete") {
                    timeout { requestTimeoutMillis = SIGN_IN_TIMEOUT_MS }
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<AuthResponseDto>(response.bodyAsText())
                Result.success(
                    CompleteAuthenticationData(
                        account = apiResponse.data.account.toLogDateAccount(),
                        tokens = apiResponse.data.tokens,
                        stepUpGrant = apiResponse.data.stepUpGrant,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to complete authentication", e)
            Result.failure(requestFailure("Failed to complete authentication", e))
        }

    override suspend fun getAccountInfo(accessToken: String): Result<LogDateAccount> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.get("$baseUrl$AUTH_PATH/me") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                val authResponse = json.decodeFromString<AuthResponseDto>(response.bodyAsText())
                Result.success(authResponse.data.account.toLogDateAccount())
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to get account info", e)
            Result.failure(requestFailure("Failed to get account info", e))
        }

    override suspend fun getEntitlement(accessToken: String): Result<EntitlementResponse> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.get("$baseUrl$AUTH_PATH/me/entitlement") {
                    header("Authorization", "Bearer $accessToken")
                }
            if (response.status.value in 200..299) {
                Result.success(json.decodeFromString<EntitlementResponse>(response.bodyAsText()))
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to get entitlement", e)
            Result.failure(requestFailure("Failed to get entitlement", e))
        }

    override suspend fun updateAccountProfile(
        accessToken: String,
        displayName: String?,
        username: String?,
        bio: String?,
    ): Result<LogDateAccount> = updateAccountProfileFields(accessToken, displayName, username, bio, null)

    override suspend fun updateAccountProfileWithOriginalBio(
        accessToken: String,
        bio: String?,
        originalBio: String?,
    ): Result<LogDateAccount> = updateAccountProfileFields(accessToken, null, null, bio, originalBio)

    private suspend fun updateAccountProfileFields(
        accessToken: String,
        displayName: String?,
        username: String?,
        bio: String?,
        originalBio: String?,
    ): Result<LogDateAccount> =
        try {
            val baseUrl = getBaseUrl()
            val updateRequest =
                UpdateAccountProfileRequest(displayName = displayName, username = username, bio = bio, originalBio = originalBio)
            val response =
                httpClient.put("$baseUrl$AUTH_PATH/me") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody(updateRequest)
                }

            if (response.status.value in 200..299) {
                val accountResponse = json.decodeFromString<AccountInfoResponse>(response.bodyAsText())
                Result.success(accountResponse.data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to update account profile", e)
            Result.failure(requestFailure("Failed to update account profile", e))
        }

    override suspend fun refreshToken(refreshToken: String): Result<String> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest(refreshToken))
                }

            if (response.status.value in 200..299) {
                val tokenResponse = json.decodeFromString<RefreshTokenResponseV1Dto>(response.bodyAsText())
                Result.success(tokenResponse.data.accessToken)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to refresh token", e)
            Result.failure(requestFailure("Failed to refresh token", e))
        }

    override suspend fun logout(refreshToken: String): Result<Unit> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/logout") {
                    contentType(ContentType.Application.Json)
                    setBody(LogoutRequestDto(refreshToken))
                }

            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to revoke refresh token during logout", e)
            Result.failure(requestFailure("Failed to revoke refresh token", e))
        }

    override suspend fun listPasskeys(accessToken: String): Result<List<PasskeyInfo>> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.get("$baseUrl$AUTH_PATH/me/passkeys") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                Result.success(json.decodeFromString<PasskeyListApiResponse>(response.bodyAsText()).data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to list passkeys", e)
            Result.failure(requestFailure("Failed to list passkeys", e))
        }

    override suspend fun beginAddPasskey(accessToken: String): Result<PasskeyRegistrationOptions> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/me/passkeys/begin") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                Result.success(json.decodeFromString<RestoreRegisterBeginResponseDto>(response.bodyAsText()).data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to begin adding a passkey", e)
            Result.failure(requestFailure("Failed to begin adding a passkey", e))
        }

    override suspend fun completeAddPasskey(
        accessToken: String,
        request: AddPasskeyRequest,
    ): Result<PasskeyInfo> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/me/passkeys/complete") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody(request)
                }

            if (response.status.value in 200..299) {
                Result.success(json.decodeFromString<PasskeyApiResponse>(response.bodyAsText()).data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to complete adding a passkey", e)
            Result.failure(requestFailure("Failed to complete adding a passkey", e))
        }

    override suspend fun listSignInIdentities(accessToken: String): Result<List<AccountSignInIdentity>> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.get("$baseUrl$AUTH_PATH/me/identities") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                Result.success(json.decodeFromString<SignInIdentityListApiResponse>(response.bodyAsText()).data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to list sign-in methods", e)
            Result.failure(requestFailure("Failed to list sign-in methods", e))
        }

    override suspend fun deletePasskey(
        accessToken: String,
        credentialId: String,
    ): Result<Unit> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.delete("$baseUrl$AUTH_PATH/me/passkeys/$credentialId") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to delete passkey", e)
            Result.failure(requestFailure("Failed to delete passkey", e))
        }

    override suspend fun deleteAccount(accessToken: String): Result<Unit> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.delete("$baseUrl$AUTH_PATH/me") {
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to delete account", e)
            Result.failure(requestFailure("Failed to delete account", e))
        }

    override suspend fun signUpWithGoogle(
        idToken: String,
        username: String?,
        displayName: String?,
        nonce: String?,
        requestedOwnerId: String?,
    ): Result<CompleteAccountCreationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        GoogleAuthRequestDto(
                            idToken = idToken,
                            username = username,
                            displayName = displayName,
                            nonce = nonce,
                            requestedOwnerId = requestedOwnerId,
                        ),
                    )
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<AuthResponseDto>(response.bodyAsText())
                Result.success(
                    CompleteAccountCreationData(
                        account = apiResponse.data.account.toLogDateAccount(),
                        tokens = apiResponse.data.tokens,
                        stepUpGrant = apiResponse.data.stepUpGrant,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to sign up with Google", e)
            Result.failure(requestFailure("Failed to sign up with Google", e))
        }

    override suspend fun signInWithGoogle(
        idToken: String,
        nonce: String?,
    ): Result<CompleteAuthenticationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/signin/google") {
                    contentType(ContentType.Application.Json)
                    setBody(GoogleAuthRequestDto(idToken = idToken, nonce = nonce))
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<AuthResponseDto>(response.bodyAsText())
                Result.success(
                    CompleteAuthenticationData(
                        account = apiResponse.data.account.toLogDateAccount(),
                        tokens = apiResponse.data.tokens,
                        stepUpGrant = apiResponse.data.stepUpGrant,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to sign in with Google", e)
            Result.failure(requestFailure("Failed to sign in with Google", e))
        }

    override suspend fun beginRestoreKeyRegistration(accessToken: String): Result<PasskeyRegistrationOptions> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/restore/register/begin") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<RestoreRegisterBeginResponseDto>(response.bodyAsText())
                Result.success(apiResponse.data)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to begin restore key registration", e)
            Result.failure(requestFailure("Failed to begin restore key registration", e))
        }

    override suspend fun completeRestoreKeyRegistration(
        accessToken: String,
        credentialJson: String,
        challenge: String,
    ): Result<Unit> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/restore/register/complete") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody(RestoreRegisterCompleteRequestDto(credentialJson, challenge))
                }

            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.w("Failed to complete restore key registration", e)
            Result.failure(requestFailure("Failed to complete restore key registration", e))
        }

    override suspend fun beginRestoreSignIn(): Result<BeginAuthenticationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/restore/begin") {
                    contentType(ContentType.Application.Json)
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<SigninPasskeyBeginResponseDto>(response.bodyAsText())
                Result.success(
                    BeginAuthenticationData(
                        challenge = apiResponse.data.challenge,
                        rpId = apiResponse.data.rpId,
                        allowCredentials = apiResponse.data.allowCredentials,
                        timeout = apiResponse.data.timeout,
                        userVerification = apiResponse.data.userVerification,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.i("Failed to begin restore sign-in: ${e.message ?: "unknown error"}")
            Result.failure(requestFailure("Failed to begin restore sign-in", e))
        }

    override suspend fun completeRestoreSignIn(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
        try {
            val baseUrl = getBaseUrl()
            val response =
                httpClient.post("$baseUrl$AUTH_PATH/restore/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.value in 200..299) {
                val apiResponse = json.decodeFromString<AuthResponseDto>(response.bodyAsText())
                Result.success(
                    CompleteAuthenticationData(
                        account = apiResponse.data.account.toLogDateAccount(),
                        tokens = apiResponse.data.tokens,
                        stepUpGrant = apiResponse.data.stepUpGrant,
                    ),
                )
            } else {
                Result.failure(response.toPasskeyApiException())
            }
        } catch (e: Exception) {
            Napier.i("Failed to complete restore sign-in: ${e.message ?: "unknown error"}")
            Result.failure(requestFailure("Failed to complete restore sign-in", e))
        }
}

/**
 * Exception thrown when passkey API operations fail.
 */
class PasskeyApiException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Common API error codes.
 */
object PasskeyApiErrorCodes {
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INVALID_SESSION_TOKEN = "INVALID_SESSION_TOKEN"
    const val PASSKEY_VERIFICATION_FAILED = "PASSKEY_VERIFICATION_FAILED"
    const val ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
    const val AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val INVALID_TOKEN = "INVALID_TOKEN"
    const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    const val REFRESH_TOKEN_REVOKED = "REFRESH_TOKEN_REVOKED"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val PASSKEY_NOT_FOUND = "PASSKEY_NOT_FOUND"
    const val PASSKEY_DELETION_FAILED = "PASSKEY_DELETION_FAILED"

    // Codes the server emits that the client can usefully distinguish.
    const val RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED"
    const val ACCOUNT_LINK_CONFLICT = "ACCOUNT_LINK_CONFLICT"
    const val EMAIL_BINDING_INVALID = "EMAIL_BINDING_INVALID"
    const val GOOGLE_AUTH_NOT_CONFIGURED = "GOOGLE_AUTH_NOT_CONFIGURED"
    const val GOOGLE_TOKEN_INVALID = "GOOGLE_TOKEN_INVALID"
    const val DELETION_UNAVAILABLE = "DELETION_UNAVAILABLE"
    const val LAST_SIGNIN_FACTOR = "LAST_SIGNIN_FACTOR"
    const val CANONICAL_OWNER_ID_TAKEN = "CANONICAL_OWNER_ID_TAKEN"
    const val SERVER_ERROR = "SERVER_ERROR"

    // Codes this client assigns when the server's answer carries no code of its own.

    /** The server did not answer in time, or answered with a server error and no explanation. */
    const val SERVER_UNAVAILABLE = "SERVER_UNAVAILABLE"

    /** The server answered with something that is not a LogDate API response. */
    const val UNEXPECTED_RESPONSE = "UNEXPECTED_RESPONSE"
}

@Serializable
private data class GoogleAuthRequestDto(
    val idToken: String,
    val username: String? = null,
    val displayName: String? = null,
    val nonce: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
private data class SignupPasskeyBeginRequestDto(
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
private data class SignupPasskeyBeginResponseDto(
    val success: Boolean,
    val data: SignupPasskeyBeginDataDto,
)

@Serializable
private data class SignupPasskeyBeginDataDto(
    val sessionToken: String,
    val registrationOptions: PasskeyRegistrationOptions,
)

@Serializable
private data class SigninPasskeyBeginResponseDto(
    val success: Boolean,
    val data: SigninPasskeyBeginDataDto,
)

@Serializable
private data class SigninPasskeyBeginDataDto(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<PasskeyAllowCredential>,
    val timeout: Long,
    val userVerification: String,
)

@Serializable
private data class AuthResponseDto(
    val success: Boolean,
    val data: AuthResponseDataDto,
)

@Serializable
private data class AuthResponseDataDto(
    val account: AuthAccountDto,
    val tokens: AccountTokens,
    val stepUpGrant: String? = null,
)

@Serializable
private data class AuthAccountDto(
    val id: String,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val bio: String? = null,
    val originalBio: String? = null,
    val passkeyCredentialIds: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val emailVerifiedAt: String? = null,
    /** Absent from servers that predate storage modes, which keep every account in legacy. */
    val storageMode: AccountStorageMode = AccountStorageMode.LEGACY,
)

@Serializable
private data class RefreshTokenResponseV1Dto(
    val success: Boolean,
    val data: RefreshTokenDataV1Dto,
)

@Serializable
private data class RefreshTokenDataV1Dto(
    val accessToken: String,
)

@Serializable
private data class LogoutRequestDto(
    val refreshToken: String,
)

@Serializable
private data class RestoreRegisterBeginResponseDto(
    val success: Boolean,
    val data: PasskeyRegistrationOptions,
)

@Serializable
private data class RestoreRegisterCompleteRequestDto(
    val credentialJson: String,
    val challenge: String,
)

private fun AuthAccountDto.toLogDateAccount(): LogDateAccount =
    LogDateAccount(
        id = Uuid.parse(id),
        username = username,
        displayName = displayName,
        did = did,
        handle = handle,
        bio = bio,
        originalBio = originalBio,
        passkeyCredentialIds = passkeyCredentialIds,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        email = email,
        emailVerified = emailVerified,
        emailVerifiedAt = emailVerifiedAt?.let { Instant.parse(it) },
        storageMode = storageMode,
    )

/** The wrapped shape `GET /auth/me/passkeys` replies with. */
@Serializable
internal data class PasskeyListApiResponse(
    val success: Boolean,
    val data: List<PasskeyInfo>,
)

/** The wrapped shape `POST /auth/me/passkeys/complete` replies with. */
@Serializable
internal data class PasskeyApiResponse(
    val success: Boolean,
    val data: PasskeyInfo,
)

/** A passkey the platform created, sent to be added to the signed-in account. */
@Serializable
data class AddPasskeyRequest(
    val challenge: String,
    val credential: PasskeyCredentialResponse,
    /** The name of this device, so the passkey list can tell the person which one it is. */
    val nickname: String? = null,
)

/**
 * One way an account can sign in, as `GET /auth/me/identities` reports it.
 *
 * @property provider `passkey` or `google`; other values may appear as the server grows.
 */
@Serializable
data class AccountSignInIdentity(
    val provider: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val createdAt: String,
    val lastSignInAt: String? = null,
)

@Serializable
internal data class SignInIdentityListApiResponse(
    val success: Boolean,
    val data: List<AccountSignInIdentity>,
)
