package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.apiError
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.ok
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectBeginRequest
import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectReceipt
import app.logdate.shared.model.AtprotoDisconnectRequest
import app.logdate.shared.model.AtprotoDisconnectResponse
import app.logdate.shared.model.AtprotoReconnectCompleteRequest
import app.logdate.shared.model.AtprotoReconnectStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import app.logdate.shared.model.PasskeyAssertionAuthenticatorResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

internal object AtprotoConnectionDocs {
    private val decision = "A".repeat(43)
    private val challenge = "B".repeat(43)
    private val connected = AtprotoConnectionSnapshot(AtprotoConnectionState.CONNECTED, 1, "2026-10-26T00:00:00Z", true, true)
    private val options = PasskeyAuthenticationOptions(challenge = challenge, rpId = "logdate.app", allowCredentials = listOf("AQ"))
    private val credential =
        PasskeyAssertionResponse(
            "AQ",
            "AQ",
            PasskeyAssertionAuthenticatorResponse(
                clientDataJSON = DocExamples.CLIENT_DATA_JSON,
                authenticatorData = DocExamples.AUTHENTICATOR_DATA,
                signature = DocExamples.SIGNATURE,
                userHandle = "",
            ),
        )

    val status: RouteConfig.() -> Unit = {
        bearerOperation(
            "getAtprotoConnection",
            ApiTags.ACCOUNT,
            "Check account connection",
            "Checks the account's external authorization. Provider availability and journal access are independent.",
        )
        response {
            ok("Current server evidence and disconnect eligibility.", connected)
            failures()
        }
    }

    val reconnectStart: RouteConfig.() -> Unit = {
        bearerOperation(
            "startAtprotoReconnect",
            ApiTags.ACCOUNT,
            "Reconnect account authorization",
            "Authorizes the current account's verified DID with its current PDS. This keeps the current LogDate session and journal open.",
        )
        request { jsonBody(AtprotoReconnectStartRequest(challenge), "The originating app's S256 challenge.") }
        response {
            ok("Open the pinned provider authorization URL.", BlueskyStartResponse(decision, "https://auth.example.com/authorize"))
            failures()
        }
    }

    val reconnectComplete: RouteConfig.() -> Unit = {
        bearerOperation(
            "completeAtprotoReconnect",
            ApiTags.ACCOUNT,
            "Finish account reconnection",
            "Consumes only an account and DID-bound reconnect proof. Returns actual authorization status without issuing a LogDate session or journal keys.",
        )
        request { jsonBody(AtprotoReconnectCompleteRequest(decision, "v".repeat(64)), "The browser return and originating app verifier.") }
        response {
            ok("Current connection evidence after the callback.", connected)
            failures()
            apiError(
                HttpStatusCode.Gone,
                ErrorCase(
                    "CONNECTION_RECONNECT_INVALID",
                    "Invalid, substituted, spent or expired reconnect proof.",
                    "This reconnect request is invalid or expired",
                ),
            )
        }
    }

    val begin: RouteConfig.() -> Unit = {
        bearerOperation(
            "beginAtprotoDisconnect",
            ApiTags.ACCOUNT,
            "Confirm disconnect with a passkey",
            "Creates a five-minute account, purpose and generation-bound decision. This does not disconnect or issue a LogDate session.",
        )
        request { jsonBody(AtprotoDisconnectBeginRequest(1), "The connection revision currently shown to the person.") }
        response {
            ok(
                "Fresh platform passkey options for this decision.",
                AtprotoDisconnectChallenge(decision, 1, options, "2026-10-26T00:05:00Z"),
            )
            failures()
        }
    }

    val disconnect: RouteConfig.() -> Unit = {
        bearerOperation(
            "disconnectAtprotoConnection",
            ApiTags.ACCOUNT,
            "Disconnect external authorization",
            "Confirms a fresh action passkey and ends only the observed grant. A completed receipt can be retried for 24 hours without " +
                "revoking a newer grant. Local completion is independent of remote confirmation. Journals and the LogDate session remain available.",
        )
        request { jsonBody(AtprotoDisconnectRequest(decision, challenge, credential), "The decision and its platform passkey assertion.") }
        response {
            ok(
                "The original completion receipt alongside current connection state.",
                AtprotoDisconnectResponse(
                    AtprotoDisconnectReceipt(decision, 2, "2026-10-26T00:01:00Z", false),
                    connected.copy(state = AtprotoConnectionState.DISCONNECTED, revision = 2, canDisconnect = false),
                ),
            )
            failures()
            apiError(
                HttpStatusCode.Gone,
                ErrorCase("CONNECTION_PROOF_EXPIRED", "The pending decision expired.", "The confirmation expired"),
            )
        }
    }

    private fun ResponsesConfig.failures() {
        apiError(
            HttpStatusCode.BadRequest,
            ErrorCase("VALIDATION_ERROR", "Invalid bounded request fields.", "Connection request is invalid"),
            ErrorCase("INVALID_REQUEST", "Malformed JSON or unsupported request content.", "Request body is invalid"),
        )
        apiError(
            HttpStatusCode.Unauthorized,
            ErrorCase("INVALID_TOKEN", "A normal LogDate account session is required.", "Access token is required"),
        )
        apiError(
            HttpStatusCode.Forbidden,
            ErrorCase("DEVICE_SCOPE_DENIED", "Sync-only devices cannot manage this connection.", "A linked device cannot do this"),
            ErrorCase(
                "CONNECTION_PROOF_INVALID",
                "The assertion is not a valid proof for this action.",
                "Confirm this action with a registered passkey",
            ),
        )
        apiError(
            HttpStatusCode.Conflict,
            ErrorCase(
                "CONNECTION_CHANGED",
                "A newer connection or account binding superseded the decision.",
                "The connection changed. Review its current status",
            ),
            ErrorCase(
                "CONNECTION_INELIGIBLE",
                "This account cannot mutate outbound authorization.",
                "This account does not use an external connection",
            ),
            ErrorCase(
                "LAST_SIGNIN_FACTOR",
                "Keep a usable independent sign-in method.",
                "Keep a usable sign-in method before disconnecting",
            ),
        )
        apiError(HttpStatusCode.PayloadTooLarge, ErrorCase("INVALID_REQUEST", "JSON exceeds 64 KiB.", "Request body is too large"))
        code(HttpStatusCode.TooManyRequests) {
            description = "RATE_LIMIT_EXCEEDED: more than ten requests per account and operation in one minute."
            header<Int>("Retry-After") { description = "Seconds before another attempt." }
            body<ApiErrorResponse> {
                example("Limited") {
                    value =
                        ApiErrorResponse(ApiError("RATE_LIMIT_EXCEEDED", "Too many connection requests"))
                }
            }
        }
        apiError(
            HttpStatusCode.ServiceUnavailable,
            ErrorCase(
                "CONNECTION_UNAVAILABLE",
                "Provider observation or persistence is unavailable.",
                "The connection could not be checked",
            ),
        )
    }
}
