package app.logdate.client.networking

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.BeginEmailVerificationResponse
import app.logdate.shared.model.CompleteEmailVerificationRequest
import app.logdate.shared.model.EmailVerificationConflictResponse
import app.logdate.shared.model.EmailVerificationErrorResponse
import app.logdate.shared.model.EmailVerifiedResponse
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Contract for the Android Digital Credentials email-verification HTTP flow:
 * /begin issues a fresh nonce + transaction id, /complete walks the wallet's
 * response through the server-side verifier and updates the account email.
 *
 * Both methods return a [Result] so transport-level failures (timeouts, JSON
 * parse errors, 5xx) are distinguishable from the well-defined HTTP outcomes
 * (200/400/409) the server exposes for verification.
 */
interface EmailVerificationApiClientContract {
    suspend fun begin(accessToken: String): Result<BeginEmailVerificationResponse>

    suspend fun complete(
        accessToken: String,
        request: CompleteEmailVerificationRequest,
    ): Result<EmailVerificationCompletion>
}

/** Server-modeled outcomes for a single `/complete` call. */
sealed interface EmailVerificationCompletion {
    data class Success(
        val email: String,
        val verifiedAt: Instant,
    ) : EmailVerificationCompletion

    /** 409 — the verified email is already attached to a different LogDate account. */
    data class Conflict(
        val message: String,
    ) : EmailVerificationCompletion

    /**
     * 400 — verification failed for a stable reason. The reason code is one of
     * the strings produced by `DigitalCredentialVerifier` or
     * `EmailVerificationService` (see those classes for the catalogue).
     */
    data class Failed(
        val reason: String,
    ) : EmailVerificationCompletion
}

class EmailVerificationApiClient(
    private val httpClient: HttpClient,
    private val configRepository: LogDateConfigRepository,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        },
) : EmailVerificationApiClientContract {
    private companion object {
        const val PATH_PREFIX = "/auth/me/email/verify"
    }

    private suspend fun baseUrl(): String = configRepository.apiBaseUrl.first()

    override suspend fun begin(accessToken: String): Result<BeginEmailVerificationResponse> =
        try {
            val response =
                httpClient.post("${baseUrl()}$PATH_PREFIX/begin") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            if (response.status.value in 200..299) {
                Result.success(json.decodeFromString<BeginEmailVerificationResponse>(response.bodyAsText()))
            } else {
                val err =
                    runCatching { json.decodeFromString<ApiErrorResponse>(response.bodyAsText()) }
                        .getOrNull()
                Result.failure(
                    EmailVerificationApiException(
                        code = err?.error?.code ?: "HTTP_${response.status.value}",
                        message = err?.error?.message ?: "Begin failed (${response.status.value})",
                    ),
                )
            }
        } catch (e: Exception) {
            Napier.w("Failed to begin email verification", e)
            Result.failure(EmailVerificationApiException("NETWORK_ERROR", "Failed to begin email verification", e))
        }

    override suspend fun complete(
        accessToken: String,
        request: CompleteEmailVerificationRequest,
    ): Result<EmailVerificationCompletion> =
        try {
            val response =
                httpClient.post("${baseUrl()}$PATH_PREFIX/complete") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            val body = response.bodyAsText()
            when (response.status) {
                HttpStatusCode.OK -> {
                    val data = json.decodeFromString<EmailVerifiedResponse>(body)
                    Result.success(EmailVerificationCompletion.Success(data.email, data.emailVerifiedAt))
                }

                HttpStatusCode.Conflict -> {
                    val data = json.decodeFromString<EmailVerificationConflictResponse>(body)
                    Result.success(EmailVerificationCompletion.Conflict(data.message))
                }

                HttpStatusCode.BadRequest -> {
                    val data = json.decodeFromString<EmailVerificationErrorResponse>(body)
                    Result.success(EmailVerificationCompletion.Failed(data.reason))
                }

                else -> {
                    val err = runCatching { json.decodeFromString<ApiErrorResponse>(body) }.getOrNull()
                    Result.failure(
                        EmailVerificationApiException(
                            code = err?.error?.code ?: "HTTP_${response.status.value}",
                            message = err?.error?.message ?: "Complete failed (${response.status.value})",
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Napier.w("Failed to complete email verification", e)
            Result.failure(EmailVerificationApiException("NETWORK_ERROR", "Failed to complete email verification", e))
        }
}

class EmailVerificationApiException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
