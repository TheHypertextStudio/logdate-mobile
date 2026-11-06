package app.logdate.server.routes

import app.logdate.server.atproto.BlueskyPreferences
import app.logdate.server.atproto.BlueskyPreferencesStore
import app.logdate.server.oauth.OAuthScopes
import app.logdate.server.routes.docs.BlueskyPreferencesDocs
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.syntax.Nsid
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
internal fun Route.blueskyPreferencesRoutes(
    store: BlueskyPreferencesStore?,
    authenticate: suspend ApplicationCall.() -> XrpcPrincipal?,
) {
    get("/app.bsky.actor.getPreferences", BlueskyPreferencesDocs.get) {
        val repository = store ?: return@get call.preferencesUnavailable()
        val principal = call.authenticate() ?: return@get
        if (!call.allowBlueskyPreferences(principal)) return@get
        call.respond(BlueskyPreferences(repository.read(principal.account.id)))
    }
    post("/app.bsky.actor.putPreferences", BlueskyPreferencesDocs.put) {
        val repository = store ?: return@post call.preferencesUnavailable()
        val principal = call.authenticate() ?: return@post
        if (!call.allowBlueskyPreferences(principal)) return@post
        val preferences = call.readBlueskyPreferences() ?: return@post
        repository.replace(principal.account.id, preferences)
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun ApplicationCall.allowBlueskyPreferences(principal: XrpcPrincipal): Boolean {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val account = principal.account
    if (!account.isActive || account.moderation != null || account.did == null || account.publicIdentityDid != account.did) {
        respond(HttpStatusCode.Forbidden, PdsErrorResponse("Forbidden", "Preferences require an active public identity."))
        return false
    }
    if (principal.oauth?.scopes?.contains(OAuthScopes.TRANSITION_GENERIC) == false) {
        respond(HttpStatusCode.Forbidden, PdsErrorResponse("InsufficientScope", "This app has not been granted access to preferences."))
        return false
    }
    return true
}

private suspend fun ApplicationCall.readBlueskyPreferences(): List<JsonElement>? {
    val bytes = receiveChannel().readRemaining(MAX_BLUESKY_PREFERENCES_BYTES + 1).readByteArray()
    if (bytes.size > MAX_BLUESKY_PREFERENCES_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, PdsErrorResponse("PayloadTooLarge", "The preferences are too large."))
        return null
    }
    val entries =
        try {
            Json.decodeFromString<BlueskyPreferences>(bytes.decodeToString()).preferences
        } catch (_: SerializationException) {
            return invalidPreferences()
        }
    val types = entries.map(::blueskyPreferenceType)
    if (types.any { it == null || it == PERSONAL_DETAILS }) return invalidPreferences()
    return entries.filterIndexed { index, _ -> types[index] != DECLARED_AGE }
}

private fun blueskyPreferenceType(entry: JsonElement): String? {
    val type = (entry as? JsonObject)?.get("\$type") as? JsonPrimitive ?: return null
    return type.content.takeIf {
        type.isString && it.startsWith("app.bsky.") && Nsid.parse(it.substringBefore('#')).isSuccess &&
            !it.endsWith('#') && it.count { character -> character == '#' } <= 1
    }
}

private suspend fun ApplicationCall.invalidPreferences(): List<JsonElement>? {
    respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "Provide valid Bluesky preferences."))
    return null
}

private suspend fun ApplicationCall.preferencesUnavailable() =
    respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "Preferences are unavailable."))

internal const val MAX_BLUESKY_PREFERENCES_BYTES = 256L * 1024
private const val PERSONAL_DETAILS = "app.bsky.actor.defs#personalDetailsPref"
private const val DECLARED_AGE = "app.bsky.actor.defs#declaredAgePref"
