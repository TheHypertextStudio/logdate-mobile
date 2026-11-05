package app.logdate.server.routes

import app.logdate.server.audit.AuditCategory
import app.logdate.server.audit.AuditLogger
import app.logdate.server.entitlements.QuotaReason
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.space.SpaceQuotaExceededException
import app.logdate.server.transfer.TransferLimits
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import studio.hypertext.atproto.pds.PdsErrorResponse

private const val RATE_LIMIT_EXCEEDED = "RateLimitExceeded"
private const val QUOTA_EXCEEDED = "QuotaExceeded"
internal const val SERVER_BUSY = "ServerBusy"

/** The error a space upload without a `Content-Length` header answers. */
internal const val LENGTH_REQUIRED = "LengthRequired"

/**
 * How many requests one account may make to the `app.logdate.space.*` methods. Each budget is
 * counted on its own, so a device downloading its space still has its whole write budget, and a
 * limit is never shared between accounts.
 *
 * The defaults let a device sync as fast as one connection sends requests: a first sync writes or
 * reads one record per request, thousands of them, and a phone sending them one after another
 * stays well under 100 a second. They stop a runaway loop or a stolen token from flooding the
 * server, not a device catching up.
 *
 * @property recordWrites `putRecord` and `deleteRecord`.
 * @property reads `getRecord`, `listRepoOps` and `getBlob`.
 * @property blobUploads `uploadBlob`, whose requests each carry up to a whole file.
 */
data class SpaceRateLimits(
    val recordWrites: RateLimitPolicy = RateLimitPolicy(maxRequests = 6_000, windowSeconds = 60),
    val reads: RateLimitPolicy = RateLimitPolicy(maxRequests = 6_000, windowSeconds = 60),
    val blobUploads: RateLimitPolicy = RateLimitPolicy(maxRequests = 1_200, windowSeconds = 60),
)

/** Which of [SpaceRateLimits]' budgets a method spends. */
internal enum class SpaceBudget(
    private val keyPrefix: String,
) {
    RECORD_WRITES("space-write"),
    READS("space-read"),
    BLOB_UPLOADS("space-upload"),
    ;

    fun policy(limits: SpaceRateLimits): RateLimitPolicy =
        when (this) {
            RECORD_WRITES -> limits.recordWrites
            READS -> limits.reads
            BLOB_UPLOADS -> limits.blobUploads
        }

    fun key(accountId: String): String = "$keyPrefix:$accountId"
}

/**
 * Counts one request against [accountId]'s [budget]. When the budget is spent, answers
 * `429 RateLimitExceeded` with a `Retry-After` header giving the seconds until a request fits again,
 * and returns false.
 */
internal suspend fun ApplicationCall.spendSpaceBudget(
    limiter: FixedWindowRateLimiter,
    limits: SpaceRateLimits,
    budget: SpaceBudget,
    accountId: String,
): Boolean {
    val key = budget.key(accountId)
    val policy = budget.policy(limits)
    if (limiter.allow(key, policy)) return true
    val retryAfter = limiter.retryAfterSeconds(key).coerceAtLeast(1)
    AuditLogger.emit(
        AuditCategory.SYNC_RATE_LIMITED,
        mapOf(
            "key" to key,
            "retryAfterSeconds" to retryAfter.toString(),
            "maxRequests" to policy.maxRequests.toString(),
            "windowSeconds" to policy.windowSeconds.toString(),
        ),
    )
    response.header(HttpHeaders.RetryAfter, retryAfter.toString())
    respond(
        HttpStatusCode.TooManyRequests,
        PdsErrorResponse(RATE_LIMIT_EXCEEDED, "Too many requests. Try again in $retryAfter seconds."),
    )
    return false
}

/** Answers `402 QuotaExceeded`: storing what was sent would take the account past its storage quota. */
internal suspend fun ApplicationCall.respondSpaceQuotaExceeded(refused: SpaceQuotaExceededException) {
    AuditLogger.emit(
        AuditCategory.SYNC_QUOTA_EXCEEDED,
        mapOf(
            "reason" to QuotaReason.STORAGE_BYTES.name,
            "limit" to refused.limitBytes.toString(),
            "current" to refused.usedBytes.toString(),
        ),
    )
    respond(
        HttpStatusCode.PaymentRequired,
        PdsErrorResponse(QUOTA_EXCEEDED, "Storage quota exceeded: ${refused.usedBytes} of ${refused.limitBytes} bytes in use."),
    )
}

/** Answers `503 ServerBusy`: this instance is running as many file transfers as it can hold. */
internal suspend fun ApplicationCall.respondServerBusy(
    limits: TransferLimits,
    message: String = "The server is busy with other file transfers. Try again in ${limits.retryAfterSeconds} seconds.",
) {
    response.header(HttpHeaders.RetryAfter, limits.retryAfterSeconds.toString())
    respond(
        HttpStatusCode.ServiceUnavailable,
        PdsErrorResponse(SERVER_BUSY, message),
    )
}

/** Answers `411 LengthRequired`: an upload must say how long it is before the server holds room for it. */
internal suspend fun ApplicationCall.respondLengthRequired() =
    respond(HttpStatusCode.LengthRequired, PdsErrorResponse(LENGTH_REQUIRED, "Send the file's length in a Content-Length header"))
