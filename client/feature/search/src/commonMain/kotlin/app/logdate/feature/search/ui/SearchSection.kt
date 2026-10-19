package app.logdate.feature.search.ui

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult

/** The headings search results are listed under, in the order they are shown. */
enum class SearchSection {
    Journals,
    People,
    Notes,
}

/**
 * Sections [results] under journals, people and notes, leaving out any section with no results.
 * Everything that is not a journal or a person is listed with the notes. The order of [results]
 * (the ranking) is kept inside each section.
 */
fun sectionSearchResults(results: List<SearchResult>): List<Pair<SearchSection, List<SearchResult>>> =
    results
        .groupBy { result ->
            when (result.contentType) {
                SearchContentType.JOURNAL -> SearchSection.Journals
                SearchContentType.PERSON -> SearchSection.People
                else -> SearchSection.Notes
            }
        }.let { grouped -> SearchSection.entries.mapNotNull { section -> grouped[section]?.let { section to it } } }
