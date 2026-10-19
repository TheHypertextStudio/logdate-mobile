package app.logdate.feature.search.ui

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SearchSectionTest {
    private fun result(
        content: String,
        type: SearchContentType,
    ) = SearchResult(Uuid.random(), content, Instant.parse("2025-07-19T15:00:00Z"), type)

    private val journal = result("Lake Days", SearchContentType.JOURNAL)
    private val person = result("Sam", SearchContentType.PERSON)
    private val note = result("Paddled out", SearchContentType.TEXT_NOTE)
    private val voice = result("The water was clear", SearchContentType.TRANSCRIPTION)
    private val rewind = result("Summer", SearchContentType.REWIND)

    @Test
    fun `results are sectioned as journals then people then notes`() {
        val sections = sectionSearchResults(listOf(note, person, journal, voice))

        assertEquals(listOf(SearchSection.Journals, SearchSection.People, SearchSection.Notes), sections.map { it.first })
        assertEquals(listOf(journal), sections[0].second)
        assertEquals(listOf(person), sections[1].second)
        assertEquals(listOf(note, voice), sections[2].second)
    }

    @Test
    fun `everything that is not a journal or a person is a note`() {
        val sections = sectionSearchResults(listOf(rewind, voice))

        assertEquals(listOf(SearchSection.Notes), sections.map { it.first })
        assertEquals(listOf(rewind, voice), sections.single().second)
    }

    @Test
    fun `sections with no results are left out`() {
        assertEquals(listOf(SearchSection.Journals), sectionSearchResults(listOf(journal)).map { it.first })
        assertEquals(emptyList(), sectionSearchResults(emptyList()))
    }

    @Test
    fun `ranking order is kept inside a section`() {
        val first = result("first", SearchContentType.TEXT_NOTE)
        val second = result("second", SearchContentType.MEDIA_CAPTION)

        assertEquals(listOf(second, first), sectionSearchResults(listOf(second, first)).single().second)
    }
}
