package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.feature.core.settings.navigation.PersonDetailRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.feature.library.navigation.MediaDetailRoute
import app.logdate.feature.postcards.navigation.PostcardViewerRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Dispatches a search-result tap to the most specific entry-detail route available, falling back
 * to the containing day for content types without their own detail screen.
 */
internal fun searchResultRoute(result: SearchResult): NavKey =
    when (result.contentType) {
        SearchContentType.JOURNAL -> JournalDetailsRoute(result.uid)

        SearchContentType.PERSON -> PersonDetailRoute(result.uid)

        SearchContentType.TEXT_NOTE -> NoteDetailRoute(result.uid)

        SearchContentType.POSTCARD -> PostcardViewerRoute(result.uid)

        SearchContentType.REWIND -> RewindDetailRoute(result.uid)

        SearchContentType.MEDIA_CAPTION -> MediaDetailRoute(result.uid)

        SearchContentType.TRANSCRIPTION,
        SearchContentType.AMBIENT_SOUND,
        SearchContentType.STICKER,
        SearchContentType.PLACE,
        -> searchResultDayRoute(result)
    }

/**
 * Resolves a search result's containing day route, threading the entry's UUID so the timeline
 * panel can scroll to the matching entry when that hook lands. Used both as the fallback in
 * [searchResultRoute] and by the long-press "Open day view" action.
 */
internal fun searchResultDayRoute(result: SearchResult): TimelineDetailRoute {
    val date =
        result.created
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    return TimelineDetailRoute(date.toString(), entryId = result.uid.toString())
}
