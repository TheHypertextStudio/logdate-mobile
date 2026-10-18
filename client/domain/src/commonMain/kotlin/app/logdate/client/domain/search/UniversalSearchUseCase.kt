package app.logdate.client.domain.search

import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Orchestrates universal search across all indexed content types.
 *
 * Debouncing is handled by the caller so the UI can represent in-flight search
 * more accurately. Results are ranked by local FTS relevance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UniversalSearchUseCase(
    private val searchRepository: SearchRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /**
     * Searches all indexed content with optional narrowing.
     *
     * The filters are resolved to a scope and applied by the repository inside its query, before
     * the result limit, so narrowing never hides matches that the limit would have cut. Relative
     * date windows are resolved against [clock] when the search starts.
     *
     * @param queryFlow Flow of search queries from the UI
     * @param filters Optional filters to narrow result types, people, places, dates, or count
     * @return Flow of ranked search results across all entity types
     */
    operator fun invoke(
        queryFlow: Flow<SearchQuery>,
        filters: SearchFilters = SearchFilters.Default,
    ): Flow<List<SearchResult>> =
        queryFlow
            .map { it.text }
            .distinctUntilChanged()
            .flatMapLatest { queryText ->
                if (queryText.isBlank()) {
                    flowOf(emptyList())
                } else {
                    searchRepository.searchScoped(
                        query = queryText,
                        scope = filters.toScope(clock.now(), timeZone()),
                        limit = filters.maxResults,
                    )
                }
            }
}
