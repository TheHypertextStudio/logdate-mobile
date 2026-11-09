package app.logdate.client

import android.content.Intent
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult

/**
 * Builds and dispatches an `ACTION_SEND` chooser for a search result. Wired into
 * [app.logdate.navigation.LogDateNavDisplay]'s `onShareSearchResult` so the long-press /
 * right-click bottom sheet surfaces a Share action on Android. Other platforms leave the
 * parameter null and the action is hidden from the sheet.
 */
internal fun MainActivity.shareSearchResult(result: SearchResult) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            val snippet = result.content.replace("[", "").replace("]", "")
            val url = canonicalSearchResultUrl(result)
            val body =
                if (url != null) {
                    if (snippet.isBlank()) url else "$snippet\n\n$url"
                } else {
                    snippet
                }
            putExtra(Intent.EXTRA_TEXT, body)
        }
    startActivity(Intent.createChooser(intent, null))
}

private fun canonicalSearchResultUrl(result: SearchResult): String? {
    val origin = BuildConfig.LOGDATE_API_BASE_URL.removeSuffix("/")
    return when (result.contentType) {
        SearchContentType.JOURNAL -> "$origin/journal/${result.uid}"
        SearchContentType.TEXT_NOTE -> "$origin/note/${result.uid}"
        SearchContentType.POSTCARD -> "$origin/postcard/${result.uid}"
        SearchContentType.REWIND -> "$origin/rewind/${result.uid}"
        else -> null
    }
}
