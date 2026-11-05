@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionConfirmationService
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeyProof
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionStatus
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationData
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationRequest
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationResponse
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionDeviceKeyView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
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
import kotlin.uuid.Uuid

internal fun Route.identityAdoptionConfirmationRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    repository: IdentityAdoptionRepository?,
    service: IdentityAdoptionConfirmationService?,
    metrics: AuthMetricsRegistry,
    enabled: Boolean,
) {
    val context = IdentityAdoptionConfirmationContext(accounts, tokens, repository, service, metrics, enabled)
    post("/account/identity-adoption/{operationId}/confirmation") { context.begin(call) }
    post("/account/identity-adoption/{operationId}/confirm") { context.confirm(call) }
}

private class IdentityAdoptionConfirmationContext(
    private val accounts: AccountRepository,
    private val tokens: TokenService,
    private val repository: IdentityAdoptionRepository?,
    private val service: IdentityAdoptionConfirmationService?,
    private val metrics: AuthMetricsRegistry,
    private val enabled: Boolean,
) {
    private val limiter = InMemoryAuthRateLimiter()

    suspend fun begin(call: ApplicationCall) =
        withOperation(call) { account, operation, confirmation ->
            val request = call.receiveAccountRequest<IdentityAdoptionConfirmationStartRequest>(metrics) ?: return@withOperation
            if (!matches(call, account, operation, request.expected)) return@withOperation
            val key = request.deviceKey
            if (key.origin.length > 2048 || key.publicKey.length != 87) return@withOperation invalid(call)
            val challenge =
                confirmation.begin(operation, IdentityAdoptionDeviceKey(key.origin, key.publicKey))
                    ?: return@withOperation invalid(call)
            val device = challenge.deviceKey ?: return@withOperation invalid(call)
            call.respond(
                IdentityAdoptionConfirmationResponse(
                    true,
                    IdentityAdoptionConfirmationData(
                        IdentityAdoptionStatus(operation, null).response().data.operation,
                        challenge.decisionId.toString(),
                        challenge.options,
                        challenge.expiresAt.toString(),
                        IdentityAdoptionDeviceKeyView(device.origin, device.publicKey),
                    ),
                ),
            )
        }

    suspend fun confirm(call: ApplicationCall) =
        withOperation(call) { account, operation, confirmation ->
            val request = call.receiveAccountRequest<IdentityAdoptionConfirmationRequest>(metrics) ?: return@withOperation
            if (!matches(call, account, operation, request.expected)) return@withOperation
            val decision = request.decisionId.takeIf { it.length == 36 }?.let(Uuid::parseOrNull)
            if (decision == null || !request.bounded()) return@withOperation invalid(call)
            val proof = IdentityAdoptionPasskeyProof(request.challenge, request.credential)
            confirmation.confirm(operation, decision, proof, request.deviceSignature) ?: return@withOperation invalid(call)
            call.respond(IdentityAdoptionStatus(operation, null).response())
        }

    private suspend fun withOperation(
        call: ApplicationCall,
        block: suspend (Account, IdentityAdoptionOperation, IdentityAdoptionConfirmationService) -> Unit,
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
            if (!limiter.allow("identity-adoption:confirmation:${account.id}", SIGNIN_RATE_LIMIT)) {
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
            block(account, operation, service)
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

    private suspend fun matches(
        call: ApplicationCall,
        account: Account,
        operation: IdentityAdoptionOperation,
        expected: IdentityAdoptionExpectedOperation,
    ): Boolean {
        if (expected.expectedRevision <= 0 ||
            expected.sourceEpoch !in 0 until Long.MAX_VALUE ||
            expected.deviceId.isBlank() ||
            expected.deviceId.encodeToByteArray().size > 128 ||
            expected.deviceId.any(Char::isISOControl) ||
            listOf(expected.sourceDid, expected.targetDid).any { it.length > 2048 || !AtprotoDid.parse(it).isSuccess }
        ) {
            invalid(call)
            return false
        }
        val binding = operation.binding
        if (operation.phase != IdentityAdoptionPhase.PREPARING ||
            operation.revision != expected.expectedRevision ||
            account.identityKind != AccountIdentityKind.HOSTED ||
            account.did != binding.sourceDid ||
            account.identityEpoch != binding.sourceEpoch ||
            binding.deviceId != expected.deviceId ||
            binding.sourceDid != expected.sourceDid ||
            binding.targetDid != expected.targetDid ||
            binding.sourceEpoch != expected.sourceEpoch
        ) {
            call.respondApiError(HttpStatusCode.Conflict, "IDENTITY_ADOPTION_CHANGED", "Your account changed.", metrics)
            return false
        }
        return true
    }

    private suspend fun invalid(call: ApplicationCall) {
        call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Identity confirmation is invalid.", metrics)
    }
}

private fun IdentityAdoptionConfirmationRequest.bounded(): Boolean =
    challenge.matches(Regex("[A-Za-z0-9_-]{43}")) &&
        deviceSignature.matches(Regex("[A-Za-z0-9_-]{86}")) &&
        credential.type == "public-key" &&
        credential.id.length in 1..4096 &&
        credential.rawId.length in 1..4096 &&
        credential.response.clientDataJSON.length in 1..8192 &&
        credential.response.authenticatorData.length in 1..8192 &&
        credential.response.signature.length in 1..8192 &&
        (credential.response.userHandle?.length ?: 0) <= 512
