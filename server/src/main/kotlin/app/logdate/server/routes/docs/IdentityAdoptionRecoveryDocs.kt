package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.apiError
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.ok
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.identity.IdentityAdoptionBindingView
import app.logdate.shared.model.identity.IdentityAdoptionOperationView
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionReceiptView
import app.logdate.shared.model.identity.IdentityAdoptionStatusData
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

internal object IdentityAdoptionRecoveryDocs {
    private const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
    private const val OPERATION = "22222222-2222-4222-8222-222222222222"
    private const val SOURCE = "did:web:source.example"
    private const val TARGET = "did:web:target.example"
    private val example =
        IdentityAdoptionStatusResponse(
            true,
            IdentityAdoptionStatusData(
                IdentityAdoptionOperationView(
                    OPERATION,
                    IdentityAdoptionBindingView(ACCOUNT, SOURCE, TARGET, "authorizing-device", 0),
                    6,
                    IdentityAdoptionPhaseView.COMMITTED,
                    "2026-01-01T00:02:00Z",
                ),
                IdentityAdoptionReceiptView(OPERATION, ACCOUNT, SOURCE, TARGET, 1, "2026-01-01T00:01:00Z"),
            ),
        )

    val current: RouteConfig.() -> Unit = {
        bearerOperation(
            "getCurrentIdentityAdoption",
            ApiTags.ACCOUNT,
            "Recover a committed identity change",
            "Returns the durable adoption receipt matching the authenticated account's current DID and identity epoch. " +
                "Other signed-in devices can discover the operation without a local initiating flow. " +
                "Recovery remains available when new adoption is disabled. " +
                "It does not renew a hold, issue tokens or change journal authority. " +
                "Clients must verify the receipt and reconcile local pending edits before switching their private sync scope.",
        )
        response {
            ok("The current committed operation and its original receipt. Responses are not cached.", example)
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(
                HttpStatusCode.Forbidden,
                ErrorCase(
                    "DEVICE_SCOPE_DENIED",
                    "Sync-only device sessions cannot read account authority.",
                    "A linked device cannot do this",
                ),
                ErrorCase("ACCOUNT_UNAVAILABLE", "The account is moderated or unavailable.", "This account is unavailable."),
            )
            apiError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "IDENTITY_ADOPTION_NOT_FOUND",
                    "No committed receipt matches the current account identity.",
                    "This identity change is unavailable.",
                ),
            )
            code(HttpStatusCode.TooManyRequests) {
                description = "RATE_LIMIT_EXCEEDED: more than ten identity-status requests per account in one minute."
                header<Int>("Retry-After") { description = "Seconds before another status request." }
                body<ApiErrorResponse> {
                    example("Limited") {
                        value = ApiErrorResponse(ApiError("RATE_LIMIT_EXCEEDED", "Too many identity requests."))
                    }
                }
            }
            apiError(
                HttpStatusCode.ServiceUnavailable,
                ErrorCase(
                    "IDENTITY_ADOPTION_UNAVAILABLE",
                    "The receipt could not be read consistently.",
                    "The identity change could not be checked.",
                ),
            )
        }
    }
}
