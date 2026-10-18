package app.logdate.feature.search.ui

import androidx.lifecycle.SavedStateHandle
import app.logdate.client.domain.search.DateRangeFilter
import app.logdate.client.domain.search.ObserveRecentSearchesUseCase
import app.logdate.client.domain.search.ObserveSearchFacetsUseCase
import app.logdate.client.domain.search.SearchFilters
import app.logdate.client.domain.search.UniversalSearchUseCase
import app.logdate.client.repository.search.RecentSearchesRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchFacetLabels
import app.logdate.client.repository.search.SearchFacets
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultDetailsRepository
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.SearchScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelRestorationTest {
    private val dispatcher = StandardTestDispatcher()
    private val person = Uuid.random()
    private val place = Uuid.random()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a new search starts empty`() =
        runTest(dispatcher) {
            val viewModel = viewModel(SavedStateHandle())

            assertEquals("", viewModel.queryText.value)
            assertEquals(SearchFilters.Default, viewModel.filters.value)
        }

    @Test
    fun `the query is restored when the screen is recreated`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            viewModel(saved).updateQuery("lake days")

            assertEquals("lake days", currentQuery(viewModel(saved)))
        }

    @Test
    fun `every narrowing is restored when the screen is recreated`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val first = viewModel(saved)
            first.toggleContentType(SearchContentType.JOURNAL)
            first.toggleContentType(SearchContentType.REWIND)
            first.togglePerson(person)
            first.togglePlace(place)
            first.toggleYear(2025)
            first.setDateRange(DateRangeFilter.ThisYear)

            assertEquals(
                SearchFilters(
                    contentTypes = setOf(SearchContentType.JOURNAL, SearchContentType.REWIND),
                    dateRange = DateRangeFilter.ThisYear,
                    personId = person,
                    placeId = place,
                    year = 2025,
                ),
                viewModel(saved).filters.value,
            )
        }

    @Test
    fun `clearing the narrowing and the query is restored as cleared`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val first = viewModel(saved)
            first.updateQuery("lake")
            first.togglePerson(person)
            first.toggleYear(2025)
            first.clearFilters()
            first.updateQuery("")

            val restored = viewModel(saved)

            assertEquals("", currentQuery(restored))
            assertEquals(SearchFilters.Default, restored.filters.value)
        }

    @Test
    fun `choosing a narrowing again clears it from what is restored`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val first = viewModel(saved)
            first.togglePerson(person)
            first.togglePerson(person)

            assertEquals(null, viewModel(saved).filters.value.personId)
        }

    @Test
    fun `saved values that are not narrowing are ignored`() =
        runTest(dispatcher) {
            val saved =
                SavedStateHandle(
                    mapOf(
                        "search_types" to "not_a_type,journal",
                        "search_person" to "not-a-uuid",
                        "search_place" to place.toString(),
                        "search_date_range" to "Someday",
                    ),
                )

            val restored = viewModel(saved).filters.value

            assertEquals(null, restored.personId)
            assertEquals(place, restored.placeId)
            assertEquals(setOf(SearchContentType.JOURNAL), restored.contentTypes)
            assertEquals(DateRangeFilter.AllTime, restored.dateRange)
        }

    private fun TestScope.currentQuery(viewModel: SearchViewModel): String {
        backgroundScope.launch { viewModel.queryText.collect {} }
        runCurrent()
        return viewModel.queryText.value
    }

    private fun viewModel(saved: SavedStateHandle): SearchViewModel {
        val repository = EmptyRepository()
        return SearchViewModel(
            universalSearchUseCase = UniversalSearchUseCase(repository),
            observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(EmptyRecents()),
            searchResultDetailsRepository = NoDetails(),
            observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(repository),
            savedStateHandle = saved,
        )
    }

    private class NoDetails : SearchResultDetailsRepository {
        override suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail> = emptyMap()

        override suspend fun labelFacets(
            personIds: Collection<Uuid>,
            placeIds: Collection<Uuid>,
        ): SearchFacetLabels = SearchFacetLabels.Empty
    }

    private class EmptyRepository : SearchRepository {
        override fun search(query: String): Flow<List<SearchResult>> = flowOf(emptyList())

        override fun searchWithLimit(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)

        override fun searchWithSnippets(query: String): Flow<List<SearchResult>> = search(query)

        override fun searchRanked(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)

        override fun searchScoped(
            query: String,
            scope: SearchScope,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)

        override fun observeFacets(
            query: String,
            scope: SearchScope,
        ): Flow<SearchFacets> = flowOf(SearchFacets.Empty)

        override fun searchInJournal(
            query: String,
            journalId: Uuid,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)
    }

    private class EmptyRecents : RecentSearchesRepository {
        override fun observeRecentSearches(): Flow<List<String>> = flowOf(emptyList())

        override suspend fun addRecentSearch(query: String) = Unit

        override suspend fun removeRecentSearch(query: String) = Unit

        override suspend fun clearRecentSearches() = Unit
    }
}
