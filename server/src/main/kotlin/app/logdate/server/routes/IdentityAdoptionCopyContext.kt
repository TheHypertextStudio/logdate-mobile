@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionCopyAccess
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionOperationView
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

internal class IdentityAdoptionCopyContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val repository: IdentityAdoptionRepository?,
    private val service: IdentityAdoptionCopyAccess?,
    val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()
    private val policy = SpaceRateLimits().recordWrites

    suspend fun withOperation(
        call: ApplicationCall,
        block: suspend (Account, IdentityAdoptionOperation, IdentityAdoptionCopyAccess) -> Unit,
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
            if (!limiter.allow("identity-adoption:copy:${account.id}", policy)) {
                call.response.header(HttpHeaders.RetryAfter, policy.windowSeconds.toString())
                return call.respondApiError(HttpStatusCode.TooManyRequests, "RATE_LIMIT_EXCEEDED", "Too many identity requests.", metrics)
            }
            val id =
                call.parameters["operationId"]
                    .orEmpty()
                    .takeIf { it.length == 36 }
                    ?.let(Uuid::parseOrNull)
                    ?: return invalid(call)
            val operation = repository.get(account.id, id.toString()) ?: return missing(call)
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

    fun expected(
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

    suspend fun view(
        call: ApplicationCall,
        operation: IdentityAdoptionOperation,
    ): IdentityAdoptionOperationView? {
        val status = repository?.status(operation.binding.accountId, operation.id)
        if (status?.operation != operation) {
            changed(call)
            return null
        }
        return status.response().data.operation
    }

    suspend fun invalid(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Identity action is invalid.", metrics)
    }

    suspend fun changed(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.Conflict, "IDENTITY_ADOPTION_CHANGED", "The identity change could not continue.", metrics)
    }

    suspend fun missing(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.NotFound, "IDENTITY_COPY_NOT_FOUND", "The private copy is unavailable.", metrics)
    }
}
