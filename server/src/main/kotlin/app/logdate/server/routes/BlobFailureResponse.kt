package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import io.ktor.http.HttpStatusCode
import studio.hypertext.atproto.pds.PdsErrorResponse

internal data class BlobFailureResponse(
    val status: HttpStatusCode,
    val body: PdsErrorResponse,
)

internal fun blobFailureResponse(error: Throwable?): BlobFailureResponse =
    when (error) {
        is IdentityWriteRefusedException ->
            when (error.outcome) {
                IdentityFenceOutcome.HELD ->
                    BlobFailureResponse(
                        HttpStatusCode.Conflict,
                        PdsErrorResponse("IdentityAdoptionInProgress", "Your account is being updated"),
                    )
                IdentityFenceOutcome.CHANGED ->
                    BlobFailureResponse(
                        HttpStatusCode.Conflict,
                        PdsErrorResponse("IdentityChanged", "Sign in to continue"),
                    )
                IdentityFenceOutcome.DELETED ->
                    BlobFailureResponse(
                        HttpStatusCode.Unauthorized,
                        PdsErrorResponse("AccountUnavailable", "This account is unavailable"),
                    )
                IdentityFenceOutcome.ALLOWED -> internalBlobFailure()
            }
        is IllegalArgumentException ->
            BlobFailureResponse(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", "Invalid blob request"),
            )
        else -> internalBlobFailure()
    }

private fun internalBlobFailure(): BlobFailureResponse =
    BlobFailureResponse(HttpStatusCode.InternalServerError, PdsErrorResponse("InternalServerError", "Blob request failed"))
