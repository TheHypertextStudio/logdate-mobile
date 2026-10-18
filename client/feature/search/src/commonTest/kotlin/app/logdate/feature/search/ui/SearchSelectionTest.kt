package app.logdate.feature.search.ui

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.key
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SearchSelectionTest {
    private val utc = TimeZone.UTC
    private val journal = result("Lake Days", SearchContentType.JOURNAL, "2025-07-01T12:00:00Z")
    private val rewind = result("Summer 2025", SearchContentType.REWIND, "2025-09-22T12:00:00Z")
    private val note = result("A day on the water", SearchContentType.TEXT_NOTE, "2025-07-19T15:00:00Z")
    private val voice = result("The water was so clear", SearchContentType.TRANSCRIPTION, "2025-07-19T18:00:00Z")

    @Test
    fun `choosing a top match or collection selects that result`() {
        assertEquals(SearchSelection.Result(journal.key), searchSelectionFor(journal, utc))
        assertEquals(SearchSelection.Result(rewind.key), searchSelectionFor(rewind, utc))
    }

    @Test
    fun `choosing an entry selects its day with the entry marked`() {
        assertEquals(SearchSelection.Day(LocalDate(2025, 7, 19), voice.key), searchSelectionFor(voice, utc))
    }

    @Test
    fun `an entry late in the evening selects its local day`() {
        val evening = result("Fireworks", SearchContentType.TEXT_NOTE, "2025-07-20T03:00:00Z")

        assertEquals(
            LocalDate(2025, 7, 19),
            (searchSelectionFor(evening, TimeZone.of("America/Los_Angeles")) as SearchSelection.Day).date,
        )
    }

    @Test
    fun `a selection survives being written down and read back`() {
        val selections =
            listOf(
                SearchSelection.Result(journal.key),
                SearchSelection.Day(LocalDate(2025, 7, 19), voice.key),
                SearchSelection.Day(LocalDate(2025, 7, 19), null),
            )

        selections.forEach { assertEquals(it, decodeSearchSelection(encodeSearchSelection(it))) }
    }

    @Test
    fun `nothing selected is written as nothing`() {
        assertEquals("", encodeSearchSelection(null))
        assertNull(decodeSearchSelection(""))
    }

    @Test
    fun `text that is not a selection reads as nothing selected`() {
        assertNull(decodeSearchSelection("garbage"))
        assertNull(decodeSearchSelection("r|not_a_type|${Uuid.random()}"))
        assertNull(decodeSearchSelection("r|journal|not-a-uuid"))
        assertNull(decodeSearchSelection("d|not-a-date||"))
    }

    @Test
    fun `the first selection is the first top match then collection then newest day`() {
        assertEquals(SearchSelection.Result(journal.key), groups(journal, rewind, note).firstSelection(utc))
        assertEquals(SearchSelection.Result(rewind.key), groups(rewind, note).firstSelection(utc))
        assertEquals(SearchSelection.Day(LocalDate(2025, 7, 19), null), groups(note, voice).firstSelection(utc))
        assertNull(groups().firstSelection(utc))
    }

    @Test
    fun `a selection is kept while its result is still shown and dropped when it is not`() {
        val shown = groups(journal, note, voice)

        assertTrue(shown.contains(SearchSelection.Result(journal.key)))
        assertTrue(shown.contains(SearchSelection.Day(LocalDate(2025, 7, 19), voice.key)))
        assertTrue(shown.contains(SearchSelection.Day(LocalDate(2025, 7, 19), null)))
        assertTrue(!shown.contains(SearchSelection.Result(rewind.key)))
        assertTrue(!shown.contains(SearchSelection.Day(LocalDate(2024, 1, 1), null)))
        assertTrue(
            !shown.contains(SearchSelection.Day(LocalDate(2025, 7, 19), SearchResultKey(SearchContentType.TEXT_NOTE, Uuid.random()))),
        )
    }

    private fun groups(vararg results: SearchResult) = groupSearchResults(results.toList(), utc)

    private fun result(
        content: String,
        type: SearchContentType,
        created: String,
    ) = SearchResult(uid = Uuid.random(), content = content, created = Instant.parse(created), contentType = type)
}
