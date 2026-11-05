package app.logdate.server.routes

import app.logdate.server.logging.IdentityNotices
import app.logdate.server.routes.docs.XrpcDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import studio.hypertext.atproto.pds.EmptyPdsResponse
import studio.hypertext.atproto.pds.PdsErrorResponse

internal fun Route.xrpcSessionRoutes(context: XrpcRouteContext) {
    registerXrpcResolveHandle(context)
    registerXrpcCreateAccount(context)
    registerXrpcCreateSession(context)
    registerXrpcGetSession(context)
    registerXrpcRefreshSession(context)
    registerXrpcDeleteSession(context)
    registerXrpcDescribeServer(context)
}

private fun Route.registerXrpcResolveHandle(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.identity.resolveHandle", XrpcDocs.resolveHandle) {
            val handleValue =
                call.request.queryParameters["handle"]
                    ?.trim()
                    .orEmpty()
            if (handleValue.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "Handle is required"),
                )
                return@get
            }

            val result = identityService.resolveHandle(handleValue)
            // A failed lookup is an outage, not a missing handle: answering HandleNotFound would let
            // other apps treat a real account as nonexistent.
            result.exceptionOrNull()?.let { error ->
                Napier.e(IdentityNotices.HANDLE_RESOLUTION_FAILED, error)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    PdsErrorResponse("InternalServerError", "Handle resolution failed"),
                )
                return@get
            }
            val resolved = result.getOrNull()
            if (resolved == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("HandleNotFound", "Handle not found: ${handleValue.lowercase()}"),
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, resolved)
        }

        // Sign-in is passkey-only, so neither password method does any work. Refusing before the body is
        // read means no request, however malformed, reaches the account or password services. Neither
        // method can succeed, so both stay out of the API reference.
    }
}

private fun Route.registerXrpcCreateAccount(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.server.createAccount", { hidden = true }) {
            call.respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", "Accounts cannot be created with a password. Create your account in the LogDate app."),
            )
        }
    }
}

private fun Route.registerXrpcCreateSession(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.server.createSession", { hidden = true }) {
            call.respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", "Password sign-in is not supported. Sign in with a passkey."),
            )
        }
    }
}

private fun Route.registerXrpcGetSession(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.server.getSession", XrpcDocs.getSession) {
            val sessions =
                sessionService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Session service is not configured"),
                )
            val bearerToken = call.requireBearerToken() ?: return@get
            val result = sessions.getSession(bearerToken)
            val session = result.getOrNull()
            if (session == null) {
                call.respondForSessionError(result.exceptionOrNull())
                return@get
            }
            call.respond(HttpStatusCode.OK, session)
        }
    }
}

private fun Route.registerXrpcRefreshSession(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.server.refreshSession", XrpcDocs.refreshSession) {
            val sessions =
                sessionService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Session service is not configured"),
                )
            val bearerToken = call.requireBearerToken() ?: return@post
            val result = sessions.refreshSession(bearerToken)
            val session = result.getOrNull()
            if (session == null) {
                call.respondForSessionError(result.exceptionOrNull())
                return@post
            }
            call.respond(HttpStatusCode.OK, session)
        }
    }
}

private fun Route.registerXrpcDeleteSession(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.server.deleteSession", XrpcDocs.deleteSession) {
            val sessions =
                sessionService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Session service is not configured"),
                )
            val bearerToken = call.requireBearerToken() ?: return@post
            val result = sessions.deleteSession(bearerToken)
            if (result.isFailure) {
                call.respondForSessionError(result.exceptionOrNull())
                return@post
            }
            call.respond(HttpStatusCode.OK, EmptyPdsResponse())
        }
    }
}

private fun Route.registerXrpcDescribeServer(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.server.describeServer", XrpcDocs.describeServer) {
            val response =
                discoveryService?.describeServer()
                    ?: studio.hypertext.atproto.pds.DescribeServerResponse(
                        did = identityService.config.serverDid,
                        availableUserDomains = listOf(identityService.config.normalizedHandleDomain),
                        inviteCodeRequired = false,
                        phoneVerificationRequired = false,
                    )
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
