package app.logdate.client.domain.search

import app.logdate.client.domain.fakes.FakeSearchRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class UniversalSearchUseCaseTest {
    private val fixedNow = Instant.parse("2026-05-04T21:30:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    @Test
    fun `filters are handed to the repository as a scope so narrowing happens before the limit`() =
        runTest {
            val repository = FakeSearchRepository()
            val person = Uuid.random()
            val place = Uuid.random()
            val useCase = UniversalSearchUseCase(repository, clock) { TimeZone.UTC }

            useCase(
                flowOf(SearchQuery("lake")),
                SearchFilters(
                    contentTypes = setOf(SearchContentType.TEXT_NOTE),
                    personId = person,
                    placeId = place,
                    year = 2025,
                    maxResults = 12,
                ),
            ).first()

            assertEquals(12, repository.lastScopedLimit)
            assertEquals(
                SearchScope(
                    contentTypes = setOf(SearchContentType.TEXT_NOTE),
                    personId = person,
                    placeId = place,
                    createdFrom = Instant.parse("2025-01-01T00:00:00Z"),
                    createdBefore = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                repository.lastScope,
            )
        }

    @Test
    fun `results outside the type scope are dropped even by a repository that cannot filter`() =
        runTest {
            val note = result("A lake note", SearchContentType.TEXT_NOTE)
            val journal = result("Lake Days", SearchContentType.JOURNAL)
            val repository = FakeSearchRepository(listOf(note, journal))
            val useCase = UniversalSearchUseCase(repository, clock) { TimeZone.UTC }

            val results =
                useCase(
                    flowOf(SearchQuery("lake")),
                    SearchFilters(contentTypes = setOf(SearchContentType.JOURNAL)),
                ).first()

            assertEquals(listOf(journal), results)
        }

    @Test
    fun `blank queries return nothing without touching the repository`() =
        runTest {
            val repository = FakeSearchRepository(listOf(result("A lake note", SearchContentType.TEXT_NOTE)))
            val useCase = UniversalSearchUseCase(repository, clock) { TimeZone.UTC }

            val results = useCase(flowOf(SearchQuery(" "))).first()

            assertEquals(emptyList(), results)
            assertEquals(null, repository.lastScope)
        }

    private fun result(
        content: String,
        type: SearchContentType,
    ) = SearchResult(
        uid = Uuid.random(),
        content = content,
        created = Instant.parse("2025-07-19T15:00:00Z"),
        contentType = type,
    )
}
