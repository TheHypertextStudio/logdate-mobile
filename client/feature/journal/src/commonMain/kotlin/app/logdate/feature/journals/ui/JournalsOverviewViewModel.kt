package app.logdate.feature.journals.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.journals.JournalSuggestion
import app.logdate.client.domain.journals.SuggestJournalsUseCase
import app.logdate.client.domain.search.SearchEntriesUseCase
import app.logdate.client.domain.search.SearchJournalsUseCase
import app.logdate.client.domain.search.SearchQuery
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.shared.model.Journal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the journals overview screen.
 *
 * Combines journals from the repository with the user-selected layout mode and sort order.
 * Layout mode is persisted via DataStore; sort order is session-scoped.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class JournalsOverviewViewModel(
    private val repository: JournalRepository,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val searchJournalsUseCase: SearchJournalsUseCase,
    private val searchEntriesUseCase: SearchEntriesUseCase,
    private val suggestJournalsUseCase: SuggestJournalsUseCase,
) : ViewModel() {
    private val _suggestions = MutableStateFlow<List<JournalSuggestion>>(emptyList())
    val suggestions: StateFlow<List<JournalSuggestion>> = _suggestions

    init {
        loadSuggestions()
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            _suggestions.value = suggestJournalsUseCase()
        }
    }

    private val entrySearchDebounceMs = 150L
    private val sortOptionState = MutableStateFlow(JournalSortOption.LAST_UPDATED)
    private val searchQueryState = MutableStateFlow(SearchQuery.Empty)

    private val layoutModeFlow =
        preferencesDataSource.observeJournalLayoutMode().map { name ->
            try {
                JournalLayoutMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                JournalLayoutMode.CAROUSEL
            }
        }

    private val settledEntrySearchQueryState: StateFlow<SearchQuery> =
        searchQueryState
            .debounce(entrySearchDebounceMs)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchQuery.Empty)

    private val latestEntrySearchState: StateFlow<EntrySearchSnapshot> =
        settledEntrySearchQueryState
            .flatMapLatest { settledQuery ->
                if (settledQuery.isBlank) {
                    flowOf(EntrySearchSnapshot(settledQuery, emptyList()))
                } else {
                    searchEntriesUseCase
                        .searchWithLimit(flowOf(settledQuery), limit = 5)
                        .map { results -> EntrySearchSnapshot(settledQuery, results) }
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                EntrySearchSnapshot(SearchQuery.Empty, emptyList()),
            )

    val uiState: StateFlow<JournalsOverviewUiState> =
        combine(
            repository.allJournalsObserved,
            layoutModeFlow,
            sortOptionState,
            searchQueryState,
        ) { journals, layoutMode, sortOption, searchQuery ->
            val filtered = searchJournalsUseCase.filterJournals(journals, searchQuery)
            val sorted = applySorting(filtered, sortOption)
            val items =
                sorted.map { journal ->
                    JournalListItemUiState.ExistingJournal(journal)
                } + JournalListItemUiState.CreateJournalPlaceholder

            JournalsOverviewSnapshot(
                journals = items,
                layoutMode = layoutMode,
                sortOption = sortOption,
                searchQuery = searchQuery,
            )
        }.combine(latestEntrySearchState) { snapshot, latestEntrySearch ->
            JournalsOverviewUiState(
                journals = snapshot.journals,
                layoutMode = snapshot.layoutMode,
                sortOption = snapshot.sortOption,
                searchQuery = snapshot.searchQuery.text,
                isEntrySearchInProgress =
                    snapshot.searchQuery.text.isNotBlank() &&
                        snapshot.searchQuery != latestEntrySearch.query,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalsOverviewUiState())

    fun updateSearchQuery(query: String) {
        searchQueryState.value = SearchQuery(query)
    }

    fun toggleLayoutMode() {
        viewModelScope.launch {
            val current = uiState.value.layoutMode
            val next =
                when (current) {
                    JournalLayoutMode.GRID -> JournalLayoutMode.CAROUSEL
                    JournalLayoutMode.CAROUSEL -> JournalLayoutMode.GRID
                }
            preferencesDataSource.setJournalLayoutMode(next.name)
        }
    }

    fun setSortOption(option: JournalSortOption) {
        sortOptionState.value = option
    }

    private fun applySorting(
        journals: List<Journal>,
        sortOption: JournalSortOption,
    ): List<Journal> =
        when (sortOption) {
            JournalSortOption.LAST_UPDATED -> journals.sortedByDescending { it.lastUpdated }
            JournalSortOption.CREATED -> journals.sortedByDescending { it.created }
            JournalSortOption.TITLE -> journals.sortedBy { it.title }
        }
}

private data class EntrySearchSnapshot(
    val query: SearchQuery,
    val results: List<SearchResult>,
)

private data class JournalsOverviewSnapshot(
    val journals: List<JournalListItemUiState>,
    val layoutMode: JournalLayoutMode,
    val sortOption: JournalSortOption,
    val searchQuery: SearchQuery,
)
