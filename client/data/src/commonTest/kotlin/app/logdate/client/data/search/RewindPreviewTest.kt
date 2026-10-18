package app.logdate.client.data.search

import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.shared.model.HighlightedQuote
import app.logdate.shared.model.LocationSummary
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RewindPreviewTest {
    private val start = Instant.parse("2025-06-21T00:00:00Z")
    private val end = Instant.parse("2025-09-22T00:00:00Z")

    @Test
    fun `a rewind preview names its people and primary place`() {
        val preview =
            rewind(
                metadata(
                    people = listOf("Sam", "Maya"),
                    location = LocationSummary(distinctLocations = 4, newPlaces = 1, primaryLocation = "Lake Mead"),
                ),
            ).toSearchPreview()

        assertEquals(listOf("Sam", "Maya"), preview.people)
        assertEquals("Lake Mead", preview.place)
    }

    @Test
    fun `highlights are at most three with quoted lines before milestones`() {
        val preview =
            rewind(
                metadata(
                    quotes = listOf(quote("The water was so clear"), quote("We stayed until dark")),
                    milestones = listOf("First kayak trip", "Longest drive"),
                ),
            ).toSearchPreview()

        assertEquals(listOf("The water was so clear", "We stayed until dark", "First kayak trip"), preview.highlights)
    }

    @Test
    fun `a rewind without metadata previews with nothing to add`() {
        val preview = rewind(null).toSearchPreview()

        assertEquals(SearchResultPreview.Rewind(highlights = emptyList(), people = emptyList(), place = null), preview)
        assertNull(preview.place)
    }

    @Test
    fun `blank highlights and a blank place are left out`() {
        val preview =
            rewind(
                metadata(
                    quotes = listOf(quote("  ")),
                    milestones = listOf(""),
                    location = LocationSummary(distinctLocations = 1, newPlaces = 0, primaryLocation = " "),
                ),
            ).toSearchPreview()

        assertEquals(emptyList(), preview.highlights)
        assertNull(preview.place)
    }

    private fun quote(text: String) = HighlightedQuote(text = text, whyItHits = "", sourceEntryId = Uuid.random().toString())

    private fun metadata(
        people: List<String> = emptyList(),
        location: LocationSummary? = null,
        quotes: List<HighlightedQuote> = emptyList(),
        milestones: List<String> = emptyList(),
    ) = RewindMetadata(
        detectedActivities = emptyList(),
        locationSummary = location,
        milestones = milestones,
        peopleHighlighted = people,
        highlightedQuotes = quotes,
    )

    private fun rewind(metadata: RewindMetadata?) =
        Rewind(
            uid = Uuid.random(),
            startDate = start,
            endDate = end,
            generationDate = end,
            label = "2025#26",
            title = "Summer 2025",
            metadata = metadata,
        )
}
