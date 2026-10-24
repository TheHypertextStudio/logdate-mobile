package app.logdate.client.sync.cloud

import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAccountCreationResponse
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAccountCreationResponse
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.RefreshTokenRequest
import app.logdate.shared.model.UsernameAvailabilityResponse
import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Checks if a username is available for registration using the availability endpoint.
 *
 * @param username The username to check availability for.
 * @return Response indicating if the username is available.
 * @throws CloudApiException If the request fails.
 */
internal suspend fun LogDateCloudApiClient.requestCheckUsernameAvailability(username: String): Result<CheckUsernameAvailabilityResponse> =
    try {
        val baseUrl = getBaseUrl()
        val response = transport.get("$baseUrl/auth/signup/username/$username/available")

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<UsernameAvailabilityResponse>()
                if (responseBody.success) {
                    Result.success(
                        CheckUsernameAvailabilityResponse(
                            username = responseBody.data.username,
                            available = responseBody.data.available,
                        ),
                    )
                } else {
                    handleApiError(response)
                }
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to check username availability")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to check username availability",
            ),
        )
    }

/**
 * Begins the account creation process.
 *
 * This initiates the passkey registration process and returns
 * the necessary challenge and options for creating a WebAuthn credential.
 *
 * @param request The account creation request containing user details.
 * @return Response with session token and registration options.
 * @throws CloudApiException If the request fails.
 */
internal suspend fun LogDateCloudApiClient.requestBeginAccountCreation(
    request: BeginAccountCreationRequest,
): Result<BeginAccountCreationResponse> =
    try {
        val baseUrl = getBaseUrl()
        val response =
            transport.post("$baseUrl/auth/signup/passkey/begin") {
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

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<SignupPasskeyBeginResponseDto>()
                if (responseBody.success) {
                    Result.success(
                        BeginAccountCreationResponse(
                            success = true,
                            data =
                                BeginAccountCreationData(
                                    sessionToken = responseBody.data.sessionToken,
                                    registrationOptions = responseBody.data.registrationOptions,
                                ),
                        ),
                    )
                } else {
                    handleApiError(response)
                }
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to begin account creation")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to begin account creation",
            ),
        )
    }

/**
 * Completes the account creation process.
 *
 * This submits the passkey credential created by the client to finalize
 * the account creation process.
 *
 * @param request The request containing the passkey credential.
 * @return Response with the created account details and authentication tokens.
 * @throws CloudApiException If the request fails.
 */
internal suspend fun LogDateCloudApiClient.requestCompleteAccountCreation(
    request: CompleteAccountCreationRequest,
): Result<CompleteAccountCreationResponse> =
    try {
        val baseUrl = getBaseUrl()
        val response =
            transport.post("$baseUrl/auth/signup/passkey/complete") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                val responseBody = response.body<AuthResponseDto>()
                if (responseBody.success) {
                    Result.success(
                        CompleteAccountCreationResponse(
                            success = true,
                            data =
                                CompleteAccountCreationData(
                                    account = responseBody.data.account.toLogDateAccount(),
                                    tokens = responseBody.data.tokens,
                                ),
                        ),
                    )
                } else {
                    handleApiError(response)
                }
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to complete account creation")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to complete account creation",
            ),
        )
    }

/**
 * Refreshes an expired access token.
 *
 * @param refreshToken The refresh token to use.
 * @return A new access token if successful.
 * @throws CloudApiException If the request fails.
 */
internal suspend fun LogDateCloudApiClient.requestRefreshAccessToken(refreshToken: String): Result<String> =
    requestRefreshSession(refreshToken).map { it.accessToken }

/**
 * Refreshes the session. A linked device's refresh also checks that its session still exists, and
 * answers `401 DEVICE_SESSION_REVOKED` when it was removed.
 */
internal suspend fun LogDateCloudApiClient.requestRefreshSession(refreshToken: String): Result<RefreshedSession> =
    try {
        val baseUrl = getBaseUrl(refreshToken = refreshToken)
        val response =
            transport.post("$baseUrl/auth/token/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshTokenRequest(refreshToken))
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<RefreshTokenResponseV1Dto>()
                if (responseBody.success) {
                    Result.success(RefreshedSession(responseBody.data.accessToken, responseBody.data.refreshToken))
                } else {
                    handleApiError(response)
                }
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to refresh access token")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to refresh access token",
            ),
        )
    }

/**
 * Gets the current account information.
 *
 * @param accessToken The access token for authentication.
 * @return The account information if the request is successful.
 * @throws CloudApiException If the request fails.
 */
internal suspend fun LogDateCloudApiClient.requestGetAccountInfo(accessToken: String): Result<LogDateAccount> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        Napier.d("Requesting account info")

        val response =
            transport.get("$baseUrl/auth/me") {
                // Add Authorization header with Bearer token scheme
                headers.append("Authorization", "Bearer $accessToken")
            }

        Napier.d("Account info response received")

        when (response.status) {
            HttpStatusCode.OK -> {
                try {
                    val responseBody = response.body<AuthResponseDto>()
                    Napier.d("Parsed account info response body")

                    if (responseBody.success) {
                        Result.success(responseBody.data.account.toLogDateAccount())
                    } else {
                        handleApiError(response)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Napier.e("Failed to parse account info response")
                    Result.failure(
                        CloudApiException(
                            errorCode = "PARSE_ERROR",
                            message = "Failed to parse account info response",
                        ),
                    )
                }
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to get account info")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to get account info",
            ),
        )
    }

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
private data class AuthResponseDto(
    val success: Boolean,
    val data: AuthResponseDataDto,
)

@Serializable
private data class AuthResponseDataDto(
    val account: AuthAccountDto,
    val tokens: AccountTokens,
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
    /** Sent only when the server rotated a linked device's refresh token. */
    val refreshToken: String? = null,
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
