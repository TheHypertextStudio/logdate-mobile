@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.bluesky.connection.AtprotoConnectionFailure
import app.logdate.server.bluesky.connection.AtprotoConnectionService
import app.logdate.server.bluesky.connection.AtprotoReconnectService
import app.logdate.server.bluesky.connection.ConnectionFailureKind
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.routes.docs.AtprotoConnectionDocs
import app.logdate.shared.model.AtprotoDisconnectBeginRequest
import app.logdate.shared.model.AtprotoDisconnectRequest
import app.logdate.shared.model.AtprotoReconnectCompleteRequest
import app.logdate.shared.model.AtprotoReconnectStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private const val MAX_CONNECTION_REQUEST_BYTES = 64 * 1024
private val CONNECTION_IDENTIFIER = Regex("^[A-Za-z0-9_-]{43}$")
private val connectionRequestJson = Json { ignoreUnknownKeys = true }

internal fun Route.atprotoConnectionRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    service: AtprotoConnectionService,
    metrics: AuthMetricsRegistry,
    reconnect: AtprotoReconnectService? = null,
) {
    val context = ConnectionRouteContext(accounts, tokens, metrics)
    route("/auth/atproto/connection") {
        if (reconnect != null) {
            post("/reconnect/start", AtprotoConnectionDocs.reconnectStart) {
                context.withAccount(call, "reconnect.start") { account ->
                    val request = call.receiveConnectionRequest<AtprotoReconnectStartRequest>(metrics) ?: return@withAccount
                    if (!CONNECTION_IDENTIFIER.matches(request.challenge)) return@withAccount call.rejectConnectionInput(metrics)
                    val started = reconnect.start(account.id, request.challenge)
                    call.respond(BlueskyStartResponse(started.attemptId, started.authorizationUrl))
                }
            }
            post("/reconnect/complete", AtprotoConnectionDocs.reconnectComplete) {
                context.withAccount(call, "reconnect.complete") { account ->
                    val request = call.receiveConnectionRequest<AtprotoReconnectCompleteRequest>(metrics) ?: return@withAccount
                    if (!CONNECTION_IDENTIFIER.matches(request.attemptId) || !request.verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}"))) {
                        return@withAccount call.rejectConnectionInput(metrics)
                    }
                    call.respond(reconnect.complete(account.id, request.attemptId, request.verifier))
                }
            }
        }
        get(AtprotoConnectionDocs.status) {
            context.withAccount(call, "status") { account -> call.respond(service.status(account.id)) }
        }
        post("/step-up", AtprotoConnectionDocs.begin) {
            context.withAccount(call, "step-up") { account ->
                val request = call.receiveConnectionRequest<AtprotoDisconnectBeginRequest>(metrics) ?: return@withAccount
                if (request.expectedRevision < 0) return@withAccount call.rejectConnectionInput(metrics)
                call.respond(service.beginDisconnect(account.id, request.expectedRevision))
            }
        }
        delete(AtprotoConnectionDocs.disconnect) {
            context.withAccount(call, "disconnect") { account ->
                val request = call.receiveConnectionRequest<AtprotoDisconnectRequest>(metrics) ?: return@withAccount
                if (!request.isBounded()) return@withAccount call.rejectConnectionInput(metrics)
                call.respond(service.disconnect(account.id, request.decisionId, PasskeyStepUpProof(request.challenge, request.credential)))
            }
        }
    }
}

private class ConnectionRouteContext(
    val accounts: AccountRepository,
    val tokens: TokenService,
    val metrics: AuthMetricsRegistry,
) {
    private val limiter = InMemoryAuthRateLimiter()

    suspend fun withAccount(
        call: ApplicationCall,
        operation: String,
        block: suspend (Account) -> Unit,
    ) {
        try {
            val account = resolveAuthenticatedAccount(call, accounts, tokens, metrics) ?: return
            if (!limiter.allow("atproto:$operation:${account.id}", SIGNIN_RATE_LIMIT)) {
                call.response.header(HttpHeaders.RetryAfter, SIGNIN_RATE_LIMIT.windowSeconds.toString())
                call.respondApiError(HttpStatusCode.TooManyRequests, "RATE_LIMIT_EXCEEDED", "Too many connection requests", metrics)
                return
            }
            block(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AtprotoConnectionFailure) {
            call.respondApiError(failure.kind.status(), failure.kind.code, failure.kind.description, metrics)
        } catch (_: Exception) {
            val failure = ConnectionFailureKind.UNAVAILABLE
            call.respondApiError(HttpStatusCode.ServiceUnavailable, failure.code, failure.description, metrics)
        }
    }
}

private fun ConnectionFailureKind.status(): HttpStatusCode =
    when (this) {
        ConnectionFailureKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        ConnectionFailureKind.PROOF_INVALID -> HttpStatusCode.Forbidden
        ConnectionFailureKind.PROOF_EXPIRED, ConnectionFailureKind.RECONNECT_INVALID -> HttpStatusCode.Gone
        ConnectionFailureKind.INELIGIBLE, ConnectionFailureKind.CHANGED, ConnectionFailureKind.LAST_SIGNIN_FACTOR -> HttpStatusCode.Conflict
    }

private suspend inline fun <reified T> ApplicationCall.receiveConnectionRequest(metrics: AuthMetricsRegistry): T? {
    val media = request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
    if (!media.equals("application/json", ignoreCase = true)) {
        respondApiError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body is invalid", metrics)
        return null
    }
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_CONNECTION_REQUEST_BYTES) {
        respondApiError(HttpStatusCode.PayloadTooLarge, "INVALID_REQUEST", "Request body is too large", metrics)
        return null
    }
    val bytes = receiveChannel().readRemaining((MAX_CONNECTION_REQUEST_BYTES + 1).toLong()).readByteArray()
    if (bytes.size > MAX_CONNECTION_REQUEST_BYTES) {
        respondApiError(HttpStatusCode.PayloadTooLarge, "INVALID_REQUEST", "Request body is too large", metrics)
        return null
    }
    return try {
        connectionRequestJson.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: Exception) {
        respondApiError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body is invalid", metrics)
        null
    }
}

private fun AtprotoDisconnectRequest.isBounded(): Boolean =
    CONNECTION_IDENTIFIER.matches(decisionId) &&
        CONNECTION_IDENTIFIER.matches(challenge) &&
        credential.type == "public-key" &&
        credential.id.length in 1..4096 &&
        credential.rawId.length in 1..4096 &&
        credential.response.clientDataJSON.length in 1..8192 &&
        credential.response.authenticatorData.length in 1..8192 &&
        credential.response.signature.length in 1..8192 &&
        credential.response.userHandle.length <= 512

private suspend fun ApplicationCall.rejectConnectionInput(metrics: AuthMetricsRegistry) {
    respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Connection request is invalid", metrics)
}
