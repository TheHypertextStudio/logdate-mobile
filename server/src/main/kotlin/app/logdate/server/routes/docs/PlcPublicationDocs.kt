package app.logdate.server.routes.docs

import app.logdate.server.identity.PlcPublicationHistory
import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.PlcPublicationAcceptedResponse
import app.logdate.server.routes.PlcPublicationConfirmRequest
import app.logdate.server.routes.PlcPublicationReviewResponse
import app.logdate.server.routes.PlcPublicationStatusResponse
import app.logdate.server.routes.apiError
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.ok
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

internal object PlcPublicationDocs {
    private const val USERNAME = "alex.logdate.app"
    private const val REVIEW_ID = "f240a88c-c537-4688-868b-98bb11cb1849"

    val status: RouteConfig.() -> Unit = {
        bearerOperation(
            "getPlcPublicationStatus",
            ApiTags.IDENTITY,
            "Check username visibility",
            "Return the account's current username and whether other apps can find it.",
        )
        response {
            ok("The account's username and visibility.", PlcPublicationStatusResponse(USERNAME, "private"))
            accountErrors()
        }
    }

    val review: RouteConfig.() -> Unit = {
        bearerOperation(
            "beginPlcPublicationReview",
            ApiTags.IDENTITY,
            "Review username publication",
            "Save the exact publication review and issue a challenge for the account's passkey.",
        )
        response {
            ok(
                "The reviewed username, public history summary, and passkey challenge.",
                PlcPublicationReviewResponse(
                    REVIEW_ID,
                    USERNAME,
                    PlcPublicationHistory(updates = 1, signingChanges = 0, recoveryChanges = 1),
                    PasskeyAuthenticationOptions("challenge", "logdate.app"),
                ),
            )
            accountErrors()
        }
    }

    val confirm: RouteConfig.() -> Unit = {
        bearerOperation(
            "confirmPlcPublicationReview",
            ApiTags.IDENTITY,
            "Confirm username publication",
            "Authorize the saved review with a fresh passkey assertion for that review.",
        )
        request {
            jsonBody(
                PlcPublicationConfirmRequest(
                    REVIEW_ID,
                    "challenge",
                    PasskeyAuthenticationResponse("id", "id", AuthenticatorAssertionResponse("data", "data", "signature", null)),
                ),
                "The review ID and the passkey assertion for its challenge.",
            )
        }
        response {
            code(HttpStatusCode.Accepted) {
                description = "The confirmation was accepted. Use status to observe the outcome."
                body<PlcPublicationAcceptedResponse> { example("Pending") { value = PlcPublicationAcceptedResponse() } }
            }
            accountErrors()
            apiError(
                HttpStatusCode.Forbidden,
                ErrorCase(
                    "PUBLICATION_CONFIRMATION_FAILED",
                    "The review or passkey assertion did not verify.",
                    "Couldn’t confirm this change.",
                ),
            )
        }
    }

    private fun ResponsesConfig.accountErrors() {
        bearerUnauthorized(ErrorEnvelope.API)
        apiError(
            HttpStatusCode.NotImplemented,
            ErrorCase("PUBLICATION_UNAVAILABLE", "Publication is disabled on this server.", "Making usernames public is unavailable."),
        )
        apiError(HttpStatusCode.NotFound, ErrorCase("ACCOUNT_NOT_FOUND", "The account does not exist.", "Account not found"))
        apiError(
            HttpStatusCode.Conflict,
            ErrorCase(
                "PUBLICATION_UNAVAILABLE",
                "The account is not ready for publication.",
                "This username can’t be made public right now.",
            ),
        )
    }
}
