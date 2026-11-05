@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionConfirmationService
import app.logdate.server.identity.adoption.IdentityAdoptionCopyAccess
import app.logdate.server.identity.adoption.IdentityAdoptionFinalization
import app.logdate.server.identity.adoption.IdentityAdoptionLifecycle
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthService
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionSourceAccess
import app.logdate.server.identity.adoption.IdentityAdoptionStatus
import app.logdate.server.routes.docs.IdentityAdoptionRecoveryDocs
import app.logdate.shared.model.identity.IdentityAdoptionAuthorizationView
import app.logdate.shared.model.identity.IdentityAdoptionBindingView
import app.logdate.shared.model.identity.IdentityAdoptionDeviceKeyView
import app.logdate.shared.model.identity.IdentityAdoptionOperationView
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionReceiptView
import app.logdate.shared.model.identity.IdentityAdoptionStatusData
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid
import io.github.smiley4.ktoropenapi.get as documentedGet

/** Registration remains disabled until the complete protected adoption service is available. */
internal fun Route.identityAdoptionRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean = false,
    oauth: IdentityAdoptionOAuthService? = null,
    confirmation: IdentityAdoptionConfirmationService? = null,
    lifecycle: IdentityAdoptionLifecycle? = null,
    finalization: IdentityAdoptionFinalization? = null,
    sourceAccess: IdentityAdoptionSourceAccess? = null,
    copyAccess: IdentityAdoptionCopyAccess? = null,
) {
    identityAdoptionCopyRoutes(accounts, tokens, repository, copyAccess, metrics, enabled)
    identityAdoptionSourceRoutes(accounts, tokens, repository, sourceAccess, metrics, enabled)
    identityAdoptionFinalizationRoutes(accounts, tokens, repository, finalization, metrics, enabled)
    identityAdoptionOAuthRoutes(accounts, tokens, oauth, metrics, enabled)
    identityAdoptionLifecycleRoutes(accounts, tokens, repository, lifecycle, metrics, enabled)
    identityAdoptionConfirmationRoutes(accounts, tokens, repository, confirmation, metrics, enabled)
    val context = IdentityAdoptionStatusRouteContext(accounts, tokens, repository, metrics, enabled)
    identityAdoptionRecoveryRoutes(accounts, tokens, repository, metrics)
    get("/account/identity-adoption/{operationId}") { context.status(call) }
}

internal fun Route.identityAdoptionRecoveryRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    metrics: AuthMetricsRegistry,
) {
    val context = IdentityAdoptionStatusRouteContext(accounts, tokens, repository, metrics, enabled = false)
    documentedGet("/account/identity-adoption/current", IdentityAdoptionRecoveryDocs.current) { context.status(call, current = true) }
}

private class IdentityAdoptionStatusRouteContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val repository: IdentityAdoptionRepository?,
    private val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()

    suspend fun status(
        call: ApplicationCall,
        current: Boolean = false,
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if ((!enabled && !current) || repository == null) {
            return call.respondApiError(HttpStatusCode.NotFound, "FEATURE_DISABLED", "Identity changes are unavailable.", metrics)
        }
        try {
            val account = account(call) ?: return
            val status =
                if (current) {
                    repository.currentCommitted(account.id)
                } else {
                    val raw = call.parameters["operationId"].orEmpty()
                    val id =
                        raw.takeIf { it.length == 36 }?.let(Uuid::parseOrNull)
                            ?: return call.respondApiError(
                                HttpStatusCode.BadRequest,
                                "VALIDATION_ERROR",
                                "Identity change is invalid.",
                                metrics,
                            )
                    repository.status(account.id, id.toString())
                }
            if (status == null) {
                return call.respondApiError(
                    HttpStatusCode.NotFound,
                    "IDENTITY_ADOPTION_NOT_FOUND",
                    "This identity change is unavailable.",
                    metrics,
                )
            }
            call.respond(status.response())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            call.respondApiError(
                HttpStatusCode.ServiceUnavailable,
                "IDENTITY_ADOPTION_UNAVAILABLE",
                "The identity change could not be checked.",
                metrics,
            )
        }
    }

    private suspend fun account(call: ApplicationCall): Account? {
        val account = resolveAuthenticatedAccount(call, accounts, tokens, metrics) ?: return null
        if (account.moderation != null) {
            call.respondApiError(HttpStatusCode.Forbidden, "ACCOUNT_UNAVAILABLE", "This account is unavailable.", metrics)
            return null
        }
        if (!limiter.allow("identity-adoption:status:${account.id}", SIGNIN_RATE_LIMIT)) {
            call.response.header(HttpHeaders.RetryAfter, SIGNIN_RATE_LIMIT.windowSeconds.toString())
            call.respondApiError(HttpStatusCode.TooManyRequests, "RATE_LIMIT_EXCEEDED", "Too many identity requests.", metrics)
            return null
        }
        return account
    }
}

internal fun IdentityAdoptionStatus.response(): IdentityAdoptionStatusResponse {
    val binding = operation.binding
    val operationView =
        IdentityAdoptionOperationView(
            operation.id,
            IdentityAdoptionBindingView(
                binding.accountId.toString(),
                binding.sourceDid,
                binding.targetDid,
                binding.deviceId,
                binding.sourceEpoch,
            ),
            operation.revision,
            IdentityAdoptionPhaseView.valueOf(operation.phase.name),
            operation.leaseExpiresAt.toString(),
        )
    val receiptView =
        receipt?.let {
            IdentityAdoptionReceiptView(
                it.operationId,
                it.accountId.toString(),
                it.sourceDid,
                it.targetDid,
                it.identityEpoch,
                it.committedAt.toString(),
            )
        }
    val authorizationView =
        authorization?.let {
            IdentityAdoptionAuthorizationView(
                it.revision,
                IdentityAdoptionDeviceKeyView(it.deviceKey.origin, it.deviceKey.publicKey),
                it.authorizedAt.toString(),
            )
        }
    return IdentityAdoptionStatusResponse(true, IdentityAdoptionStatusData(operationView, receiptView, authorizationView))
}
