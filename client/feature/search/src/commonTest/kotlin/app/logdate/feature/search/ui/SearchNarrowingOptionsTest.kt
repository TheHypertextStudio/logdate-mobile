package app.logdate.feature.search.ui

import app.logdate.client.domain.search.MonthBucket
import app.logdate.client.domain.search.SearchFacetCounts
import app.logdate.client.domain.search.SearchFilters
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchFacetLabels
import app.logdate.client.repository.search.SearchPersonLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SearchNarrowingOptionsTest {
    private val sam = Uuid.random()
    private val maya = Uuid.random()
    private val lake = Uuid.random()
    private val home = Uuid.random()

    private val labels =
        SearchFacetLabels(
            people = mapOf(sam to SearchPersonLabel("Sam", null), maya to SearchPersonLabel("Maya", null)),
            places = mapOf(lake to "Lake Mead", home to "Home"),
        )

    @Test
    fun `people and places list busiest first then by name`() {
        val counts =
            SearchFacetCounts(
                byPerson = mapOf(sam to 2, maya to 5),
                byPlace = mapOf(lake to 3, home to 3),
            )

        val options = buildNarrowingOptions(SearchNarrowingState(counts, labels), showPeople = true)

        assertEquals(listOf("Maya", "Sam"), options.people.map { it.label })
        assertEquals(listOf(5, 2), options.people.map { it.count })
        assertEquals(listOf("Home", "Lake Mead"), options.places.map { it.label })
    }

    @Test
    fun `people are left out until they are offered`() {
        val counts = SearchFacetCounts(byPerson = mapOf(sam to 2))

        val options = buildNarrowingOptions(SearchNarrowingState(counts, labels), showPeople = false)

        assertEquals(emptyList(), options.people)
    }

    @Test
    fun `an option with no name is left out unless it is chosen`() {
        val unknown = Uuid.random()
        val counts = SearchFacetCounts(byPlace = mapOf(lake to 3, unknown to 2))

        val plain = buildNarrowingOptions(SearchNarrowingState(counts, labels), showPeople = true)
        val chosen = buildNarrowingOptions(SearchNarrowingState(counts, labels, SearchFilters(placeId = unknown)), showPeople = true)

        assertEquals(listOf(lake), plain.places.map { it.value })
        assertEquals(listOf(lake, unknown), chosen.places.map { it.value })
        assertEquals(null, chosen.places.last().label)
    }

    @Test
    fun `an option that would leave nothing is hidden unless it is chosen`() {
        val counts = SearchFacetCounts(byPlace = mapOf(lake to 0, home to 2))

        val plain = buildNarrowingOptions(SearchNarrowingState(counts, labels), showPeople = true)
        val chosen = buildNarrowingOptions(SearchNarrowingState(counts, labels, SearchFilters(placeId = lake)), showPeople = true)

        assertEquals(listOf(home), plain.places.map { it.value })
        assertEquals(listOf(home, lake), chosen.places.map { it.value })
        assertTrue(chosen.places.first { it.value == lake }.selected)
    }

    @Test
    fun `the chosen person and place are marked`() {
        val counts = SearchFacetCounts(byPerson = mapOf(sam to 2, maya to 1), byPlace = mapOf(lake to 3, home to 1))

        val options =
            buildNarrowingOptions(SearchNarrowingState(counts, labels, SearchFilters(personId = sam, placeId = home)), showPeople = true)

        assertEquals(listOf(true, false), options.people.map { it.selected })
        assertEquals(listOf(false, true), options.places.map { it.selected })
        assertTrue(options.anyChosen)
    }

    @Test
    fun `types keep their usual order and mark the chosen ones`() {
        val counts =
            SearchFacetCounts(
                byType =
                    mapOf(
                        SearchContentType.REWIND to 1,
                        SearchContentType.TEXT_NOTE to 4,
                        SearchContentType.JOURNAL to 2,
                    ),
            )

        val options =
            buildNarrowingOptions(
                SearchNarrowingState(counts, labels, SearchFilters(contentTypes = setOf(SearchContentType.JOURNAL))),
                showPeople = true,
            )

        assertEquals(
            listOf(SearchContentType.TEXT_NOTE, SearchContentType.JOURNAL, SearchContentType.REWIND),
            options.types.map { it.value },
        )
        assertEquals(listOf(false, true, false), options.types.map { it.selected })
    }

    @Test
    fun `the time strip spans every year from the first to the last result`() {
        val counts =
            SearchFacetCounts(
                byMonth =
                    mapOf(
                        MonthBucket(2023, 8) to 2,
                        MonthBucket(2025, 7) to 3,
                        MonthBucket(2025, 3) to 1,
                    ),
            )

        val options = buildNarrowingOptions(SearchNarrowingState(counts, labels), showPeople = true)

        assertEquals(listOf(2023, 2024, 2025), options.years.map { it.year })
        assertEquals(listOf(2, 0, 4), options.years.map { it.total })
        assertEquals(2, options.years.first().months[7])
        assertEquals(
            12,
            options.years
                .first()
                .months.size,
        )
        assertEquals(1, options.years.last().months[2])
        assertEquals(3, options.years.last().months[6])
    }

    @Test
    fun `the chosen year stays on the strip when it has no results`() {
        val counts = SearchFacetCounts(byMonth = mapOf(MonthBucket(2025, 7) to 3))

        val options = buildNarrowingOptions(SearchNarrowingState(counts, labels, SearchFilters(year = 2022)), showPeople = true)

        assertEquals(listOf(2022, 2023, 2024, 2025), options.years.map { it.year })
        assertTrue(options.years.first().selected)
        assertFalse(options.years.last().selected)
    }

    @Test
    fun `no results and nothing chosen leave nothing to choose`() {
        val options = buildNarrowingOptions(SearchNarrowingState(), showPeople = true)

        assertFalse(options.hasChoices)
        assertFalse(options.anyChosen)
    }
}
