package app.logdate.client.networking

import app.logdate.client.networking.PasskeyApiClient.Companion.AUTH_PATH
import app.logdate.shared.model.AccountInfoResponse
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.RefreshTokenRequest
import app.logdate.shared.model.UpdateAccountProfileRequest
import io.github.aakira.napier.Napier
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

internal suspend fun PasskeyApiClient.requestGetAccountInfo(accessToken: String): Result<LogDateAccount> =
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

internal suspend fun PasskeyApiClient.requestGetEntitlement(accessToken: String): Result<EntitlementResponse> =
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

internal suspend fun PasskeyApiClient.requestUpdateAccountProfileFields(
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

internal suspend fun PasskeyApiClient.requestRefreshToken(refreshToken: String): Result<String> =
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

internal suspend fun PasskeyApiClient.requestLogout(refreshToken: String): Result<Unit> =
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
