package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.shared.model.identity.IdentityAdoptionMediaData
import app.logdate.shared.model.identity.IdentityAdoptionMediaResponse
import app.logdate.shared.model.identity.IdentityAdoptionMediaReuseRequest
import app.logdate.shared.model.identity.IdentityAdoptionMediaVerificationView
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** Reuses the retained immutable source object; it creates no target alias or additional storage charge. */
internal suspend fun IdentityAdoptionCopyContext.verifySourceBlob(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionMediaReuseRequest>(metrics) ?: return@withOperation
        if (!IdentityAdoptionVerifiedBlob.acceptsCid(request.cid)) return@withOperation invalid(call)
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.BEGIN_BLOB, request.signature)
                ?: return@withOperation invalid(call)
        val media = service.verifyMedia(operation, challenge, request.signature, request.cid) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(
            IdentityAdoptionMediaResponse(
                true,
                IdentityAdoptionMediaData(
                    view,
                    IdentityAdoptionMediaVerificationView(
                        media.operationId,
                        media.admissionId,
                        media.generation,
                        media.cid,
                        media.size,
                        media.mimeType,
                    ),
                ),
            ),
        )
    }
