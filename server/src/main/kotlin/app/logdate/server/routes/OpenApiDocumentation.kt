package app.logdate.server.routes

import app.logdate.server.openapi.ApiLimits
import app.logdate.server.openapi.renderApiText
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.responses.SimpleErrorResponse
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.ResponseConfig
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.swagger.v3.oas.models.media.Schema
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.pds.OAuthErrorResponse
import studio.hypertext.atproto.pds.PdsErrorResponse

/** The `{"error": "..."}` shape the quota and transcription endpoints answer with. */
@Serializable
internal data class MessageErrorResponse(
    val error: String,
)

/** The `{"ok": true}` acknowledgement returned by logout. */
@Serializable
internal data class OkResponse(
    val ok: Boolean,
)

/** The `{"success": true}` acknowledgement returned when a restore credential is registered. */
@Serializable
internal data class SuccessResponse(
    val success: Boolean,
)

/** Which error envelope a family of endpoints answers with. See the overview's Errors section. */
internal enum class ErrorEnvelope { API, SYNC, PDS, OAUTH, MESSAGE }

/** One failure an endpoint can answer with: the machine-readable code, what it means, and the message the server sends. */
internal data class ErrorCase(
    val code: String,
    val description: String,
    val message: String,
)

private fun RouteConfig.operation(
    id: String,
    tag: String,
    summary: String,
    description: String,
) {
    operationId = id
    tags = listOf(tag)
    this.summary = summary
    this.description = renderApiText(description.trimIndent())
}

internal fun RouteConfig.publicOperation(
    id: String,
    tag: String,
    summary: String,
    description: String,
) = operation(id, tag, summary, description)

internal fun RouteConfig.bearerOperation(
    id: String,
    tag: String,
    summary: String,
    description: String,
) {
    operation(id, tag, summary, description)
    protected = true
    securitySchemeNames = listOf("bearerAuth")
}

/**
 * An operation that accepts either a LogDate bearer token or a DPoP-bound OAuth token
 * (`Authorization: DPoP <token>` together with a `DPoP` proof header).
 */
internal fun RouteConfig.bearerOrDpopOperation(
    id: String,
    tag: String,
    summary: String,
    description: String,
) {
    operation(id, tag, summary, description)
    protected = true
    securitySchemeNames = listOf("bearerAuth", "dpopProof")
}

// ---- Requests -------------------------------------------------------------------------------

/**
 * `type: string, format: binary`. The kotlinx generator would describe a `ByteArray` as a JSON
 * array of bytes, which is not what a file upload or download looks like on the wire.
 */
internal fun binarySchema(): Schema<*> =
    Schema<Any>().apply {
        types = setOf("string")
        format = "binary"
    }

internal inline fun <reified T> RequestConfig.jsonBody(
    example: T,
    description: String,
) {
    body<T> {
        this.description = renderApiText(description.trimIndent())
        required = true
        mediaTypes(ContentType.Application.Json)
        example("Example") { value = example }
    }
}

/** The two query parameters every change feed takes. */
internal fun RequestConfig.sinceAndLimit(
    defaultLimit: Int,
    maxLimit: Int?,
) {
    queryParameter<Long>("since") {
        description =
            if (maxLimit != null) {
                "Exclusive version cursor. Send `0` (or omit) for everything, then the `lastTimestamp` from the previous " +
                    "page. Non-numeric values answer `400 INVALID_PARAMETER`."
            } else {
                "Exclusive version cursor. Send `0` (or omit) for everything, then the highest `serverVersion` from the " +
                    "previous page. Non-numeric values are treated as `0`, not rejected."
            }
        required = false
        example("First page") { value = 0L }
    }
    queryParameter<Int>("limit") {
        description =
            if (maxLimit != null) {
                "Maximum records per list in the page (applied to `changes` and `deletions` separately). Defaults to " +
                    "$defaultLimit; values outside 1…$maxLimit are clamped. Non-numeric values fall back to the default."
            } else {
                "Maximum drafts in the page. Defaults to $defaultLimit and is not clamped."
            }
        required = false
        example("Default") { value = defaultLimit }
    }
}

// ---- Success responses ----------------------------------------------------------------------

internal inline fun <reified T> ResponsesConfig.ok(
    description: String,
    example: T,
) {
    code(HttpStatusCode.OK) {
        this.description = renderApiText(description.trimIndent())
        body<T> { example("Example") { value = example } }
    }
}

internal inline fun <reified T> ResponsesConfig.created(
    description: String,
    example: T,
    locationTemplate: String,
) {
    code(HttpStatusCode.Created) {
        this.description = renderApiText(description.trimIndent())
        header<String>("Location") { this.description = "Path of the new resource, for example `$locationTemplate`." }
        body<T> { example("Example") { value = example } }
    }
}

internal fun ResponsesConfig.noContent(description: String) {
    code(HttpStatusCode.NoContent) { this.description = renderApiText(description.trimIndent()) }
}

// ---- Error responses ------------------------------------------------------------------------

private fun ResponsesConfig.errorResponse(
    status: HttpStatusCode,
    envelope: ErrorEnvelope,
    cases: List<ErrorCase>,
    details: Map<String, String> = emptyMap(),
    retryAfterHeader: Boolean = false,
    headers: ResponseConfig.() -> Unit = {},
) {
    require(cases.isNotEmpty()) { "an error response needs at least one case" }
    require(getResponses().none { it.statusCode == status.value.toString() }) {
        "$status is already documented on this operation; pass every case in one call, a second call would replace the first"
    }
    code(status) {
        description =
            buildString {
                cases.forEach { case ->
                    // The bare `{"error"}` envelope carries no code, so its bullets quote the message.
                    append("- `")
                        .append(if (envelope == ErrorEnvelope.MESSAGE) case.message else case.code)
                        .append("` — ")
                        .append(case.description)
                        .append('\n')
                }
            }.trim()
        if (retryAfterHeader) {
            header<Int>("Retry-After") { this.description = "Seconds to wait before trying again." }
        }
        headers()
        when (envelope) {
            ErrorEnvelope.API -> {
                body<ApiErrorResponse> {
                    cases.forEach { case -> example(case.code) { value = ApiErrorResponse(ApiError(case.code, case.message)) } }
                }
            }

            ErrorEnvelope.SYNC -> {
                body<SimpleErrorResponse> {
                    cases.forEach { case ->
                        example(case.code) { value = SimpleErrorResponse(case.code, case.message, details) }
                    }
                }
            }

            ErrorEnvelope.PDS -> {
                body<PdsErrorResponse> {
                    cases.forEach { case -> example(case.code) { value = PdsErrorResponse(case.code, case.message) } }
                }
            }

            ErrorEnvelope.OAUTH -> {
                body<OAuthErrorResponse> {
                    cases.forEach { case -> example(case.code) { value = OAuthErrorResponse(case.code, case.message) } }
                }
            }

            ErrorEnvelope.MESSAGE -> {
                body<MessageErrorResponse> {
                    cases.forEach { case -> example(case.code) { value = MessageErrorResponse(case.message) } }
                }
            }
        }
    }
}

/** An error in the Authentication / Account / Identity envelope. All [cases] share one [status]. */
internal fun ResponsesConfig.apiError(
    status: HttpStatusCode,
    vararg cases: ErrorCase,
) = errorResponse(status, ErrorEnvelope.API, cases.toList())

/** An error in the sync envelope; [details] is the example's `details` map. */
internal fun ResponsesConfig.syncError(
    status: HttpStatusCode,
    vararg cases: ErrorCase,
    details: Map<String, String> = emptyMap(),
) = errorResponse(status, ErrorEnvelope.SYNC, cases.toList(), details = details)

/** An error in the XRPC envelope; [ErrorCase.code] is the AT Protocol error name. */
internal fun ResponsesConfig.pdsError(
    status: HttpStatusCode,
    vararg cases: ErrorCase,
    headers: ResponseConfig.() -> Unit = {},
) = errorResponse(status, ErrorEnvelope.PDS, cases.toList(), headers = headers)

/** An error in the OAuth envelope; [ErrorCase.code] is the RFC 6749 `error` value. */
internal fun ResponsesConfig.oauthError(
    status: HttpStatusCode,
    vararg cases: ErrorCase,
    headers: ResponseConfig.() -> Unit = {},
) = errorResponse(status, ErrorEnvelope.OAUTH, cases.toList(), headers = headers)

/** The `DPoP-Nonce` header a DPoP endpoint sets on the response that asks for a fresh nonce. */
internal val dpopNonceHeader: ResponseConfig.() -> Unit = {
    header<String>("DPoP-Nonce") { description = "The nonce to put in your next DPoP proof." }
}

/** An error in the bare `{"error": "..."}` envelope. */
internal fun ResponsesConfig.messageError(
    status: HttpStatusCode,
    vararg cases: ErrorCase,
) = errorResponse(status, ErrorEnvelope.MESSAGE, cases.toList())

/** The `401` every bearer-protected endpoint can answer, phrased for the envelope its family uses. */
internal fun ResponsesConfig.bearerUnauthorized(envelope: ErrorEnvelope) {
    val advice = "Refresh the access token and retry once; if that also fails, sign in again."
    when (envelope) {
        ErrorEnvelope.API -> {
            apiError(
                HttpStatusCode.Unauthorized,
                ErrorCase("INVALID_TOKEN", "The access token is missing, malformed or expired. $advice", "Invalid or expired access token"),
            )
        }

        ErrorEnvelope.SYNC -> {
            syncError(
                HttpStatusCode.Unauthorized,
                ErrorCase(
                    "UNAUTHORIZED",
                    "The access token is missing, malformed or expired. $advice",
                    "Missing or invalid Authorization header",
                ),
            )
        }

        ErrorEnvelope.PDS -> {
            pdsError(
                HttpStatusCode.Unauthorized,
                ErrorCase("AuthRequired", "No `Authorization: Bearer` header was sent.", "Missing bearer token"),
                ErrorCase(
                    "InvalidToken",
                    "The token is malformed, expired or was issued for another account. $advice",
                    "Authentication failed",
                ),
            )
        }

        ErrorEnvelope.MESSAGE -> {
            messageError(
                HttpStatusCode.Unauthorized,
                ErrorCase(
                    "missing_or_invalid_token",
                    "The access token is missing, malformed or expired. $advice",
                    "missing or invalid Authorization header",
                ),
            )
        }

        ErrorEnvelope.OAUTH -> {
            oauthError(
                HttpStatusCode.Unauthorized,
                ErrorCase(
                    "login_required",
                    "The person is not signed in to LogDate. Sign them in, then repeat the request.",
                    "LogDate bearer authentication is required",
                ),
            )
        }
    }
}

/**
 * The `429` a rate-limited endpoint answers; the numbers come from the policy the handler enforces.
 * The auth endpoints count per IP address and send no `Retry-After`; every other family counts
 * per account and does.
 */
internal fun ResponsesConfig.rateLimited(
    policy: RateLimitPolicy,
    envelope: ErrorEnvelope,
) {
    val perIp = envelope == ErrorEnvelope.API
    val retryAfterHeader = !perIp
    val scope = if (perIp) "IP address" else "account"
    val limit = ApiLimits.describe(policy)
    val wait =
        if (retryAfterHeader) {
            "Wait the number of seconds in `Retry-After`, then try again."
        } else {
            "Wait for the ${ApiLimits.windowName(policy)} window to pass, then try again."
        }
    val message =
        when (envelope) {
            ErrorEnvelope.SYNC -> "Too many uploads. Try again in 42 seconds."
            ErrorEnvelope.MESSAGE -> "cloud transcription session rate limit exceeded"
            else -> "Too many requests. Please retry later."
        }
    val case = ErrorCase("RATE_LIMIT_EXCEEDED", "More than $limit from one $scope. $wait", message)
    val details = if (retryAfterHeader) mapOf("retryAfterSeconds" to "42") else emptyMap()
    errorResponse(HttpStatusCode.TooManyRequests, envelope, listOf(case), details = details, retryAfterHeader = retryAfterHeader)
}

/** The `402` an upload answers when the account's plan has no room for it. */
internal fun ResponsesConfig.quotaExceeded() =
    errorResponse(
        HttpStatusCode.PaymentRequired,
        ErrorEnvelope.SYNC,
        listOf(
            ErrorCase(
                "QUOTA_EXCEEDED",
                "The upload would exceed the account's plan. `details.reason` is `STORAGE_BYTES` or `BACKUP_COUNT`; " +
                    "`details.limit` and `details.current` give the numbers. Show the person their usage and do not retry.",
                "Storage quota exceeded: 5368709120 of 5368709120 bytes in use.",
            ),
        ),
        details = mapOf("reason" to "STORAGE_BYTES", "limit" to "5368709120", "current" to "5368709120"),
    )
