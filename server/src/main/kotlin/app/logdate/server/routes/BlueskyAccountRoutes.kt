package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountSessionUnavailableException
import app.logdate.server.bluesky.BlueskyAccountAction
import app.logdate.server.bluesky.BlueskyAccountContinuationService
import app.logdate.server.bluesky.BlueskyAccountFailure
import app.logdate.server.bluesky.BlueskyAccountFailureKind
import app.logdate.server.bluesky.BlueskyAccountProfile
import app.logdate.server.bluesky.BlueskyPasskeyAccountService
import app.logdate.server.bluesky.BlueskySignInFailure
import app.logdate.server.bluesky.BlueskySignInFailureKind
import app.logdate.server.bluesky.BlueskySignInService
import app.logdate.server.clientAddress
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.routes.docs.BlueskyAccountDocs
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationBeginResponse
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationBeginResponse
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import studio.hypertext.atproto.syntax.Handle

/** These endpoints can only continue a server-verified, originating-app-bound identity proof. */
internal fun Route.blueskyAccountRoutes(
    continuations: BlueskyAccountContinuationService,
    passkeys: BlueskyPasskeyAccountService,
    signIn: BlueskySignInService? = null,
    issueSession: suspend (Account) -> AuthResponse,
) {
    val limiter = FixedWindowRateLimiter()
    route("/auth/bluesky") {
        if (signIn != null) blueskyStartRoutes(signIn, limiter)
        post("/exchange", BlueskyAccountDocs.exchange) {
            call.blueskyRequest<BlueskyExchangeRequest, BlueskyExchangeResponse>(limiter, "exchange", SIGNIN_RATE_LIMIT) { request ->
                val next =
                    continuations.exchange(request.attemptId, request.verifier)
                        ?: throw BlueskyAccountFailure(BlueskyAccountFailureKind.EXPIRED)
                val step =
                    when (next.action) {
                        BlueskyAccountAction.CREATE_ACCOUNT -> BlueskyAccountNextStep.CREATE_ACCOUNT
                        BlueskyAccountAction.CONFIRM_PASSKEY -> BlueskyAccountNextStep.CONFIRM_PASSKEY
                    }
                BlueskyExchangeResponse(next.token, step)
            }
        }
        blueskyRegistrationRoutes(passkeys, issueSession, limiter)
        blueskyAuthenticationRoutes(passkeys, issueSession, limiter)
    }
}

private fun Route.blueskyStartRoutes(
    signIn: BlueskySignInService,
    limiter: FixedWindowRateLimiter,
) {
    post("/start", BlueskyAccountDocs.start) {
        call.blueskyRequest<BlueskyStartRequest, BlueskyStartResponse>(limiter, "start", SIGNIN_RATE_LIMIT) { request ->
            val handle =
                Handle.parse(request.handle.trim().removePrefix("@")).getOrNull()
                    ?: throw BadRequestException("The sign-in request is invalid")
            if (!request.challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) throw BadRequestException("The sign-in request is invalid")
            val started = signIn.start(handle, request.challenge)
            BlueskyStartResponse(started.attemptId, started.authorizationUrl)
        }
    }
}

private fun Route.blueskyRegistrationRoutes(
    passkeys: BlueskyPasskeyAccountService,
    issueSession: suspend (Account) -> AuthResponse,
    limiter: FixedWindowRateLimiter,
) {
    post("/registration/begin", BlueskyAccountDocs.beginRegistration) {
        call.blueskyRequest<BlueskyRegistrationBeginRequest, BlueskyRegistrationBeginResponse>(
            limiter,
            "registration.begin",
            SIGNUP_RATE_LIMIT,
        ) {
            val next =
                passkeys.beginRegistration(
                    it.continuationToken,
                    BlueskyAccountProfile(it.requestedOwnerId, it.username, it.displayName, it.bio, it.originalBio),
                )
            BlueskyRegistrationBeginResponse(next.token, next.options)
        }
    }
    post("/registration/complete", BlueskyAccountDocs.completeRegistration) {
        call.blueskyRequest<BlueskyRegistrationCompleteRequest, AuthResponse>(limiter, "registration.complete", SIGNUP_RATE_LIMIT) {
            issueSession(passkeys.completeRegistration(it.continuationToken, it.credential, it.nickname))
        }
    }
}

private fun Route.blueskyAuthenticationRoutes(
    passkeys: BlueskyPasskeyAccountService,
    issueSession: suspend (Account) -> AuthResponse,
    limiter: FixedWindowRateLimiter,
) {
    post("/authentication/begin", BlueskyAccountDocs.beginAuthentication) {
        call.blueskyRequest<BlueskyAuthenticationBeginRequest, BlueskyAuthenticationBeginResponse>(
            limiter,
            "authentication.begin",
            SIGNIN_RATE_LIMIT,
        ) {
            val next = passkeys.beginAuthentication(it.continuationToken)
            BlueskyAuthenticationBeginResponse(next.token, next.options)
        }
    }
    post("/authentication/complete", BlueskyAccountDocs.completeAuthentication) {
        call.blueskyRequest<BlueskyAuthenticationCompleteRequest, AuthResponse>(limiter, "authentication.complete", SIGNIN_RATE_LIMIT) {
            issueSession(passkeys.completeAuthentication(it.continuationToken, it.credential))
        }
    }
}

private suspend inline fun <reified Request : Any, reified Response : Any> ApplicationCall.blueskyRequest(
    limiter: FixedWindowRateLimiter,
    operation: String,
    policy: RateLimitPolicy,
    action: (Request) -> Response,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    val key = "$operation:${clientAddress()}"
    if (!limiter.allow(key, policy)) {
        response.headers.append(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(key).toString())
        return respond(HttpStatusCode.TooManyRequests, BlueskyApiError("RATE_LIMIT_EXCEEDED", "Too many sign-in requests"))
    }
    try {
        respond(action(receive<Request>()))
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (failure: BlueskyAccountFailure) {
        respondAccountFailure(failure.kind)
    } catch (failure: BlueskySignInFailure) {
        if (failure.kind == BlueskySignInFailureKind.DECLINED) {
            respond(HttpStatusCode.BadRequest, BlueskyApiError("BLUESKY_CANCELED", "Bluesky sign-in was canceled"))
            return
        }
        val kind =
            if (failure.kind ==
                BlueskySignInFailureKind.UNAVAILABLE
            ) {
                BlueskyAccountFailureKind.UNAVAILABLE
            } else {
                BlueskyAccountFailureKind.EXPIRED
            }
        respondAccountFailure(kind)
    } catch (_: AccountSessionUnavailableException) {
        respondAccountFailure(BlueskyAccountFailureKind.EXPIRED)
    } catch (_: ContentTransformationException) {
        respondInvalidBlueskyRequest()
    } catch (_: ContentConvertException) {
        respondInvalidBlueskyRequest()
    } catch (_: SerializationException) {
        respondInvalidBlueskyRequest()
    } catch (_: BadRequestException) {
        respondInvalidBlueskyRequest()
    } catch (_: Exception) {
        respondAccountFailure(BlueskyAccountFailureKind.UNAVAILABLE)
    }
}

private suspend fun ApplicationCall.respondInvalidBlueskyRequest() =
    respond(HttpStatusCode.BadRequest, BlueskyApiError("INVALID_REQUEST", "Request body is invalid"))

private suspend fun ApplicationCall.respondAccountFailure(kind: BlueskyAccountFailureKind) {
    val (status, error) =
        when (kind) {
            BlueskyAccountFailureKind.EXPIRED ->
                HttpStatusCode.Unauthorized to
                    BlueskyApiError("BLUESKY_HANDOFF_EXPIRED", "This sign-in request has expired")
            BlueskyAccountFailureKind.INVALID_PROFILE ->
                HttpStatusCode.BadRequest to
                    BlueskyApiError("VALIDATION_ERROR", "Check the account details")
            BlueskyAccountFailureKind.CONFLICT ->
                HttpStatusCode.Conflict to
                    BlueskyApiError("ACCOUNT_LINK_CONFLICT", "This account could not be created or confirmed")
            BlueskyAccountFailureKind.PASSKEY_FAILED ->
                HttpStatusCode.Unauthorized to
                    BlueskyApiError("PASSKEY_VERIFICATION_FAILED", "The passkey could not be confirmed")
            BlueskyAccountFailureKind.UNAVAILABLE ->
                HttpStatusCode.ServiceUnavailable to
                    BlueskyApiError("BLUESKY_UNAVAILABLE", "Bluesky sign-in is unavailable")
        }
    respond(status, error)
}

@Serializable
private data class BlueskyApiError(
    val error: BlueskyApiErrorDetail,
) {
    constructor(code: String, message: String) : this(BlueskyApiErrorDetail(code, message))
}

@Serializable
private data class BlueskyApiErrorDetail(
    val code: String,
    val message: String,
)
