@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionSourceAccess
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceQuery
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionActionData
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionSourceData
import app.logdate.shared.model.identity.IdentityAdoptionSourceQueryView
import app.logdate.shared.model.identity.IdentityAdoptionSourceReadRequest
import app.logdate.shared.model.identity.IdentityAdoptionSourceResponse
import app.logdate.shared.model.identity.IdentityAdoptionSourceStartRequest
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

internal fun Route.identityAdoptionSourceRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    service: IdentityAdoptionSourceAccess?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean,
) {
    val context = IdentityAdoptionSourceContext(accounts, tokens, repository, service, metrics, enabled)
    post("/account/identity-adoption/{operationId}/source-challenge") { context.issue(call) }
    post("/account/identity-adoption/{operationId}/source-page") { context.read(call) }
}

private class IdentityAdoptionSourceContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val repository: IdentityAdoptionRepository?,
    private val service: IdentityAdoptionSourceAccess?,
    private val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()
    private val policy = SpaceRateLimits().reads

    suspend fun issue(call: ApplicationCall) =
        withOperation(call) { account, current, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionSourceStartRequest>(metrics) ?: return@withOperation
            val operation = operation(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
            val challenge = service.issue(operation, request.query.domain()) ?: return@withOperation changed(call)
            val status = repository?.status(account.id, operation.id)
            if (status?.operation != operation) return@withOperation changed(call)
            call.respond(
                IdentityAdoptionActionResponse(
                    true,
                    IdentityAdoptionActionData(
                        status.response().data.operation,
                        IdentityAdoptionActionChallengeView(
                            challenge.id.toString(),
                            challenge.nonce,
                            IdentityAdoptionActionView.READ_STAGED,
                            challenge.bodyDigest,
                        ),
                    ),
                ),
            )
        }

    suspend fun read(call: ApplicationCall) =
        withOperation(call) { account, current, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionSourceReadRequest>(metrics) ?: return@withOperation
            val operation = operation(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
            val challenge =
                finalizationChallenge(request.challenge, IdentityAdoptionAction.READ_STAGED, request.signature)
                    ?: return@withOperation invalid(call)
            val page = service.read(operation, request.query.domain(), challenge, request.signature) ?: return@withOperation changed(call)
            val status = repository?.status(account.id, operation.id)
            if (status?.operation != operation || page.operationId != operation.id || page.revision != operation.revision) {
                return@withOperation changed(call)
            }
            call.respond(
                IdentityAdoptionSourceResponse(
                    true,
                    IdentityAdoptionSourceData(
                        status.response().data.operation,
                        identityAdoptionSourceTransportPage(page),
                    ),
                ),
            )
        }

    private fun operation(
        account: Account,
        current: IdentityAdoptionOperation,
        expected: IdentityAdoptionExpectedOperation,
        phase: IdentityAdoptionPhaseView,
    ): IdentityAdoptionOperation? {
        if (phase !in
            setOf(IdentityAdoptionPhaseView.PREPARING, IdentityAdoptionPhaseView.FENCED, IdentityAdoptionPhaseView.VERIFIED)
        ) {
            return null
        }
        return finalizationOperation(account, current, expected, IdentityAdoptionPhase.valueOf(phase.name))
    }

    private fun IdentityAdoptionSourceQueryView.domain() =
        IdentityAdoptionSourceQuery(
            IdentityAdoptionSourceKind.valueOf(kind.name),
            after,
            limit,
        )

    private suspend fun withOperation(
        call: ApplicationCall,
        block: suspend (Account, IdentityAdoptionOperation, IdentityAdoptionSourceAccess) -> Unit,
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!enabled || repository == null || service == null) {
            return call.respondApiError(HttpStatusCode.NotFound, "FEATURE_DISABLED", "Identity changes are unavailable.", metrics)
        }
        try {
            val account = resolveAuthenticatedAccount(call, accounts, tokens, metrics) ?: return
            if (account.moderation != null) {
                return call.respondApiError(HttpStatusCode.Forbidden, "ACCOUNT_UNAVAILABLE", "This account is unavailable.", metrics)
            }
            if (!limiter.allow("identity-adoption:source:${account.id}", policy)) {
                call.response.header(HttpHeaders.RetryAfter, policy.windowSeconds.toString())
                return call.respondApiError(HttpStatusCode.TooManyRequests, "RATE_LIMIT_EXCEEDED", "Too many identity requests.", metrics)
            }
            val id =
                call.parameters["operationId"]
                    .orEmpty()
                    .takeIf { it.length == 36 }
                    ?.let(Uuid::parseOrNull)
                    ?: return invalid(call)
            val operation =
                repository.get(account.id, id.toString()) ?: return call.respondApiError(
                    HttpStatusCode.NotFound,
                    "IDENTITY_ADOPTION_NOT_FOUND",
                    "This identity change is unavailable.",
                    metrics,
                )
            block(account, operation, service)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: IdentityAdoptionStagingException) {
            if (failure.reason == IdentityAdoptionStagingFailure.INVALID_REQUEST) invalid(call) else changed(call)
        } catch (_: Exception) {
            call.respondApiError(
                HttpStatusCode.ServiceUnavailable,
                "IDENTITY_ADOPTION_UNAVAILABLE",
                "The identity change could not continue.",
                metrics,
            )
        }
    }

    private suspend fun invalid(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Identity action is invalid.", metrics)
    }

    private suspend fun changed(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.Conflict, "IDENTITY_ADOPTION_CHANGED", "The identity change could not continue.", metrics)
    }
}
