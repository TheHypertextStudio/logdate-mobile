package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.shared.model.identity.IdentityAdoptionCopiedData
import app.logdate.shared.model.identity.IdentityAdoptionCopiedResponse
import app.logdate.shared.model.identity.IdentityAdoptionCopyReadRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyRecordRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun IdentityAdoptionCopyContext.putCopyRecord(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionCopyRecordRequest>(metrics, 6 * 1024 * 1024) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.STAGE_RECORDS, request.signature)
                ?: return@withOperation invalid(call)
        val copy = service.put(operation, challenge, request.signature, request.copy.domain()) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionCopiedResponse(true, IdentityAdoptionCopiedData(view, copy.record.wire(), copy.origin.wire())))
    }

internal suspend fun IdentityAdoptionCopyContext.readCopyRecord(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionCopyReadRequest>(metrics, 256 * 1024) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.READ_STAGED, request.signature)
                ?: return@withOperation invalid(call)
        val copy = service.read(operation, challenge, request.signature, request.domain()) ?: return@withOperation missing(call)
        if (copy.origin.sourceDigest != request.sourceDigest) return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionCopiedResponse(true, IdentityAdoptionCopiedData(view, copy.record.wire(), copy.origin.wire())))
    }
