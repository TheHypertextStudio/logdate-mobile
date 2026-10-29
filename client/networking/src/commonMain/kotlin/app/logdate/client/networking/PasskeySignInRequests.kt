package app.logdate.client.networking

import app.logdate.client.networking.PasskeyApiClient.Companion.AUTH_PATH
import app.logdate.client.networking.PasskeyApiClient.Companion.SIGN_IN_TIMEOUT_MS
import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.UsernameAvailabilityData
import app.logdate.shared.model.UsernameAvailabilityResponse
import io.github.aakira.napier.Napier
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

internal suspend fun PasskeyApiClient.requestCheckUsernameAvailability(username: String): Result<UsernameAvailabilityData> =
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

internal suspend fun PasskeyApiClient.requestBeginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> =
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

internal suspend fun PasskeyApiClient.requestCompleteAccountCreation(
    request: CompleteAccountCreationRequest,
): Result<CompleteAccountCreationData> =
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

internal suspend fun PasskeyApiClient.requestBeginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> =
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

internal suspend fun PasskeyApiClient.requestCompleteAuthentication(
    request: CompleteAuthenticationRequest,
): Result<CompleteAuthenticationData> =
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

internal suspend fun PasskeyApiClient.requestSignUpWithGoogle(
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

internal suspend fun PasskeyApiClient.requestSignInWithGoogle(
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

internal suspend fun PasskeyApiClient.requestBeginRestoreSignIn(): Result<BeginAuthenticationData> =
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

internal suspend fun PasskeyApiClient.requestCompleteRestoreSignIn(
    request: CompleteAuthenticationRequest,
): Result<CompleteAuthenticationData> =
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
