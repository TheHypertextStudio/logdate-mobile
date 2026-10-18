package app.logdate.client.domain.search

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SearchFiltersScopeTest {
    private val tz = TimeZone.of("America/Los_Angeles")

    // Monday, 2026-05-04 14:30 PT
    private val now: Instant = LocalDateTime(2026, 5, 4, 14, 30).toInstant(tz)

    @Test
    fun `default filters do not narrow anything`() {
        assertEquals(SearchScope(), SearchFilters.Default.toScope(now, tz))
    }

    @Test
    fun `content types person and place pass through unchanged`() {
        val person = Uuid.random()
        val place = Uuid.random()
        val filters =
            SearchFilters(
                contentTypes = setOf(SearchContentType.TEXT_NOTE),
                personId = person,
                placeId = place,
            )

        val scope = filters.toScope(now, tz)

        assertEquals(setOf(SearchContentType.TEXT_NOTE), scope.contentTypes)
        assertEquals(person, scope.personId)
        assertEquals(place, scope.placeId)
        assertNull(scope.createdFrom)
        assertNull(scope.createdBefore)
    }

    @Test
    fun `year narrows to the local calendar year`() {
        val scope = SearchFilters(year = 2025).toScope(now, tz)

        assertEquals(LocalDate(2025, 1, 1).atStartOfDayIn(tz), scope.createdFrom)
        assertEquals(LocalDate(2026, 1, 1).atStartOfDayIn(tz), scope.createdBefore)
    }

    @Test
    fun `relative date range narrows to its window`() {
        val scope = SearchFilters(dateRange = DateRangeFilter.ThisMonth).toScope(now, tz)

        assertEquals(LocalDate(2026, 5, 1).atStartOfDayIn(tz), scope.createdFrom)
        assertEquals(LocalDate(2026, 6, 1).atStartOfDayIn(tz), scope.createdBefore)
    }

    @Test
    fun `year and date range combine to their overlap`() {
        val scope = SearchFilters(year = 2026, dateRange = DateRangeFilter.ThisMonth).toScope(now, tz)

        assertEquals(LocalDate(2026, 5, 1).atStartOfDayIn(tz), scope.createdFrom)
        assertEquals(LocalDate(2026, 6, 1).atStartOfDayIn(tz), scope.createdBefore)
    }

    @Test
    fun `year outside the date range leaves an empty window`() {
        val scope = SearchFilters(year = 2025, dateRange = DateRangeFilter.ThisMonth).toScope(now, tz)

        val from = requireNotNull(scope.createdFrom)
        val before = requireNotNull(scope.createdBefore)
        assertTrue(from >= before)
    }
}
