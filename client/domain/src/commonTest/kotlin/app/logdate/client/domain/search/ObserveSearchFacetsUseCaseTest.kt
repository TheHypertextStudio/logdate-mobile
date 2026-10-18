package app.logdate.client.domain.search

import app.logdate.client.domain.fakes.FakeSearchRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchFacets
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

class ObserveSearchFacetsUseCaseTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-05-04T21:30:00Z")
        }
    private val losAngeles = TimeZone.of("America/Los_Angeles")

    @Test
    fun `counts and scope are passed through and creation times are bucketed by local month`() =
        runTest {
            val person = Uuid.random()
            val place = Uuid.random()
            val repository =
                FakeSearchRepository(
                    facets =
                        SearchFacets(
                            byType = mapOf(SearchContentType.TEXT_NOTE to 3),
                            byPerson = mapOf(person to 2),
                            byPlace = mapOf(place to 1),
                            createdAt =
                                listOf(
                                    // 20:00 PT on 31 July is already August in UTC.
                                    Instant.parse("2025-08-01T03:00:00Z"),
                                    Instant.parse("2025-07-04T19:00:00Z"),
                                    Instant.parse("2024-12-31T09:00:00Z"),
                                ),
                        ),
                )
            val useCase = ObserveSearchFacetsUseCase(repository, clock) { losAngeles }

            val counts = useCase(flowOf(SearchQuery("lake")), SearchFilters(personId = person, year = 2025)).first()

            assertEquals(mapOf(SearchContentType.TEXT_NOTE to 3), counts.byType)
            assertEquals(mapOf(person to 2), counts.byPerson)
            assertEquals(mapOf(place to 1), counts.byPlace)
            assertEquals(
                mapOf(MonthBucket(2025, 7) to 2, MonthBucket(2024, 12) to 1),
                counts.byMonth,
            )
            assertEquals(mapOf(2025 to 2, 2024 to 1), counts.byYear)
            assertEquals(person, repository.lastFacetScope?.personId)
            assertEquals(Instant.parse("2025-01-01T08:00:00Z"), repository.lastFacetScope?.createdFrom)
        }

    @Test
    fun `blank queries have no counts and do not reach the repository`() =
        runTest {
            val repository = FakeSearchRepository()
            val useCase = ObserveSearchFacetsUseCase(repository, clock) { TimeZone.UTC }

            val counts = useCase(flowOf(SearchQuery(" "))).first()

            assertEquals(SearchFacetCounts.Empty, counts)
            assertEquals(null as SearchScope?, repository.lastFacetScope)
        }
}
