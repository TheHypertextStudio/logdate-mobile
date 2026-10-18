package app.logdate.feature.search.ui

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SearchResultGroupsTest {
    private val utc = TimeZone.UTC
    private val losAngeles = TimeZone.of("America/Los_Angeles")

    @Test
    fun `every content type belongs to exactly one shape`() {
        val shapes = SearchContentType.entries.associateWith { it.shape }

        assertEquals(SearchResultShape.Entity, shapes.getValue(SearchContentType.PERSON))
        assertEquals(SearchResultShape.Entity, shapes.getValue(SearchContentType.PLACE))
        assertEquals(SearchResultShape.Entity, shapes.getValue(SearchContentType.JOURNAL))
        assertEquals(SearchResultShape.Collection, shapes.getValue(SearchContentType.REWIND))
        assertEquals(SearchResultShape.Collection, shapes.getValue(SearchContentType.POSTCARD))
        listOf(
            SearchContentType.TEXT_NOTE,
            SearchContentType.TRANSCRIPTION,
            SearchContentType.MEDIA_CAPTION,
            SearchContentType.STICKER,
            SearchContentType.AMBIENT_SOUND,
        ).forEach { assertEquals(SearchResultShape.DayItem, shapes.getValue(it), it.name) }
    }

    @Test
    fun `no results make no groups`() {
        val groups = groupSearchResults(emptyList(), utc)

        assertTrue(groups.isEmpty)
        assertEquals(emptyList(), groups.topMatches)
        assertEquals(emptyList(), groups.collections)
        assertEquals(emptyList(), groups.days)
    }

    @Test
    fun `people places and journals are top matches in that order and keep their rank`() {
        val journal = result("Lake Days", SearchContentType.JOURNAL, "2025-07-01T00:00:00Z")
        val placeA = result("Lake Mead", SearchContentType.PLACE, "2025-07-01T00:00:00Z")
        val placeB = result("Lakeview", SearchContentType.PLACE, "2025-07-02T00:00:00Z")
        val person = result("Lakshmi", SearchContentType.PERSON, "2025-07-01T00:00:00Z")

        val groups = groupSearchResults(listOf(journal, placeA, placeB, person), utc)

        assertEquals(listOf(person, placeA, placeB, journal), groups.topMatches)
    }

    @Test
    fun `rewinds and postcards are collections newest first`() {
        val older = result("Summer 2023", SearchContentType.REWIND, "2023-09-22T00:00:00Z")
        val postcard = result("To Dad", SearchContentType.POSTCARD, "2025-07-20T00:00:00Z")
        val newer = result("Summer 2025", SearchContentType.REWIND, "2025-09-22T00:00:00Z")

        val groups = groupSearchResults(listOf(older, postcard, newer), utc)

        assertEquals(listOf(newer, postcard, older), groups.collections)
    }

    @Test
    fun `entries group by local day newest day first and in time order within a day`() {
        val lateJuly19 = result("Sunburn", SearchContentType.TEXT_NOTE, "2025-07-19T20:00:00Z")
        val earlyJuly19 = result("Kayak", SearchContentType.MEDIA_CAPTION, "2025-07-19T10:00:00Z")
        val june = result("White ring", SearchContentType.TEXT_NOTE, "2024-06-02T10:00:00Z")
        val july20 = result("Pizza", SearchContentType.TRANSCRIPTION, "2025-07-20T12:00:00Z")

        val groups = groupSearchResults(listOf(lateJuly19, june, earlyJuly19, july20), utc)

        assertEquals(
            listOf(
                SearchDayGroup(LocalDate(2025, 7, 20), listOf(july20)),
                SearchDayGroup(LocalDate(2025, 7, 19), listOf(earlyJuly19, lateJuly19)),
                SearchDayGroup(LocalDate(2024, 6, 2), listOf(june)),
            ),
            groups.days,
        )
    }

    @Test
    fun `a late evening result stays on its local day`() {
        // 03:00 UTC on 20 July is 20:00 on 19 July in Los Angeles.
        val evening = result("Fireworks", SearchContentType.TEXT_NOTE, "2025-07-20T03:00:00Z")

        val groups = groupSearchResults(listOf(evening), losAngeles)

        assertEquals(listOf(LocalDate(2025, 7, 19)), groups.days.map { it.date })
    }

    @Test
    fun `stickers and soundscapes group into days with notes`() {
        val note = result("A note", SearchContentType.TEXT_NOTE, "2025-07-19T10:00:00Z")
        val sticker = result("Lake day", SearchContentType.STICKER, "2025-07-19T11:00:00Z")
        val sound = result("Water", SearchContentType.AMBIENT_SOUND, "2025-07-19T12:00:00Z")

        val groups = groupSearchResults(listOf(sound, sticker, note), utc)

        assertEquals(listOf(listOf(note, sticker, sound)), groups.days.map { it.results })
        assertEquals(emptyList(), groups.topMatches)
        assertEquals(emptyList(), groups.collections)
    }

    @Test
    fun `groups report how many results they hold`() {
        val groups =
            groupSearchResults(
                listOf(
                    result("Lake Days", SearchContentType.JOURNAL, "2025-07-01T00:00:00Z"),
                    result("Summer 2025", SearchContentType.REWIND, "2025-09-22T00:00:00Z"),
                    result("A note", SearchContentType.TEXT_NOTE, "2025-07-19T10:00:00Z"),
                    result("Another", SearchContentType.TEXT_NOTE, "2025-07-19T11:00:00Z"),
                ),
                utc,
            )

        assertEquals(4, groups.total)
    }

    private fun result(
        content: String,
        type: SearchContentType,
        created: String,
    ) = SearchResult(uid = Uuid.random(), content = content, created = Instant.parse(created), contentType = type)
}
