package app.logdate.server.routes

import app.logdate.server.atproto.ACCEPT_LABELERS
import app.logdate.server.atproto.AtprotoServiceAuth
import app.logdate.server.atproto.AtprotoServiceProxy
import app.logdate.server.atproto.BLUESKY_APPVIEW
import app.logdate.server.atproto.BLUESKY_CHAT
import app.logdate.server.atproto.BLUESKY_MODERATION
import app.logdate.server.atproto.CONTENT_LABELERS
import app.logdate.server.atproto.ProxyException
import app.logdate.server.atproto.ProxyRequest
import app.logdate.server.atproto.ServiceAuthException
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import studio.hypertext.atproto.oauth.client.JvmOAuthHttp
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.syntax.Nsid

internal fun Application.atprotoServiceProxy(issuer: AtprotoServiceAuth): AtprotoServiceProxy {
    val http =
        JvmOAuthHttp.publicInternetClient {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }
    monitor.subscribe(ApplicationStopped) { http.close() }

    return AtprotoServiceProxy(http, issuer)
}

internal fun Route.xrpcServiceProxyRoutes(
    proxy: AtprotoServiceProxy,
    authenticate: suspend ApplicationCall.() -> XrpcPrincipal?,
) {
    // The target service owns the schema for each forwarded method.
    get("/{method}", { hidden = true }) { call.forwardServiceRequest(proxy, authenticate) }
    post("/{method}", { hidden = true }) { call.forwardServiceRequest(proxy, authenticate) }
}

private suspend fun ApplicationCall.forwardServiceRequest(
    proxy: AtprotoServiceProxy,
    authenticate: suspend ApplicationCall.() -> XrpcPrincipal?,
) {
    val method = parameters["method"].orEmpty()
    val references = request.headers.getAll("atproto-proxy")
    val reference = references?.singleOrNull() ?: defaultService(method)
    if (references != null && references.size != 1) {
        return respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "Provide one service DID and fragment."))
    }
    if (reference == null || Nsid.parse(method).isFailure) {
        return respond(HttpStatusCode.NotFound, PdsErrorResponse("MethodNotFound", "This method is unavailable."))
    }
    val principal = authenticate() ?: return
    if (method in LOCAL_PREFERENCES || !principal.mayUseServiceMethod(method)) {
        return respond(HttpStatusCode.Forbidden, PdsErrorResponse("Forbidden", "This method is outside the session grant."))
    }
    val result =
        try {
            proxy.forward(
                principal.account,
                reference,
                method,
                ProxyRequest(
                    method = request.httpMethod,
                    query = request.queryParameters,
                    accept = request.headers.getAll(HttpHeaders.Accept).orEmpty(),
                    labelers = request.headers.getAll(ACCEPT_LABELERS).orEmpty(),
                    contentType = request.headers[HttpHeaders.ContentType],
                    body = receiveChannel(),
                ),
            )
        } catch (error: ServiceAuthException) {
            val status =
                when (error.code) {
                    "Forbidden" -> HttpStatusCode.Forbidden
                    "TemporarilyUnavailable" -> HttpStatusCode.ServiceUnavailable
                    else -> HttpStatusCode.BadRequest
                }
            return respond(status, PdsErrorResponse(error.code, error.message))
        } catch (error: ProxyException) {
            return respond(error.status, PdsErrorResponse(error.code, error.message))
        }
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    if (!result.status.isSuccess()) {
        return respond(result.status, PdsErrorResponse("UpstreamFailure", "The service declined the request."))
    }
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Content-Security-Policy", "sandbox")
    result.labelers.forEach { response.headers.append(CONTENT_LABELERS, it) }
    val contentType = result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    respondBytes(result.body, contentType ?: ContentType.Application.OctetStream, result.status)
}

private fun defaultService(method: String): String? =
    when {
        method in LOCAL_PREFERENCES -> null
        method.startsWith("app.bsky.") -> BLUESKY_APPVIEW
        method.startsWith("chat.bsky.") -> BLUESKY_CHAT
        method == "com.atproto.moderation.createReport" -> BLUESKY_MODERATION
        else -> null
    }

private val LOCAL_PREFERENCES = setOf("app.bsky.actor.getPreferences", "app.bsky.actor.putPreferences")
