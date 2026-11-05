@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthService
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityAdoptionStatus
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.identity.IdentityAdoptionBrowserCancelData
import app.logdate.shared.model.identity.IdentityAdoptionBrowserCancelResponse
import app.logdate.shared.model.identity.IdentityAdoptionOAuthExchangeRequest
import app.logdate.shared.model.identity.IdentityAdoptionOAuthStartData
import app.logdate.shared.model.identity.IdentityAdoptionOAuthStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionOAuthStartResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import kotlin.uuid.Uuid

private val adoptionIdentifier = Regex("[A-Za-z0-9_-]{43}")

internal fun Route.identityAdoptionOAuthRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    service: IdentityAdoptionOAuthService?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean,
) {
    val context = IdentityAdoptionOAuthRouteContext(accounts, tokens, service, metrics, enabled)
    post("/account/identity-adoption/begin") { context.begin(call) }
    post("/account/identity-adoption/cancel-browser") { context.cancelBrowser(call) }
    post("/account/identity-adoption/exchange") { context.exchange(call) }
}

private class IdentityAdoptionOAuthRouteContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val service: IdentityAdoptionOAuthService?,
    private val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()

    suspend fun begin(call: ApplicationCall) =
        withAccount(call) { account, oauth ->
            val request = call.receiveAccountRequest<IdentityAdoptionOAuthStartRequest>(metrics) ?: return@withAccount
            val handle = Handle.parse(request.handle.trim().removePrefix("@")).getOrNull()
            if (handle == null ||
                !adoptionIdentifier.matches(request.challenge) ||
                !validSource(request.deviceId, request.sourceDid, request.sourceEpoch)
            ) {
                return@withAccount invalid(call)
            }
            if (!matches(account, request.sourceDid, request.sourceEpoch)) return@withAccount changed(call)
            val binding =
                IdentityAdoptionOAuthBinding(
                    account.id,
                    request.sourceDid,
                    request.sourceEpoch,
                    request.deviceId,
                    Uuid.random(),
                )
            val started = oauth.start(binding, handle, request.challenge) ?: return@withAccount invalid(call)
            call.respond(
                IdentityAdoptionOAuthStartResponse(
                    true,
                    IdentityAdoptionOAuthStartData(
                        binding.proposalId.toString(),
                        binding.accountId.toString(),
                        binding.deviceId,
                        binding.sourceDid,
                        binding.sourceEpoch,
                        started.attemptId,
                        started.authorizationUrl,
                        started.expiresAt.toString(),
                    ),
                ),
            )
        }

    suspend fun cancelBrowser(call: ApplicationCall) =
        withAccount(call) { account, oauth ->
            val request = call.receiveAccountRequest<IdentityAdoptionOAuthExchangeRequest>(metrics) ?: return@withAccount
            val proposal = request.proposalId.takeIf { it.length == 36 }?.let(Uuid::parseOrNull)
            if (proposal == null ||
                !adoptionIdentifier.matches(request.attemptId) ||
                !request.verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")) ||
                !validSource(request.deviceId, request.sourceDid, request.sourceEpoch)
            ) {
                return@withAccount invalid(call)
            }
            if (!matches(account, request.sourceDid, request.sourceEpoch)) return@withAccount changed(call)
            val binding = IdentityAdoptionOAuthBinding(account.id, request.sourceDid, request.sourceEpoch, request.deviceId, proposal)
            when (val result = oauth.cancelBrowser(binding, request.attemptId, request.verifier)) {
                is IdentityAdoptionOutcome.Accepted -> {
                    val canceled = result.value
                    call.respond(
                        IdentityAdoptionBrowserCancelResponse(
                            true,
                            IdentityAdoptionBrowserCancelData(
                                binding.proposalId.toString(),
                                binding.accountId.toString(),
                                binding.deviceId,
                                binding.sourceDid,
                                binding.sourceEpoch,
                                canceled.canceledAt.toString(),
                            ),
                        ),
                    )
                }
                is IdentityAdoptionOutcome.Refused ->
                    when (result.reason) {
                        IdentityAdoptionRefusal.PROOF_INVALID -> invalid(call)
                        else -> changed(call)
                    }
            }
        }

    suspend fun exchange(call: ApplicationCall) =
        withAccount(call) { account, oauth ->
            val request = call.receiveAccountRequest<IdentityAdoptionOAuthExchangeRequest>(metrics) ?: return@withAccount
            val proposal = request.proposalId.takeIf { it.length == 36 }?.let(Uuid::parseOrNull)
            if (proposal == null ||
                !adoptionIdentifier.matches(request.attemptId) ||
                !request.verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")) ||
                !validSource(request.deviceId, request.sourceDid, request.sourceEpoch)
            ) {
                return@withAccount invalid(call)
            }
            if (!matches(account, request.sourceDid, request.sourceEpoch)) return@withAccount changed(call)
            val binding = IdentityAdoptionOAuthBinding(account.id, request.sourceDid, request.sourceEpoch, request.deviceId, proposal)
            when (val result = oauth.exchange(binding, request.attemptId, request.verifier)) {
                is IdentityAdoptionOutcome.Accepted -> call.respond(IdentityAdoptionStatus(result.value, null).response())
                is IdentityAdoptionOutcome.Refused ->
                    when (result.reason) {
                        IdentityAdoptionRefusal.PROOF_INVALID, IdentityAdoptionRefusal.PROOF_EXPIRED -> invalid(call)
                        else -> changed(call)
                    }
            }
        }

    private suspend fun withAccount(
        call: ApplicationCall,
        block: suspend (Account, IdentityAdoptionOAuthService) -> Unit,
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!enabled || service == null) {
            return call.respondApiError(HttpStatusCode.NotFound, "FEATURE_DISABLED", "Identity changes are unavailable.", metrics)
        }
        try {
            val account = resolveAuthenticatedAccount(call, accounts, tokens, metrics) ?: return
            if (account.moderation != null) {
                return call.respondApiError(HttpStatusCode.Forbidden, "ACCOUNT_UNAVAILABLE", "This account is unavailable.", metrics)
            }
            if (!limiter.allow("identity-adoption:oauth:${account.id}", SIGNIN_RATE_LIMIT)) {
                call.response.header(HttpHeaders.RetryAfter, SIGNIN_RATE_LIMIT.windowSeconds.toString())
                return call.respondApiError(HttpStatusCode.TooManyRequests, "RATE_LIMIT_EXCEEDED", "Too many identity requests.", metrics)
            }
            block(account, service)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: IdentityWriteRefusedException) {
            changed(call)
        } catch (_: Exception) {
            call.respondApiError(
                HttpStatusCode.ServiceUnavailable,
                "IDENTITY_ADOPTION_UNAVAILABLE",
                "The identity change is unavailable.",
                metrics,
            )
        }
    }

    private fun validSource(
        device: String,
        did: String,
        epoch: Long,
    ): Boolean =
        device.isNotBlank() &&
            device.toByteArray().size <= 128 &&
            device.none(Char::isISOControl) &&
            did.length <= 2048 &&
            AtprotoDid.parse(did).isSuccess &&
            epoch in 0 until Long.MAX_VALUE

    private fun matches(
        account: Account,
        did: String,
        epoch: Long,
    ): Boolean = account.identityKind == AccountIdentityKind.HOSTED && account.did == did && account.identityEpoch == epoch

    private suspend fun invalid(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Identity change is invalid.", metrics)
    }

    private suspend fun changed(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.Conflict, "IDENTITY_ADOPTION_CHANGED", "Your account changed.", metrics)
    }
}
