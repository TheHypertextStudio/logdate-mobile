package app.logdate.feature.search.ui

import app.logdate.client.domain.search.ObserveRecentSearchesUseCase
import app.logdate.client.domain.search.ObserveSearchFacetsUseCase
import app.logdate.client.domain.search.UniversalSearchUseCase
import app.logdate.client.repository.search.RecentSearchesRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultDetailsRepository
import app.logdate.client.repository.search.SearchResultKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for [SearchViewModel].
 *
 * Verifies the end-to-end search flow from query input to result display,
 * including debouncing logic, handling of empty or short queries, and
 * integration with recent search history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `launch search excludes deferred people results even without a selected filter`() =
        runTest(dispatcher) {
            val repository =
                FakeSearchRepository(
                    listOf(
                        SearchResult(Uuid.random(), "Morning walk", Instant.fromEpochMilliseconds(1_000), SearchContentType.TEXT_NOTE),
                        SearchResult(Uuid.random(), "Morning walk", Instant.fromEpochMilliseconds(1_000), SearchContentType.PERSON),
                    ),
                )
            val viewModel =
                SearchViewModel(
                    UniversalSearchUseCase(repository),
                    ObserveRecentSearchesUseCase(FakeRecentSearchesRepository()),
                    NoDetails,
                    ObserveSearchFacetsUseCase(repository),
                )
            backgroundScope.launch { viewModel.searchState.collect() }
            runCurrent()

            viewModel.updateQuery("Morning")
            advanceTimeBy(150)
            advanceUntilIdle()

            val resultState = assertIs<SearchScreenState.Results>(viewModel.searchState.value)
            assertEquals(listOf(SearchContentType.TEXT_NOTE), resultState.results.map { it.contentType })
        }

    @Test
    fun `typing shows searching until debounced results arrive`() =
        runTest(dispatcher) {
            val repository =
                FakeSearchRepository(
                    listOf(
                        SearchResult(
                            uid = Uuid.random(),
                            content = "Hiking through the redwoods",
                            created = Instant.fromEpochMilliseconds(1_000),
                            contentType = app.logdate.client.repository.search.SearchContentType.TEXT_NOTE,
                        ),
                    ),
                )
            val recentSearchesRepository = FakeRecentSearchesRepository(listOf("sunset"))
            val viewModel =
                SearchViewModel(
                    universalSearchUseCase = UniversalSearchUseCase(repository),
                    observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(recentSearchesRepository),
                    searchResultDetailsRepository = NoDetails,
                    observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(repository),
                )
            backgroundScope.launch { viewModel.searchState.collect() }
            runCurrent()

            viewModel.updateQuery("hik")
            runCurrent()

            assertIs<SearchScreenState.Searching>(viewModel.searchState.value)

            advanceTimeBy(150)
            advanceUntilIdle()

            val resultState = assertIs<SearchScreenState.Results>(viewModel.searchState.value)
            assertEquals("hik", resultState.query)
            assertEquals(1, resultState.results.size)
        }

    @Test
    fun `single character queries still return live results`() =
        runTest(dispatcher) {
            val repository =
                FakeSearchRepository(
                    listOf(
                        SearchResult(
                            uid = Uuid.random(),
                            content = "A quiet morning walk",
                            created = Instant.fromEpochMilliseconds(1_000),
                            contentType = app.logdate.client.repository.search.SearchContentType.TEXT_NOTE,
                        ),
                    ),
                )
            val viewModel =
                SearchViewModel(
                    universalSearchUseCase = UniversalSearchUseCase(repository),
                    observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(FakeRecentSearchesRepository()),
                    searchResultDetailsRepository = NoDetails,
                    observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(repository),
                )
            backgroundScope.launch { viewModel.searchState.collect() }
            runCurrent()

            viewModel.updateQuery("a")
            runCurrent()
            advanceTimeBy(150)
            advanceUntilIdle()

            val resultState = assertIs<SearchScreenState.Results>(viewModel.searchState.value)
            assertEquals("a", resultState.query)
            assertEquals(1, resultState.results.size)
        }

    private class FakeRecentSearchesRepository(
        recentSearches: List<String> = emptyList(),
    ) : RecentSearchesRepository {
        private val flow = MutableStateFlow(recentSearches)

        override fun observeRecentSearches(): Flow<List<String>> = flow

        override suspend fun addRecentSearch(query: String) {
            flow.value = listOf(query) + flow.value.filterNot { it == query }
        }

        override suspend fun removeRecentSearch(query: String) {
            flow.value = flow.value.filterNot { it == query }
        }

        override suspend fun clearRecentSearches() {
            flow.value = emptyList()
        }
    }

    private object NoDetails : SearchResultDetailsRepository {
        override suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail> = emptyMap()
    }

    private class FakeSearchRepository(
        initialResults: List<SearchResult> = emptyList(),
    ) : SearchRepository {
        private val resultsFlow = MutableStateFlow(initialResults)

        override fun search(query: String): Flow<List<SearchResult>> =
            resultsFlow.map { results ->
                if (query.isBlank()) {
                    emptyList()
                } else {
                    results.filter { it.content.contains(query, ignoreCase = true) }
                }
            }

        override fun searchWithLimit(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query).map { it.take(limit) }

        override fun searchWithSnippets(query: String): Flow<List<SearchResult>> = search(query)

        override fun searchRanked(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query).map { it.take(limit) }

        override fun searchInJournal(
            query: String,
            journalId: Uuid,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query).map { it.take(limit) }
    }
}
