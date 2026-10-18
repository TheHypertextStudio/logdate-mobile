package app.logdate.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.search.DateRangeFilter
import app.logdate.client.domain.search.ObserveRecentSearchesUseCase
import app.logdate.client.domain.search.SearchFilters
import app.logdate.client.domain.search.SearchQuery
import app.logdate.client.domain.search.UniversalSearchUseCase
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultDetailsRepository
import app.logdate.client.repository.search.SearchResultKey
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the universal search screen.
 *
 * Manages search query state, recent searches, filter state (content types + date range), and
 * ranked results across all indexed content types.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val universalSearchUseCase: UniversalSearchUseCase,
    private val observeRecentSearchesUseCase: ObserveRecentSearchesUseCase,
    private val searchResultDetailsRepository: SearchResultDetailsRepository,
) : ViewModel() {
    private val queryState = MutableStateFlow(SearchQuery.Empty)
    private val filtersState = MutableStateFlow(SearchFilters.Default)
    private val searchDebounceMs = 150L

    private val settledQueryState: StateFlow<SearchQuery> =
        queryState
            .debounce(searchDebounceMs)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchQuery.Empty)

    private val latestResultsState: StateFlow<SearchResultSnapshot> =
        combine(settledQueryState, filtersState) { query, filters -> query to filters }
            .flatMapLatest { (settledQuery, filters) ->
                if (settledQuery.isBlank) {
                    flowOf(SearchResultSnapshot(settledQuery, emptyList()))
                } else {
                    val launchFilters =
                        filters.copy(contentTypes = filters.contentTypes ?: launchSearchContentTypes)
                    universalSearchUseCase(flowOf(settledQuery), launchFilters).map { results ->
                        SearchResultSnapshot(settledQuery, results)
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchResultSnapshot(SearchQuery.Empty, emptyList()),
            )

    /**
     * The media, dates and covers of the current results, keyed by result.
     *
     * Looked up once per settled result set. A result with no entry has nothing to show beyond its
     * text, and a failed lookup leaves every result showing just its text.
     */
    val resultDetails: StateFlow<Map<SearchResultKey, SearchResultDetail>> =
        latestResultsState
            .mapLatest { snapshot -> describe(snapshot.results) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail> {
        if (results.isEmpty()) return emptyMap()
        return try {
            searchResultDetailsRepository.describe(results)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w("Could not describe ${results.size} search results; showing them without media", error)
            emptyMap()
        }
    }

    val queryText: StateFlow<String> =
        queryState
            .map { it.text }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * The current search screen state: idle (with recents), searching, empty, or results.
     */
    val searchState: StateFlow<SearchScreenState> =
        combine(
            queryState,
            latestResultsState,
            observeRecentSearchesUseCase(),
        ) { query, latestResults, recentSearches ->
            when {
                query.isBlank -> SearchScreenState.Idle(recentSearches)
                query != latestResults.query -> SearchScreenState.Searching(query.text)
                latestResults.results.isEmpty() -> SearchScreenState.Empty(query.text)
                else -> SearchScreenState.Results(query.text, latestResults.results)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchScreenState.Idle(emptyList()),
        )

    /**
     * Read-only view of the active filter set so UI can render selected chips.
     *
     * Uses [asStateFlow] rather than [stateIn] because [filtersState] is already a hot
     * [MutableStateFlow] with no operator chain to share — `stateIn` would only add a coroutine
     * and a `WhileSubscribed` window that could let `filters.value` drift from `filtersState.value`
     * during gaps between subscribers.
     */
    val filters: StateFlow<SearchFilters> = filtersState.asStateFlow()

    /** Updates the search query text. */
    fun updateQuery(newQuery: String) {
        queryState.update { SearchQuery(newQuery) }
    }

    /**
     * Toggles a launch content-type filter chip. An empty selection restores the launch scope.
     */
    fun toggleContentType(type: SearchContentType) {
        if (type !in launchSearchContentTypes) return
        filtersState.update { current ->
            val active = current.contentTypes ?: emptySet()
            val next = if (type in active) active - type else active + type
            current.copy(contentTypes = next.takeIf { it.isNotEmpty() })
        }
    }

    /**
     * Sets the date-range filter (single-select).
     */
    fun setDateRange(range: DateRangeFilter) {
        filtersState.update { it.copy(dateRange = range) }
    }

    /**
     * Resets type and date filters to the defaults.
     */
    fun clearFilters() {
        filtersState.update { SearchFilters.Default }
    }

    /**
     * Records the current query as a recent search (call when user commits a search).
     */
    fun commitSearch() {
        val current = queryState.value
        if (current.isNotEmpty) {
            viewModelScope.launch {
                observeRecentSearchesUseCase.record(current.text)
            }
        }
    }

    /** Removes a single entry from the recent-searches list. */
    fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            observeRecentSearchesUseCase.remove(query)
        }
    }

    /** Clears the entire recent-searches list. */
    fun clearRecentSearches() {
        viewModelScope.launch {
            observeRecentSearchesUseCase.clear()
        }
    }
}

/**
 * UI state for the search screen.
 */
sealed interface SearchScreenState {
    /**
     * No active query. Show recent searches as suggestions.
     */
    data class Idle(
        val recentSearches: List<String>,
    ) : SearchScreenState

    /**
     * Query is still settling or the latest result set has not completed yet.
     */
    data class Searching(
        val query: String,
    ) : SearchScreenState

    /**
     * Query submitted but no results found.
     */
    data class Empty(
        val query: String,
    ) : SearchScreenState

    /**
     * Ranked results from universal search.
     */
    data class Results(
        val query: String = "",
        val results: List<SearchResult>,
    ) : SearchScreenState
}

private data class SearchResultSnapshot(
    val query: SearchQuery,
    val results: List<SearchResult>,
)
