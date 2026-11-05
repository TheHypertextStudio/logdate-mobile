package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingData
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingResponse
import app.logdate.shared.model.identity.IdentityAdoptionMappingReadRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun IdentityAdoptionCopyContext.putCopyMapping(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionCopyMappingRequest>(metrics, 256 * 1024) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.STAGE_RECORDS, request.signature)
                ?: return@withOperation invalid(call)
        val mapping =
            service.putMapping(operation, challenge, request.signature, request.mapping.domain()) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionCopyMappingResponse(true, IdentityAdoptionCopyMappingData(view, mapping.wire())))
    }

internal suspend fun IdentityAdoptionCopyContext.readCopyMapping(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionMappingReadRequest>(metrics, 256 * 1024) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.READ_STAGED, request.signature)
                ?: return@withOperation invalid(call)
        val mapping =
            service.readMapping(operation, challenge, request.signature, request.source.domain()) ?: return@withOperation missing(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionCopyMappingResponse(true, IdentityAdoptionCopyMappingData(view, mapping.wire())))
    }
