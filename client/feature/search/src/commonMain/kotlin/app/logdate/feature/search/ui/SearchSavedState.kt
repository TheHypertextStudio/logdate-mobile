package app.logdate.feature.search.ui

import androidx.lifecycle.SavedStateHandle
import app.logdate.client.domain.search.DateRangeFilter
import app.logdate.client.domain.search.SearchFilters
import app.logdate.client.repository.search.SearchContentType
import kotlin.uuid.Uuid

private const val QUERY_KEY = "search_query"
private const val TYPES_KEY = "search_types"
private const val PERSON_KEY = "search_person"
private const val PLACE_KEY = "search_place"
private const val YEAR_KEY = "search_year"
private const val DATE_RANGE_KEY = "search_date_range"

/** The query text that was being searched before the screen was recreated, or empty. */
internal fun SavedStateHandle.savedQuery(): String = get<String>(QUERY_KEY).orEmpty()

internal fun SavedStateHandle.saveQuery(text: String) {
    this[QUERY_KEY] = text
}

/**
 * The narrowing that was chosen before the screen was recreated.
 *
 * A saved value that no longer names a narrowing option is ignored, so an old or damaged save
 * leaves that narrowing unset instead of failing the screen.
 */
internal fun SavedStateHandle.savedFilters(): SearchFilters {
    val types =
        get<String>(TYPES_KEY)
            ?.split(',')
            ?.mapNotNull { name -> SearchContentType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
    return SearchFilters(
        contentTypes = types,
        dateRange =
            get<String>(DATE_RANGE_KEY)?.let { name -> DateRangeFilter.entries.firstOrNull { it.name == name } } ?: DateRangeFilter.AllTime,
        personId = get<String>(PERSON_KEY)?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        placeId = get<String>(PLACE_KEY)?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        year = get<Int>(YEAR_KEY),
    )
}

internal fun SavedStateHandle.saveFilters(filters: SearchFilters) {
    this[TYPES_KEY] = filters.contentTypes?.joinToString(",") { it.name }
    this[DATE_RANGE_KEY] = filters.dateRange.name
    this[PERSON_KEY] = filters.personId?.toString()
    this[PLACE_KEY] = filters.placeId?.toString()
    this[YEAR_KEY] = filters.year
}
