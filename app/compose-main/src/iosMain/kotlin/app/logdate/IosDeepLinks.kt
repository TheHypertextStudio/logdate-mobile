package app.logdate

import app.logdate.client.ui.navigation.DeepLinkAction
import app.logdate.client.ui.navigation.DeepLinkBus
import app.logdate.client.ui.navigation.LogdateHostClass
import app.logdate.client.ui.navigation.classifyLogdateHost
import io.github.aakira.napier.Napier
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import kotlin.uuid.Uuid

/**
 * Entry point for iOS URL handlers (custom-scheme or universal link). Returns true when the URL
 * was understood and routed to the app's navigation, false when the URL is ignored — Swift's
 * `application(_:open:options:)` and `application(_:continue:restorationHandler:)` should
 * propagate the bool back to the system.
 *
 * Recognized URL shapes:
 *   - `logdate://{type}/{id}` (legacy in-app scheme),
 *   - `studio.hypertext.logdate://{type}/{id}` (reverse-DNS, hijack-resistant),
 *   - `https://logdate.app/{type}/{id}` (apex web — covers the legacy
 *     `/j/{id}` short journal shape too),
 *   - `https://{handle}.logdate.app/{type}/{id}` (canonical tenant portal).
 *
 * Reserved subdomains (`app.logdate.app`, `api.logdate.app`, …) are not
 * claimed; the apex AASA in `logdate-web` already excludes them, so the
 * resolver only sees them if the URL is hand-fed (e.g. paste). Returning
 * null in that case keeps the system from looking like it accepted a URL
 * the app can't handle.
 */
@Suppress("ktlint:standard:function-naming")
fun HandleIosDeepLink(urlString: String): Boolean {
    val url = NSURL.URLWithString(urlString) ?: return false
    val action = parseAction(url)
    if (action == null) {
        Napier.d("HandleIosDeepLink: no match for $urlString")
        return false
    }
    DeepLinkBus.emit(action)
    return true
}

private fun parseAction(url: NSURL): DeepLinkAction? {
    val scheme = url.scheme?.lowercase() ?: return null
    val components = NSURLComponents.componentsWithURL(url, resolvingAgainstBaseURL = false) ?: return null
    val host = components.host?.lowercase().orEmpty()
    val path = components.path?.trim('/').orEmpty()
    val segments = if (path.isEmpty()) emptyList() else path.split('/')

    return when {
        scheme in APP_SCHEMES -> {
            resolveSegments(host, segments)
        }

        scheme.startsWith("http") && isLogdateHost(host) && segments.isNotEmpty() -> {
            resolveSegments(segments.first(), segments.drop(1))
        }

        else -> {
            null
        }
    }
}

private fun isLogdateHost(host: String): Boolean = classifyLogdateHost(host, LOGDATE_HOST) !is LogdateHostClass.Other

private fun resolveSegments(
    type: String,
    rest: List<String>,
): DeepLinkAction? {
    if (type == TYPE_LOCATION_TIMELINE) return DeepLinkAction.OpenLocationTimeline
    val first = rest.firstOrNull() ?: return null
    val id = parseUuid(first) ?: return null
    return when (type) {
        TYPE_JOURNAL, TYPE_JOURNAL_SHORT -> DeepLinkAction.OpenJournal(id)
        TYPE_NOTE -> DeepLinkAction.OpenNote(id)
        TYPE_REWIND -> DeepLinkAction.OpenRewind(id)
        else -> null
    }
}

private fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

private val APP_SCHEMES = setOf("logdate", "studio.hypertext.logdate")
private const val LOGDATE_HOST = "logdate.app"
private const val TYPE_JOURNAL = "journal"
private const val TYPE_JOURNAL_SHORT = "j"
private const val TYPE_NOTE = "note"
private const val TYPE_REWIND = "rewind"
private const val TYPE_LOCATION_TIMELINE = "location-timeline"
