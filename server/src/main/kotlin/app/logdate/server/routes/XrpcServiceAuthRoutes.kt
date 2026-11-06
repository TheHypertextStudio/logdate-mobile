package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoServiceAuth
import app.logdate.server.atproto.ServiceAuthException
import app.logdate.server.atproto.ServiceAuthResponse
import app.logdate.server.oauth.OAuthScopes
import app.logdate.server.routes.docs.XrpcDocs
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import studio.hypertext.atproto.pds.PdsErrorResponse

internal fun Route.xrpcServiceAuthRoutes(
    serviceAuth: AtprotoServiceAuth?,
    authenticate: suspend ApplicationCall.() -> XrpcPrincipal?,
) {
    get("/com.atproto.server.getServiceAuth", XrpcDocs.getServiceAuth) {
        val issuer =
            serviceAuth ?: return@get call.respond(
                HttpStatusCode.NotImplemented,
                PdsErrorResponse("Unsupported", "Service authentication is not configured"),
            )
        val principal = call.authenticate() ?: return@get
        val parameters = call.request.queryParameters
        val audience = parameters.getAll("aud")?.singleOrNull()
        val method = parameters.getAll("lxm")?.singleOrNull()
        if (audience == null || method == null) {
            return@get call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "Provide one audience and method."))
        }
        val expirationValues = parameters.getAll("exp")
        val expiresAt = expirationValues?.singleOrNull()?.toLongOrNull()
        if (expirationValues != null && expiresAt == null) {
            return@get call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("BadExpiration", "Provide one expiration time."))
        }
        if (!principal.mayUseServiceMethod(method)) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                PdsErrorResponse("Forbidden", "This method is outside the session grant."),
            )
        }
        val token =
            try {
                issuer.issue(principal.account, audience, method, expiresAt)
            } catch (error: ServiceAuthException) {
                val status =
                    when (error.code) {
                        "Forbidden" -> HttpStatusCode.Forbidden
                        "TemporarilyUnavailable" -> HttpStatusCode.ServiceUnavailable
                        else -> HttpStatusCode.BadRequest
                    }
                return@get call.respond(status, PdsErrorResponse(error.code, error.message))
            }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.OK, ServiceAuthResponse(token))
    }
}

internal fun XrpcPrincipal.mayUseServiceMethod(method: String): Boolean {
    val name = method.lowercase()
    if (PRIVATE_SERVICE_METHOD_PREFIXES.any(name::startsWith)) return false
    return oauth == null || (OAuthScopes.TRANSITION_GENERIC in oauth.scopes && !name.startsWith("chat.bsky."))
}

private val PRIVATE_SERVICE_METHOD_PREFIXES =
    listOf(
        "com.atproto.server.",
        "com.atproto.admin.",
        "com.atproto.identity.",
        "com.atproto.repo.",
        "com.atproto.sync.",
        "app.logdate.",
        "studio.hypertext.logdate.",
    )
