@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionFinalization
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionActionData
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionFinalizationStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionVerifyRequest
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
import app.logdate.shared.model.identity.IdentityAdoptionCommitRequest as CommitWireRequest

internal fun Route.identityAdoptionFinalizationRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    service: IdentityAdoptionFinalization?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean,
) {
    val context = IdentityAdoptionFinalizationContext(accounts, tokens, repository, service, metrics, enabled)
    post("/account/identity-adoption/{operationId}/finalization-challenge") { context.issue(call) }
    post("/account/identity-adoption/{operationId}/verify") { context.verify(call) }
    post("/account/identity-adoption/{operationId}/commit") { context.commit(call) }
}

private class IdentityAdoptionFinalizationContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val repository: IdentityAdoptionRepository?,
    private val service: IdentityAdoptionFinalization?,
    private val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()

    suspend fun issue(call: ApplicationCall) =
        withOperation(call) { account, current, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionFinalizationStartRequest>(metrics) ?: return@withOperation
            val phase =
                when (request.action) {
                    IdentityAdoptionActionView.VERIFY -> IdentityAdoptionPhase.FENCED
                    IdentityAdoptionActionView.COMMIT -> IdentityAdoptionPhase.VERIFIED
                    else -> return@withOperation invalid(call)
                }
            if (request.phase.name != phase.name || !validDigests(request.manifestDigest, request.coverageDigest)) {
                return@withOperation invalid(call)
            }
            val operation = finalizationOperation(account, current, request.expected, phase) ?: return@withOperation changed(call)
            val challenge =
                service.issue(
                    operation,
                    IdentityAdoptionAction.valueOf(request.action.name),
                    IdentityAdoptionCommitRequest(request.manifestDigest, request.coverageDigest),
                ) ?: return@withOperation changed(call)
            val status = repository?.status(account.id, operation.id)
            if (status?.operation != operation) return@withOperation changed(call)
            call.respond(
                IdentityAdoptionActionResponse(
                    true,
                    IdentityAdoptionActionData(
                        status.response().data.operation,
                        IdentityAdoptionActionChallengeView(challenge.id.toString(), challenge.nonce, request.action, challenge.bodyDigest),
                    ),
                ),
            )
        }

    suspend fun verify(call: ApplicationCall) =
        withOperation(call) { account, current, service ->
            val request = call.receiveAccountRequest<IdentityAdoptionVerifyRequest>(metrics) ?: return@withOperation
            val operation =
                finalizationOperation(account, current, request.expected, IdentityAdoptionPhase.FENCED)
                    ?: return@withOperation changed(call)
            val challenge =
                finalizationChallenge(request.challenge, IdentityAdoptionAction.VERIFY, request.signature)
                    ?: return@withOperation invalid(call)
            val manifest = finalizationManifest(request.manifest) ?: return@withOperation invalid(call)
            if (!validDigests(request.coverageDigest)) return@withOperation invalid(call)
            val verified =
                service.verify(
                    operation,
                    challenge,
                    request.signature,
                    IdentityAdoptionVerificationRequest(manifest, request.coverageDigest),
                ) ?: return@withOperation changed(call)
            val status = repository?.status(account.id, operation.id)
            if (status?.operation != verified.operation || status.receipt != null) return@withOperation changed(call)
            call.respond(status.response())
        }

    suspend fun commit(call: ApplicationCall) =
        withOperation(call) { account, current, service ->
            val request = call.receiveAccountRequest<CommitWireRequest>(metrics) ?: return@withOperation
            val operation =
                finalizationOperation(account, current, request.expected, IdentityAdoptionPhase.VERIFIED, replayCommit = true)
                    ?: return@withOperation changed(call)
            val challenge =
                finalizationChallenge(request.challenge, IdentityAdoptionAction.COMMIT, request.signature)
                    ?: return@withOperation invalid(call)
            if (!validDigests(request.manifestDigest, request.coverageDigest)) return@withOperation invalid(call)
            val receipt =
                service.commit(
                    operation,
                    challenge,
                    request.signature,
                    IdentityAdoptionCommitRequest(request.manifestDigest, request.coverageDigest),
                ) ?: return@withOperation changed(call)
            val status = repository?.status(account.id, operation.id)
            if (status?.receipt != receipt || status.operation.phase != IdentityAdoptionPhase.COMMITTED) return@withOperation changed(call)
            call.respond(status.response())
        }

    private suspend fun withOperation(
        call: ApplicationCall,
        block: suspend (Account, IdentityAdoptionOperation, IdentityAdoptionFinalization) -> Unit,
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
            if (!limiter.allow("identity-adoption:finalize:${account.id}", SIGNIN_RATE_LIMIT)) {
                call.response.header(HttpHeaders.RetryAfter, SIGNIN_RATE_LIMIT.windowSeconds.toString())
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

    private fun validDigests(vararg values: String): Boolean = values.all { it.matches(Regex("[0-9a-f]{64}")) }

    private suspend fun invalid(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Identity action is invalid.", metrics)
    }

    private suspend fun changed(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.Conflict, "IDENTITY_ADOPTION_CHANGED", "The identity change could not continue.", metrics)
    }
}
