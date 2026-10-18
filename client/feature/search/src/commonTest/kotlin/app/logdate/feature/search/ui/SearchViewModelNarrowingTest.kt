package app.logdate.feature.search.ui

import app.logdate.client.domain.search.MonthBucket
import app.logdate.client.domain.search.ObserveRecentSearchesUseCase
import app.logdate.client.domain.search.ObserveSearchFacetsUseCase
import app.logdate.client.domain.search.SearchFacetCounts
import app.logdate.client.domain.search.SearchFilters
import app.logdate.client.domain.search.UniversalSearchUseCase
import app.logdate.client.repository.search.RecentSearchesRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchFacetLabels
import app.logdate.client.repository.search.SearchFacets
import app.logdate.client.repository.search.SearchPersonLabel
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
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelNarrowingTest {
    private val dispatcher = StandardTestDispatcher()
    private val sam = Uuid.random()
    private val maya = Uuid.random()
    private val lake = Uuid.random()
    private val home = Uuid.random()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `choosing a person place or year narrows and choosing it again clears it`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.togglePerson(sam)
            viewModel.togglePlace(lake)
            viewModel.toggleYear(2025)
            assertEquals(sam, viewModel.filters.value.personId)
            assertEquals(lake, viewModel.filters.value.placeId)
            assertEquals(2025, viewModel.filters.value.year)

            viewModel.togglePerson(sam)
            viewModel.togglePlace(lake)
            viewModel.toggleYear(2025)
            assertNull(viewModel.filters.value.personId)
            assertNull(viewModel.filters.value.placeId)
            assertNull(viewModel.filters.value.year)
        }

    @Test
    fun `choosing another person replaces the first`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.togglePerson(sam)
            viewModel.togglePerson(maya)

            assertEquals(maya, viewModel.filters.value.personId)
        }

    @Test
    fun `clearing filters clears every narrowing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.togglePerson(sam)
            viewModel.togglePlace(lake)
            viewModel.toggleYear(2025)
            viewModel.toggleContentType(SearchContentType.TEXT_NOTE)

            viewModel.clearFilters()

            assertEquals(SearchFilters.Default, viewModel.filters.value)
        }

    @Test
    fun `narrowing reaches the search as a scope`() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository = repository)
            observe(viewModel)
            viewModel.togglePlace(lake)
            viewModel.toggleYear(2025)

            viewModel.updateQuery("lake")
            advanceTimeBy(150)
            advanceUntilIdle()

            assertEquals(lake, repository.lastSearchScope?.placeId)
            assertEquals(launchSearchContentTypes, repository.lastSearchScope?.contentTypes)
            assertEquals(true, repository.lastSearchScope?.createdFrom != null)
        }

    @Test
    fun `counts and names reach the narrowing state and only launch types are counted`() =
        runTest(dispatcher) {
            val facets =
                SearchFacets(
                    byType =
                        mapOf(
                            SearchContentType.TEXT_NOTE to 3,
                            SearchContentType.JOURNAL to 1,
                            SearchContentType.STICKER to 2,
                        ),
                    byPerson = mapOf(sam to 2),
                    byPlace = mapOf(lake to 3, home to 1),
                    createdAt = listOf(Instant.parse("2025-07-19T15:00:00Z")),
                )
            val labels =
                SearchFacetLabels(
                    people = mapOf(sam to SearchPersonLabel("Sam", null)),
                    places = mapOf(lake to "Lake Mead", home to "Home"),
                )
            val viewModel = viewModel(repository = RecordingRepository(facets = facets), labels = labels)
            observe(viewModel)

            viewModel.updateQuery("lake")
            advanceTimeBy(150)
            advanceUntilIdle()

            val narrowing = viewModel.narrowing.value
            assertEquals(mapOf(SearchContentType.TEXT_NOTE to 3, SearchContentType.JOURNAL to 1), narrowing.counts.byType)
            assertEquals(mapOf(sam to 2), narrowing.counts.byPerson)
            assertEquals(mapOf(lake to 3, home to 1), narrowing.counts.byPlace)
            assertEquals(mapOf(MonthBucket(2025, 7) to 1), narrowing.counts.byMonth)
            assertEquals(labels, narrowing.labels)
        }

    @Test
    fun `counts follow the narrowing that is chosen`() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository = repository)
            observe(viewModel)
            viewModel.updateQuery("lake")
            advanceTimeBy(150)
            advanceUntilIdle()

            viewModel.togglePerson(sam)
            advanceTimeBy(150)
            advanceUntilIdle()

            assertEquals(sam, repository.lastFacetScope?.personId)
            assertEquals(launchSearchContentTypes, repository.lastFacetScope?.contentTypes)
        }

    @Test
    fun `a blank query has no counts`() =
        runTest(dispatcher) {
            val viewModel = viewModel(repository = RecordingRepository(facets = SearchFacets(byPlace = mapOf(lake to 3))))
            observe(viewModel)

            advanceTimeBy(150)
            advanceUntilIdle()

            assertEquals(SearchFacetCounts.Empty, viewModel.narrowing.value.counts)
        }

    private fun TestScope.observe(viewModel: SearchViewModel) {
        backgroundScope.launch { viewModel.searchState.collect {} }
        backgroundScope.launch { viewModel.resultDetails.collect {} }
        backgroundScope.launch { viewModel.narrowing.collect {} }
        runCurrent()
    }

    private fun viewModel(
        repository: RecordingRepository = RecordingRepository(),
        labels: SearchFacetLabels = SearchFacetLabels.Empty,
    ) = SearchViewModel(
        universalSearchUseCase = UniversalSearchUseCase(repository),
        observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(EmptyRecents()),
        searchResultDetailsRepository = Labels(labels),
        observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(repository),
    )

    private class Labels(
        private val labels: SearchFacetLabels,
    ) : SearchResultDetailsRepository {
        override suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail> = emptyMap()

        override suspend fun labelFacets(
            personIds: Collection<Uuid>,
            placeIds: Collection<Uuid>,
        ): SearchFacetLabels =
            SearchFacetLabels(
                people = labels.people.filterKeys { it in personIds },
                places = labels.places.filterKeys { it in placeIds },
            )
    }

    private class RecordingRepository(
        private val facets: SearchFacets = SearchFacets.Empty,
    ) : SearchRepository {
        var lastSearchScope: SearchScope? = null
            private set
        var lastFacetScope: SearchScope? = null
            private set

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
        ): Flow<List<SearchResult>> {
            lastSearchScope = scope
            return flowOf(emptyList())
        }

        override fun observeFacets(
            query: String,
            scope: SearchScope,
        ): Flow<SearchFacets> {
            lastFacetScope = scope
            return flowOf(facets)
        }

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
