package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.PlcPublicationConsent
import app.logdate.server.identity.PlcPublicationHistory
import app.logdate.server.routes.docs.PlcPublicationDocs
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun Route.plcPublicationRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    consent: PlcPublicationConsent?,
) {
    route("/identity/publication") {
        get("", PlcPublicationDocs.status) {
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@get
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val service = consent ?: return@get call.publicationDisabled()
            val status = service.status(account.id) ?: return@get call.publicationUnavailable()
            call.respond(PlcPublicationStatusResponse(status.username, status.state.wireValue))
        }
        post("/review", PlcPublicationDocs.review) {
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@post
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val service = consent ?: return@post call.publicationDisabled()
            val prompt = service.begin(account.id) ?: return@post call.publicationUnavailable()
            call.respond(PlcPublicationReviewResponse(prompt.reviewId.toString(), prompt.username, prompt.history, prompt.passkey))
        }
        post("/confirm", PlcPublicationDocs.confirm) {
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@post
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val service = consent ?: return@post call.publicationDisabled()
            val request = call.receive<PlcPublicationConfirmRequest>()
            val id = Uuid.parseOrNull(request.reviewId)
            if (id == null || !service.authorize(account.id, id, request.challenge, request.credential)) {
                return@post call.respondIdentityApiError(
                    HttpStatusCode.Forbidden,
                    "PUBLICATION_CONFIRMATION_FAILED",
                    "Couldn’t confirm this change.",
                )
            }
            call.respond(HttpStatusCode.Accepted, PlcPublicationAcceptedResponse())
        }
    }
}

private suspend fun ApplicationCall.publicationDisabled() =
    respondIdentityApiError(
        HttpStatusCode.NotImplemented,
        "PUBLICATION_UNAVAILABLE",
        "Setup for other apps is unavailable.",
    )

private suspend fun ApplicationCall.publicationUnavailable() =
    respondIdentityApiError(
        HttpStatusCode.Conflict,
        "PUBLICATION_UNAVAILABLE",
        "This account can’t be set up for other apps right now.",
    )

@Serializable
internal data class PlcPublicationStatusResponse(
    val username: String,
    val state: String,
)

@Serializable
internal data class PlcPublicationReviewResponse(
    val reviewId: String,
    val username: String,
    val history: PlcPublicationHistory,
    val passkey: PasskeyAuthenticationOptions,
)

@Serializable
internal data class PlcPublicationConfirmRequest(
    val reviewId: String,
    val challenge: String,
    val credential: PasskeyAuthenticationResponse,
)

@Serializable
internal data class PlcPublicationAcceptedResponse(
    val state: String = "pending",
)
