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
import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.client.repository.search.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelDetailsTest {
    private val dispatcher = StandardTestDispatcher()
    private val created = Instant.parse("2025-07-19T15:00:00Z")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `results are described once their search settles`() =
        runTest(dispatcher) {
            val caption = SearchResult(Uuid.random(), "Kayak at the lake", created, SearchContentType.MEDIA_CAPTION)
            val details = FakeDetails(mapOf(caption.key to SearchResultDetail.Image("file:///a.jpg")))
            val viewModel = viewModel(listOf(caption), details)
            observe(viewModel)

            viewModel.updateQuery("lake")
            advanceTimeBy(150)
            advanceUntilIdle()

            assertEquals(mapOf(caption.key to SearchResultDetail.Image("file:///a.jpg")), viewModel.resultDetails.value)
            assertEquals(listOf(listOf(caption)), details.requests)
        }

    @Test
    fun `details are cleared when the query is cleared`() =
        runTest(dispatcher) {
            val caption = SearchResult(Uuid.random(), "Kayak at the lake", created, SearchContentType.MEDIA_CAPTION)
            val viewModel = viewModel(listOf(caption), FakeDetails(mapOf(caption.key to SearchResultDetail.Image("file:///a.jpg"))))
            observe(viewModel)
            viewModel.updateQuery("lake")
            advanceTimeBy(150)
            advanceUntilIdle()

            viewModel.updateQuery("")
            advanceTimeBy(150)
            advanceUntilIdle()

            assertEquals(emptyMap(), viewModel.resultDetails.value)
        }

    @Test
    fun `results still show when describing them fails`() =
        runTest(dispatcher) {
            val note = SearchResult(Uuid.random(), "A lake day", created, SearchContentType.TEXT_NOTE)
            val viewModel = viewModel(listOf(note), FakeDetails(emptyMap(), failure = IllegalStateException("lookup failed")))
            observe(viewModel)

            viewModel.updateQuery("lake")
            advanceTimeBy(150)
            advanceUntilIdle()

            assertIs<SearchScreenState.Results>(viewModel.searchState.value)
            assertEquals(emptyMap(), viewModel.resultDetails.value)
        }

    @Test
    fun `a chosen result is previewed from what the repository reads for it`() =
        runTest(dispatcher) {
            val journal = SearchResult(Uuid.random(), "Lake Days", created, SearchContentType.JOURNAL)
            val preview = SearchResultPreview.Journal("On the water", created, emptyList())
            val details = FakeDetails(emptyMap(), previews = mapOf(journal.key to preview))
            val viewModel = viewModel(listOf(journal), details)

            assertEquals(preview, viewModel.loadPreview(journal.key))
            assertNull(viewModel.loadPreview(SearchResultKey(SearchContentType.JOURNAL, Uuid.random())))
        }

    @Test
    fun `a preview that cannot be read leaves the result without one`() =
        runTest(dispatcher) {
            val journal = SearchResult(Uuid.random(), "Lake Days", created, SearchContentType.JOURNAL)
            val viewModel = viewModel(listOf(journal), FakeDetails(emptyMap(), failure = IllegalStateException("unreadable")))

            assertNull(viewModel.loadPreview(journal.key))
        }

    private fun TestScope.observe(viewModel: SearchViewModel) {
        backgroundScope.launch { viewModel.searchState.collect {} }
        backgroundScope.launch { viewModel.resultDetails.collect {} }
        runCurrent()
    }

    private fun viewModel(
        results: List<SearchResult>,
        details: SearchResultDetailsRepository,
    ): SearchViewModel {
        val repository = FixedResultsRepository(results)
        return SearchViewModel(
            universalSearchUseCase = UniversalSearchUseCase(repository),
            observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(EmptyRecents()),
            searchResultDetailsRepository = details,
            observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(repository),
        )
    }

    private class FakeDetails(
        private val details: Map<SearchResultKey, SearchResultDetail>,
        private val failure: Throwable? = null,
        private val previews: Map<SearchResultKey, SearchResultPreview> = emptyMap(),
    ) : SearchResultDetailsRepository {
        override suspend fun preview(key: SearchResultKey): SearchResultPreview? {
            failure?.let { throw it }
            return previews[key]
        }

        val requests = mutableListOf<List<SearchResult>>()

        override suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail> {
            if (results.isNotEmpty()) requests += results
            failure?.let { throw it }
            return details.filterKeys { key -> results.any { it.key == key } }
        }
    }

    private class FixedResultsRepository(
        private val results: List<SearchResult>,
    ) : SearchRepository {
        override fun search(query: String): Flow<List<SearchResult>> = flowOf(results.filter { query.isNotBlank() })

        override fun searchWithLimit(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)

        override fun searchWithSnippets(query: String): Flow<List<SearchResult>> = search(query)

        override fun searchRanked(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)

        override fun searchInJournal(
            query: String,
            journalId: Uuid,
            limit: Int,
        ): Flow<List<SearchResult>> = search(query)
    }

    private class EmptyRecents : RecentSearchesRepository {
        private val flow = MutableStateFlow(emptyList<String>())

        override fun observeRecentSearches(): Flow<List<String>> = flow.map { it }

        override suspend fun addRecentSearch(query: String) = Unit

        override suspend fun removeRecentSearch(query: String) = Unit

        override suspend fun clearRecentSearches() = Unit
    }
}
