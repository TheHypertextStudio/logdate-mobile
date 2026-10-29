package app.logdate.server.routes

import app.logdate.server.bluesky.BlueskySignInFailure
import app.logdate.server.bluesky.BlueskySignInService
import app.logdate.shared.model.BlueskyBrowserReturn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException

/** The provider callback may return a public attempt ID, never credentials or an arbitrary destination. */
internal fun Route.blueskyCallbackRoutes(signIn: BlueskySignInService) {
    get("/oauth/bluesky/callback") {
        val parameters = callbackParameters(call.request.queryParameters)
        val target =
            if (parameters == null) {
                BlueskyBrowserReturn.ERROR_URL
            } else {
                try {
                    appReturn(signIn.callback(parameters))
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (failure: BlueskySignInFailure) {
                    appReturn(failure.verifiedAttemptId)
                } catch (_: Exception) {
                    BlueskyBrowserReturn.ERROR_URL
                }
            }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append(HttpHeaders.Location, target)
        call.respond(HttpStatusCode.SeeOther)
    }
}

private fun appReturn(id: String?): String =
    if (id != null && id.matches(Regex("[A-Za-z0-9_-]{43}"))) "${BlueskyBrowserReturn.URL}?attempt=$id" else BlueskyBrowserReturn.ERROR_URL

private fun callbackParameters(parameters: Parameters): Map<String, String>? {
    val entries = parameters.entries()
    if (entries.size > 16 || entries.any { it.value.size != 1 || it.value.single().length > 4096 }) return null
    val values = entries.associate { it.key to it.value.single() }
    if (values["state"]?.matches(Regex("[A-Za-z0-9_-]{43}")) != true || values["iss"].isNullOrBlank()) return null
    val hasCode = !values["code"].isNullOrBlank()
    val hasError = !values["error"].isNullOrBlank()
    if (hasCode == hasError) return null
    return values
}
