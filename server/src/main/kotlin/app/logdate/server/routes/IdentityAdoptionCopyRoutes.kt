@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionCopyAccess
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionActionData
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionCopyCoverageData
import app.logdate.shared.model.identity.IdentityAdoptionCopyCoverageRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyCoverageResponse
import app.logdate.shared.model.identity.IdentityAdoptionCopyStartRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put

internal fun Route.identityAdoptionCopyRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    service: IdentityAdoptionCopyAccess?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean,
) {
    val context = IdentityAdoptionCopyContext(accounts, tokens, repository, service, metrics, enabled)
    post("/account/identity-adoption/{operationId}/read-source-media") { context.readSourceMedia(call) }
    post("/account/identity-adoption/{operationId}/begin-private-blob") { context.beginPrivateBlob(call) }
    put("/account/identity-adoption/{operationId}/private-blob") { context.writePrivateBlob(call) }
    post("/account/identity-adoption/{operationId}/discard-private-blob") { context.discardPrivateBlob(call) }
    post("/account/identity-adoption/{operationId}/verify-private-blob") { context.verifyPrivateBlob(call) }
    post("/account/identity-adoption/{operationId}/read-private-blob") { context.readPrivateBlob(call) }
    post("/account/identity-adoption/{operationId}/copy-challenge") { context.issueCopy(call) }
    post("/account/identity-adoption/{operationId}/verify-source-blob") { context.verifySourceBlob(call) }
    post("/account/identity-adoption/{operationId}/copy-record") { context.putCopyRecord(call) }
    post("/account/identity-adoption/{operationId}/read-copy") { context.readCopyRecord(call) }
    post("/account/identity-adoption/{operationId}/copy-mapping") { context.putCopyMapping(call) }
    post("/account/identity-adoption/{operationId}/read-mapping") { context.readCopyMapping(call) }
    post("/account/identity-adoption/{operationId}/copy-coverage") { context.readCopyCoverage(call) }
}

private suspend fun IdentityAdoptionCopyContext.issueCopy(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionCopyStartRequest>(metrics) ?: return@withOperation
        if (request.action !in
            setOf(
                IdentityAdoptionActionView.STAGE_RECORDS,
                IdentityAdoptionActionView.READ_STAGED,
                IdentityAdoptionActionView.BEGIN_BLOB,
            ) ||
            !request.bodyDigest.matches(Regex("[0-9a-f]{64}"))
        ) {
            return@withOperation invalid(call)
        }
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            service.issue(operation, IdentityAdoptionAction.valueOf(request.action.name), request.bodyDigest)
                ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(
            IdentityAdoptionActionResponse(
                true,
                IdentityAdoptionActionData(
                    view,
                    IdentityAdoptionActionChallengeView(
                        challenge.id.toString(),
                        challenge.nonce,
                        request.action,
                        challenge.bodyDigest,
                    ),
                ),
            ),
        )
    }

private suspend fun IdentityAdoptionCopyContext.readCopyCoverage(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val request = call.receiveAccountRequest<IdentityAdoptionCopyCoverageRequest>(metrics) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.READ_STAGED, request.signature)
                ?: return@withOperation invalid(call)
        val coverage = service.coverage(operation, challenge, request.signature) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionCopyCoverageResponse(true, IdentityAdoptionCopyCoverageData(view, coverage.wire())))
    }
