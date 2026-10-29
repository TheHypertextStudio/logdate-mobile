package app.logdate.client.networking

import app.logdate.client.networking.PasskeyApiClient.Companion.AUTH_PATH
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import io.github.aakira.napier.Napier
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

internal suspend fun PasskeyApiClient.requestListPasskeys(accessToken: String): Result<List<PasskeyInfo>> =
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

internal suspend fun PasskeyApiClient.requestBeginAddPasskey(accessToken: String): Result<PasskeyRegistrationOptions> =
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

internal suspend fun PasskeyApiClient.requestCompleteAddPasskey(
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

internal suspend fun PasskeyApiClient.requestListSignInIdentities(accessToken: String): Result<List<AccountSignInIdentity>> =
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

internal suspend fun PasskeyApiClient.requestDeletePasskey(
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

internal suspend fun PasskeyApiClient.requestDeleteAccount(accessToken: String): Result<Unit> =
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

internal suspend fun PasskeyApiClient.requestBeginRestoreKeyRegistration(accessToken: String): Result<PasskeyRegistrationOptions> =
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

internal suspend fun PasskeyApiClient.requestCompleteRestoreKeyRegistration(
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
