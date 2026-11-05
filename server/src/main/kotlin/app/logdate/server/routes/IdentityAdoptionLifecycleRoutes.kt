@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_EMPTY_BODY_DIGEST
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionLifecycle
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionStatus
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.identity.IdentityAdoptionAbandonRequest
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionActionData
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionLifecycleRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

internal fun Route.identityAdoptionLifecycleRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    lifecycle: IdentityAdoptionLifecycle?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean,
) {
    val context = IdentityAdoptionLifecycleContext(accounts, tokens, repository, lifecycle, metrics, enabled)
    post("/account/identity-adoption/{operationId}/action-challenge") { context.issue(call) }
    post("/account/identity-adoption/{operationId}/lifecycle") { context.apply(call) }
    post("/account/identity-adoption/{operationId}/abandon") { context.abandon(call) }
}

private class IdentityAdoptionLifecycleContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val repository: IdentityAdoptionRepository?,
    private val lifecycle: IdentityAdoptionLifecycle?,
    private val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()

    suspend fun issue(call: ApplicationCall) =
        withOperation(call) { account, operation, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionActionStartRequest>(metrics) ?: return@withOperation
            if (request.action !in ACTIONS) return@withOperation invalid(call)
            if (!matches(account, operation, request.expected) || operation.phase.name != request.phase.name) {
                return@withOperation changed(call)
            }
            val action = IdentityAdoptionAction.valueOf(request.action.name)
            val challenge = service.issue(operation, action) ?: return@withOperation changed(call)
            call.respond(
                IdentityAdoptionActionResponse(
                    true,
                    IdentityAdoptionActionData(
                        IdentityAdoptionStatus(operation, null).response().data.operation,
                        IdentityAdoptionActionChallengeView(
                            challenge.id.toString(),
                            challenge.nonce,
                            IdentityAdoptionActionView.valueOf(challenge.action.name),
                            challenge.bodyDigest,
                        ),
                    ),
                ),
            )
        }

    suspend fun apply(call: ApplicationCall) =
        withOperation(call) { account, operation, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionLifecycleRequest>(metrics) ?: return@withOperation
            val raw = request.challenge
            val id = raw.id.takeIf { it.length == 36 }?.let(Uuid::parseOrNull)
            if (id == null ||
                raw.action !in ACTIONS ||
                raw.bodyDigest != IDENTITY_ADOPTION_EMPTY_BODY_DIGEST ||
                !raw.nonce.matches(Regex("[A-Za-z0-9_-]{43}")) ||
                !request.signature.matches(Regex("[A-Za-z0-9_-]{86}"))
            ) {
                return@withOperation invalid(call)
            }
            if (!matches(account, operation, request.expected) || operation.phase.name != request.phase.name) {
                return@withOperation changed(call)
            }
            val challenge = IdentityAdoptionActionChallenge(id, raw.nonce, IdentityAdoptionAction.valueOf(raw.action.name), raw.bodyDigest)
            respond(call, service.apply(operation, challenge, request.signature))
        }

    suspend fun abandon(call: ApplicationCall) =
        withOperation(call) { account, operation, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionAbandonRequest>(metrics) ?: return@withOperation
            if (!matches(account, operation, request.expected, replayAbandon = true)) return@withOperation changed(call)
            respond(call, service.abandon(operation))
        }

    private suspend fun respond(
        call: ApplicationCall,
        result: IdentityAdoptionOutcome<IdentityAdoptionOperation>,
    ) {
        when (result) {
            is IdentityAdoptionOutcome.Accepted -> {
                val status = repository?.status(result.value.binding.accountId, result.value.id)
                if (status == null || status.operation != result.value) return changed(call)
                call.respond(status.response())
            }
            is IdentityAdoptionOutcome.Refused -> changed(call)
        }
    }

    private suspend fun withOperation(
        call: ApplicationCall,
        block: suspend (Account, IdentityAdoptionOperation, IdentityAdoptionLifecycle) -> Unit,
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!enabled || repository == null || lifecycle == null) {
            return call.respondApiError(HttpStatusCode.NotFound, "FEATURE_DISABLED", "Identity changes are unavailable.", metrics)
        }
        try {
            val account = resolveAuthenticatedAccount(call, accounts, tokens, metrics) ?: return
            if (account.moderation != null) {
                return call.respondApiError(HttpStatusCode.Forbidden, "ACCOUNT_UNAVAILABLE", "This account is unavailable.", metrics)
            }
            if (!limiter.allow("identity-adoption:lifecycle:${account.id}", SIGNIN_RATE_LIMIT)) {
                call.response.header(HttpHeaders.RetryAfter, SIGNIN_RATE_LIMIT.windowSeconds.toString())
                return call.respondApiError(HttpStatusCode.TooManyRequests, "RATE_LIMIT_EXCEEDED", "Too many identity requests.", metrics)
            }
            val raw = call.parameters["operationId"].orEmpty()
            val id = raw.takeIf { it.length == 36 }?.let(Uuid::parseOrNull) ?: return invalid(call)
            val operation =
                repository.get(account.id, id.toString()) ?: return call.respondApiError(
                    HttpStatusCode.NotFound,
                    "IDENTITY_ADOPTION_NOT_FOUND",
                    "This identity change is unavailable.",
                    metrics,
                )
            block(account, operation, lifecycle)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            call.respondApiError(
                HttpStatusCode.ServiceUnavailable,
                "IDENTITY_ADOPTION_UNAVAILABLE",
                "The identity change is unavailable.",
                metrics,
            )
        }
    }

    private fun matches(
        account: Account,
        operation: IdentityAdoptionOperation,
        expected: IdentityAdoptionExpectedOperation,
        replayAbandon: Boolean = false,
    ): Boolean {
        val b = operation.binding
        val revision =
            expected.expectedRevision > 0 &&
                (
                    operation.revision == expected.expectedRevision ||
                        (
                            replayAbandon &&
                                operation.phase == IdentityAdoptionPhase.CANCELED &&
                                expected.expectedRevision < Long.MAX_VALUE &&
                                operation.revision == expected.expectedRevision + 1
                        )
                )
        return revision &&
            account.identityKind == AccountIdentityKind.HOSTED &&
            account.did == b.sourceDid &&
            account.identityEpoch == b.sourceEpoch &&
            b.deviceId == expected.deviceId &&
            b.sourceDid == expected.sourceDid &&
            b.targetDid == expected.targetDid &&
            b.sourceEpoch == expected.sourceEpoch
    }

    private suspend fun invalid(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Identity action is invalid.", metrics)
    }

    private suspend fun changed(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.Conflict, "IDENTITY_ADOPTION_CHANGED", "The identity change could not continue.", metrics)
    }

    private companion object {
        val ACTIONS = setOf(IdentityAdoptionActionView.HOLD, IdentityAdoptionActionView.RENEW, IdentityAdoptionActionView.CANCEL)
    }
}
