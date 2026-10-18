package app.logdate.feature.search.ui

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** How a kind of search result is presented, which decides the group it appears in. */
enum class SearchResultShape {
    /** A thing in its own right rather than an entry on a day: a person, a place or a journal. */
    Entity,

    /** A curated set with a cover and a period: a rewind or a postcard. */
    Collection,

    /** Something captured on a particular day: a note, a recording, a photo, a sticker or a sound. */
    DayItem,
}

/** The [SearchResultShape] this kind of result is presented in. */
val SearchContentType.shape: SearchResultShape
    get() =
        when (this) {
            SearchContentType.PERSON,
            SearchContentType.PLACE,
            SearchContentType.JOURNAL,
            -> SearchResultShape.Entity
            SearchContentType.REWIND,
            SearchContentType.POSTCARD,
            -> SearchResultShape.Collection
            SearchContentType.TEXT_NOTE,
            SearchContentType.TRANSCRIPTION,
            SearchContentType.MEDIA_CAPTION,
            SearchContentType.STICKER,
            SearchContentType.AMBIENT_SOUND,
            -> SearchResultShape.DayItem
        }

/** The results of one local calendar day, in the order they happened. */
data class SearchDayGroup(
    val date: LocalDate,
    val results: List<SearchResult>,
)

/**
 * Search results arranged by what they are.
 *
 * @property topMatches People, places and journals, in that order, each kept in rank order.
 * @property collections Rewinds and postcards, newest first.
 * @property days Entries grouped by local day, newest day first, each day in time order.
 */
data class SearchResultGroups(
    val topMatches: List<SearchResult>,
    val collections: List<SearchResult>,
    val days: List<SearchDayGroup>,
) {
    /** The number of results in all groups. */
    val total: Int get() = topMatches.size + collections.size + days.sumOf { it.results.size }

    /** True when there are no results in any group. */
    val isEmpty: Boolean get() = total == 0
}

private val topMatchOrder = listOf(SearchContentType.PERSON, SearchContentType.PLACE, SearchContentType.JOURNAL)

/**
 * Arranges [results] into [SearchResultGroups], placing each result by the [shape][shape] of its
 * content type and dating entries in [timeZone].
 */
fun groupSearchResults(
    results: List<SearchResult>,
    timeZone: TimeZone,
): SearchResultGroups {
    val byShape = results.groupBy { it.contentType.shape }
    val entities = byShape[SearchResultShape.Entity].orEmpty()
    return SearchResultGroups(
        topMatches = topMatchOrder.flatMap { type -> entities.filter { it.contentType == type } },
        collections = byShape[SearchResultShape.Collection].orEmpty().sortedByDescending { it.created },
        days =
            byShape[SearchResultShape.DayItem]
                .orEmpty()
                .groupBy { it.created.toLocalDateTime(timeZone).date }
                .map { (date, items) -> SearchDayGroup(date, items.sortedBy { it.created }) }
                .sortedByDescending { it.date },
    )
}
