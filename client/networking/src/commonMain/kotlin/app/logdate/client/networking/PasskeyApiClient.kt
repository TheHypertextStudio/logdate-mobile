package app.logdate.client.networking

import app.logdate.shared.config.LogDateConfigRepository
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
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.UsernameAvailabilityData
import app.logdate.util.UuidSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

/** Auth v1 operations using the selected server and shared transport. */
class PasskeyApiClient(
    internal val httpClient: HttpClient,
    internal val configRepository: LogDateConfigRepository,
    internal val json: Json =
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
        internal const val AUTH_PATH = "/auth"

        /** How long a sign-in request may take before the person is told the server isn't responding. */
        internal const val SIGN_IN_TIMEOUT_MS = 30_000L
    }

    internal suspend fun getBaseUrl(): String = configRepository.apiBaseUrl.first()

    /**
     * The failure a non-success response describes. The server's own `{code, message}` envelope
     * wins; a server error without one (a gateway's timeout page, for instance) means the server is
     * not responding, and anything else unreadable is reported as such rather than as a network
     * problem.
     */
    internal suspend fun HttpResponse.toPasskeyApiException(): PasskeyApiException {
        val envelope = runCatching { json.decodeFromString<ApiErrorResponse>(bodyAsText()) }.getOrNull()
        return when {
            envelope != null -> PasskeyApiException(envelope.error.code, envelope.error.message)
            status.value >= 500 -> PasskeyApiException(PasskeyApiErrorCodes.SERVER_UNAVAILABLE, "The server answered HTTP ${status.value}")
            else ->
                PasskeyApiException(
                    PasskeyApiErrorCodes.UNEXPECTED_RESPONSE,
                    "The server answered HTTP ${status.value} without an error",
                )
        }
    }

    /** A request that failed before any answer: a stalled server, or no connection at all. */
    internal fun requestFailure(
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
        requestCheckUsernameAvailability(username)

    override suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> =
        requestBeginAccountCreation(request)

    override suspend fun completeAccountCreation(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData> =
        requestCompleteAccountCreation(request)

    override suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> =
        requestBeginAuthentication(request)

    override suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
        requestCompleteAuthentication(request)

    override suspend fun getAccountInfo(accessToken: String): Result<LogDateAccount> = requestGetAccountInfo(accessToken)

    override suspend fun getEntitlement(accessToken: String): Result<EntitlementResponse> = requestGetEntitlement(accessToken)

    override suspend fun updateAccountProfile(
        accessToken: String,
        displayName: String?,
        username: String?,
        bio: String?,
    ): Result<LogDateAccount> = requestUpdateAccountProfileFields(accessToken, displayName, username, bio, null)

    override suspend fun updateAccountProfileWithOriginalBio(
        accessToken: String,
        bio: String?,
        originalBio: String?,
    ): Result<LogDateAccount> = requestUpdateAccountProfileFields(accessToken, null, null, bio, originalBio)

    override suspend fun refreshToken(refreshToken: String): Result<String> = requestRefreshToken(refreshToken)

    override suspend fun logout(refreshToken: String): Result<Unit> = requestLogout(refreshToken)

    override suspend fun listPasskeys(accessToken: String): Result<List<PasskeyInfo>> = requestListPasskeys(accessToken)

    override suspend fun beginAddPasskey(accessToken: String): Result<PasskeyRegistrationOptions> = requestBeginAddPasskey(accessToken)

    override suspend fun completeAddPasskey(
        accessToken: String,
        request: AddPasskeyRequest,
    ): Result<PasskeyInfo> = requestCompleteAddPasskey(accessToken, request)

    override suspend fun listSignInIdentities(accessToken: String): Result<List<AccountSignInIdentity>> =
        requestListSignInIdentities(accessToken)

    override suspend fun deletePasskey(
        accessToken: String,
        credentialId: String,
    ): Result<Unit> = requestDeletePasskey(accessToken, credentialId)

    override suspend fun deleteAccount(accessToken: String): Result<Unit> = requestDeleteAccount(accessToken)

    override suspend fun signUpWithGoogle(
        idToken: String,
        username: String?,
        displayName: String?,
        nonce: String?,
        requestedOwnerId: String?,
    ): Result<CompleteAccountCreationData> = requestSignUpWithGoogle(idToken, username, displayName, nonce, requestedOwnerId)

    override suspend fun signInWithGoogle(
        idToken: String,
        nonce: String?,
    ): Result<CompleteAuthenticationData> = requestSignInWithGoogle(idToken, nonce)

    override suspend fun beginRestoreKeyRegistration(accessToken: String): Result<PasskeyRegistrationOptions> =
        requestBeginRestoreKeyRegistration(accessToken)

    override suspend fun completeRestoreKeyRegistration(
        accessToken: String,
        credentialJson: String,
        challenge: String,
    ): Result<Unit> = requestCompleteRestoreKeyRegistration(accessToken, credentialJson, challenge)

    override suspend fun beginRestoreSignIn(): Result<BeginAuthenticationData> = requestBeginRestoreSignIn()

    override suspend fun completeRestoreSignIn(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
        requestCompleteRestoreSignIn(request)
}
