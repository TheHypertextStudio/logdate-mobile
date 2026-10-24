package app.logdate.client.sync.space

import app.logdate.client.device.crypto.IdentityKeyNotFoundException
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.MediaTooLargeException
import app.logdate.space.DataHomeBusyException
import app.logdate.space.DataHomeConflictException
import app.logdate.space.DataHomeCursorTooOldException
import app.logdate.space.DataHomeQuotaExceededException
import app.logdate.space.DataHomeRateLimitedException
import studio.hypertext.atproto.xrpc.XrpcProtocolException
import studio.hypertext.atproto.xrpc.XrpcSerializationException
import studio.hypertext.atproto.xrpc.XrpcTransportException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A record this device built that its schema rejects. Sending it would fail the same way every time. */
class SpaceRecordInvalidException(
    message: String,
) : Exception(message)

/** The personal space can't be used for the signed-in account, such as when the account has no DID. */
class SpaceUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The signed-in account or server changed while personal-space work was under way. The work stops
 * and runs again for the new account; it is not a cancellation of the sync.
 */
class SpaceScopeChangedException : Exception("The signed-in account or server changed during sync")

/**
 * When the server's rate limit on this device lifts, shared by every personal-space call in one sync
 * engine: once the server answers `429`, uploads and downloads both wait at least as long as its
 * `Retry-After` asked, instead of each sending another request to be refused.
 */
internal class SpaceRateLimit(
    private val now: () -> Instant = { Clock.System.now() },
) {
    private var until: Instant? = null

    /** Remembers the wait [error] asks for, when it is a rate-limit refusal. */
    fun record(error: Throwable) {
        val refusal = error as? CloudApiException ?: return
        if (refusal.statusCode != TOO_MANY_REQUESTS) return
        val wait = (refusal.retryAfterMillis ?: DEFAULT_WAIT.inWholeMilliseconds).milliseconds
        val lifts = now() + wait
        until = until?.let { maxOf(it, lifts) } ?: lifts
    }

    /** The refusal to report instead of sending a request, while the limit still applies. */
    fun refusal(): CloudApiException? {
        val lifts = until ?: return null
        val remaining = lifts - now()
        if (remaining <= Duration.ZERO) {
            until = null
            return null
        }
        return CloudApiException(
            errorCode = "RateLimitExceeded",
            message = "The server asked this device to wait before syncing again",
            statusCode = TOO_MANY_REQUESTS,
            retryAfterMillis = remaining.inWholeMilliseconds,
        )
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429

        /** How long to wait when the server refuses without saying how long. */
        val DEFAULT_WAIT = 1.minutes
    }
}

/** The error code the sync layer uses for a full storage quota, whichever transport reported it. */
internal const val STORAGE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED"

private val QUOTA_ERRORS = setOf("QuotaExceeded", "StorageQuotaExceeded", STORAGE_QUOTA_EXCEEDED, "QUOTA_EXCEEDED")
private const val PAYLOAD_TOO_LARGE = 413
private const val INSUFFICIENT_STORAGE = 507

/**
 * Runs a personal-space call, reporting its failure in the sync layer's own terms so the existing
 * paths handle it: an expired token is a [CloudApiException] with status 401, which
 * [app.logdate.client.sync.SyncTokenRefresher] refreshes; rate limiting carries the server's
 * `Retry-After`; a full quota uses [STORAGE_QUOTA_EXCEEDED]; a failure before any response is a
 * `NETWORK_ERROR` that is retried. Swap conflicts and stale cursors pass through for the caller.
 */
internal suspend fun <T> spaceCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(translateSpaceFailure(error))
    }

internal fun translateSpaceFailure(error: Throwable): Throwable =
    when (error) {
        is DataHomeConflictException, is DataHomeCursorTooOldException -> {
            error
        }

        is XrpcProtocolException -> {
            error.toCloudFailure()
        }

        is DataHomeRateLimitedException, is DataHomeBusyException, is DataHomeQuotaExceededException -> {
            (error.cause as? XrpcProtocolException)?.toCloudFailure() ?: error
        }

        is XrpcTransportException -> {
            CloudApiException("NETWORK_ERROR", "The personal space couldn't be reached", cause = error)
        }

        is XrpcSerializationException -> {
            CloudApiException("INVALID_RESPONSE", "The server's personal space sent a response this version can't read", cause = error)
        }

        else -> {
            error
        }
    }

private fun XrpcProtocolException.toCloudFailure(): Throwable {
    val code = error
    val detail = errorMessage ?: "HTTP $statusCode"
    return when {
        code == "BlobTooLarge" -> {
            MediaTooLargeException("The server refused the file as too large: $detail")
        }

        code in QUOTA_ERRORS || statusCode == INSUFFICIENT_STORAGE -> {
            CloudApiException(STORAGE_QUOTA_EXCEEDED, "Cloud storage is full", statusCode, this)
        }

        statusCode == PAYLOAD_TOO_LARGE -> {
            CloudApiException(code ?: "RECORD_TOO_LARGE", detail, statusCode, this)
        }

        else -> {
            CloudApiException(
                errorCode = code ?: "HTTP_$statusCode",
                message = detail,
                statusCode = statusCode,
                cause = this,
                retryAfterMillis = retryAfterSeconds?.let { it * 1_000 },
            )
        }
    }
}

/** How a failed space call is reported in a [app.logdate.client.sync.SyncError]. */
internal fun Throwable.spaceErrorType(): SyncErrorType =
    when {
        this is MediaTooLargeException -> SyncErrorType.MEDIA_TOO_LARGE_ERROR
        this is IdentityKeyNotFoundException -> SyncErrorType.KEY_RECOVERY_ERROR
        this is SpaceUnavailableException -> SyncErrorType.SERVER_ERROR
        this is SpaceScopeChangedException -> SyncErrorType.AUTHENTICATION_ERROR
        this !is CloudApiException -> SyncErrorType.UNKNOWN_ERROR
        statusCode == 401 || statusCode == 403 -> SyncErrorType.AUTHENTICATION_ERROR
        errorCode == STORAGE_QUOTA_EXCEEDED -> SyncErrorType.STORAGE_ERROR
        errorCode == "NETWORK_ERROR" -> SyncErrorType.NETWORK_ERROR
        else -> SyncErrorType.SERVER_ERROR
    }

/** Whether retrying the same request can only fail the same way. */
internal fun Throwable.isPermanentSpaceFailure(): Boolean =
    this is SpaceRecordInvalidException ||
        (this is SpaceRecordMappingException && !retryable) ||
        this is MediaTooLargeException ||
        (this is CloudApiException && statusCode == 400)
