package app.logdate.client.domain.search

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A calendar month in the local time zone. */
data class MonthBucket(
    val year: Int,
    val month: Int,
)

/**
 * How many results each narrowing option would leave.
 *
 * Each count follows every narrowing in the filters except its own, so a count is the number of
 * results the person would see after choosing that option.
 *
 * @property byType Result counts by content type.
 * @property byPerson Counts of results linked to each person.
 * @property byPlace Counts of results captured at each place.
 * @property byMonth Result counts by local calendar month, ignoring any date narrowing.
 */
data class SearchFacetCounts(
    val byType: Map<SearchContentType, Int> = emptyMap(),
    val byPerson: Map<Uuid, Int> = emptyMap(),
    val byPlace: Map<Uuid, Int> = emptyMap(),
    val byMonth: Map<MonthBucket, Int> = emptyMap(),
) {
    /** Result counts by local calendar year, ignoring any date narrowing. */
    val byYear: Map<Int, Int> =
        byMonth.entries
            .groupBy({ it.key.year }, { it.value })
            .mapValues { (_, counts) -> counts.sum() }

    companion object {
        /** Counts for a search with no query. */
        val Empty = SearchFacetCounts()
    }
}

/**
 * Counts how many results each narrowing option of a search would leave.
 *
 * Creation times are bucketed by month in the current time zone, so a result created late on the
 * last evening of a month counts toward that month rather than the next one in UTC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveSearchFacetsUseCase(
    private val searchRepository: SearchRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /**
     * @param queryFlow Flow of search queries from the UI
     * @param filters The narrowing currently applied
     * @return Flow of counts, empty while the query is blank
     */
    operator fun invoke(
        queryFlow: Flow<SearchQuery>,
        filters: SearchFilters = SearchFilters.Default,
    ): Flow<SearchFacetCounts> =
        queryFlow
            .map { it.text }
            .distinctUntilChanged()
            .flatMapLatest { queryText ->
                if (queryText.isBlank()) {
                    flowOf(SearchFacetCounts.Empty)
                } else {
                    val zone = timeZone()
                    searchRepository
                        .observeFacets(queryText, filters.toScope(clock.now(), zone))
                        .map { facets ->
                            SearchFacetCounts(
                                byType = facets.byType,
                                byPerson = facets.byPerson,
                                byPlace = facets.byPlace,
                                byMonth =
                                    facets.createdAt
                                        .map { it.toLocalDateTime(zone) }
                                        .groupingBy { MonthBucket(it.year, it.month.ordinal + 1) }
                                        .eachCount(),
                            )
                        }
                }
            }
}
