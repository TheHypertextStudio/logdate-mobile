@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountSessionUnavailableException
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.clientAddress
import app.logdate.server.identity.HandleLabels
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val USERNAME_MIN_LENGTH = 3
internal const val USERNAME_MAX_LENGTH = 50
internal const val DISPLAY_NAME_MAX_LENGTH = 100
internal val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]+$")
internal const val EMAIL_BINDING_SOURCE_GOOGLE = "google_id_token"
internal val SIGNUP_RATE_LIMIT = RateLimitPolicy(maxRequests = 5, windowSeconds = 60 * 60)
internal val SIGNIN_RATE_LIMIT = RateLimitPolicy(maxRequests = 10, windowSeconds = 60)

internal suspend fun resolveEmailBinding(
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

internal fun validateUsername(username: String): String? {
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

internal fun validateDisplayName(displayName: String): String? {
    if (displayName.isBlank()) {
        return "Display name is required"
    }

    if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
        return "Display name must be 1-100 characters long"
    }

    return null
}

internal class InMemoryAuthRateLimiter {
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

internal suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    metrics: AuthMetricsRegistry?,
) {
    metrics?.recordError(code)
    respond(status, ApiErrorResponse(ApiError(code, message)))
}

internal suspend fun ApplicationCall.parseRequiredCanonicalOwnerId(
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

internal suspend fun ApplicationCall.respondForRequestException(
    error: Exception,
    fallbackMessage: String,
    metrics: AuthMetricsRegistry?,
) {
    when (error) {
        is AccountSessionUnavailableException ->
            respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "This account cannot start a session", metrics)
        is ContentTransformationException,
        is ContentConvertException,
        is SerializationException,
        is BadRequestException,
        -> respondApiError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body is invalid", metrics)

        else -> respondApiError(HttpStatusCode.InternalServerError, "SERVER_ERROR", fallbackMessage, metrics)
    }
}

internal suspend fun ApplicationCall.enforceRateLimit(
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

internal fun ApplicationCall.hashRemoteIp(): String? {
    val value = clientAddress().ifBlank { return null }
    return value.sha256()
}

internal fun ApplicationCall.hashUserAgent(): String? {
    val value = request.headers[HttpHeaders.UserAgent] ?: return null
    if (value.isBlank()) return null
    return value.sha256()
}

internal fun String.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(toByteArray())
    return hash.joinToString(separator = "") { "%02x".format(it) }
}

internal suspend fun ApplicationCall.rejectAuthRequest(
    status: HttpStatusCode,
    code: String,
    message: String,
    metrics: AuthMetricsRegistry?,
): Nothing? {
    respondApiError(status, code, message, metrics)
    return null
}
