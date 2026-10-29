package app.logdate.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient

internal fun Route.blueskyMetadataRoutes(client: AtprotoOAuthClient?) {
    if (client == null) return
    get("/oauth/client-metadata.json") {
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(client.clientMetadataJson(), ContentType.Application.Json)
    }
}
