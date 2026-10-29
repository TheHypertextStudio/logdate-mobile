package app.logdate.client.device.auth

import app.logdate.shared.model.BlueskyBrowserReturn

/** Only the fixed browser return is eligible to resume an already pending app authorization. */
object BlueskyAppReturn {
    fun attemptId(url: String): String? {
        val prefix = PREFIXES.firstOrNull(url::startsWith) ?: return null
        val id = url.removePrefix(prefix)
        return id.takeIf { it.length == 43 && IDENTIFIER.matches(it) }
    }

    private val PREFIXES = listOf("${BlueskyBrowserReturn.URL}?attempt=", "${BlueskyBrowserReturn.NATIVE_URL}?attempt=")
    private val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")
}
