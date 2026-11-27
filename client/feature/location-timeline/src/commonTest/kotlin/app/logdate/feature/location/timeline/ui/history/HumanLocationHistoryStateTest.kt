package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.text.toUiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HumanLocationHistoryStateTest {
    private val day =
        HumanLocationHistoryState(
            dateLabel = "Tuesday, September 29".toUiText(),
            items =
                listOf(
                    HistoryItemUi("home", HistoryItemKind.Visit, "Home".toUiText(), "8:00 AM".toUiText()),
                    HistoryItemUi("walk", HistoryItemKind.Journey, "Walking".toUiText(), "9:00 AM".toUiText()),
                    HistoryItemUi("cafe", HistoryItemKind.Visit, "Mothership Coffee".toUiText(), "9:15 AM".toUiText()),
                ),
        )

    @Test
    fun `scrubbing includes journeys and clamps to the ends of the day`() {
        assertEquals("home", day.itemAtReplayPosition(-2f)?.id)
        assertEquals("walk", day.itemAtReplayPosition(0.5f)?.id)
        assertEquals("cafe", day.itemAtReplayPosition(2f)?.id)
        assertNull(day.copy(items = emptyList()).itemAtReplayPosition(0f))
        assertNull(day.itemAtReplayPosition(Float.NaN))
    }

    @Test
    fun `stepping starts at the first moment without wrapping or reviving stale selection`() {
        assertEquals("home", day.adjacentItem(1)?.id)
        assertNull(day.adjacentItem(-1))
        assertEquals("walk", day.copy(selectedItemId = "home").adjacentItem(1)?.id)
        assertNull(day.copy(selectedItemId = "home").adjacentItem(-1))
        assertNull(day.copy(selectedItemId = "missing").adjacentItem(1))
        assertNull(day.copy(selectedItemId = "cafe").adjacentItem(1))
    }

    @Test
    fun `place search trims whitespace and matches names without case sensitivity`() {
        val state =
            day.copy(
                places =
                    listOf(
                        HistoryPlaceUi("cafe", "Mothership Coffee".toUiText(), "2 visits".toUiText()),
                        HistoryPlaceUi("library", "West Charleston Library".toUiText(), "1 visit".toUiText()),
                    ),
                placesQuery = "  COFFEE ",
            )
        assertEquals(listOf("cafe"), state.filteredPlaces().map { it.id })
        assertEquals(2, state.copy(placesQuery = " ").filteredPlaces().size)
        assertEquals(emptyList(), state.copy(placesQuery = "unknown").filteredPlaces())
    }

    @Test
    fun `place sorting and category filtering order results as requested`() {
        val state =
            day.copy(
                places =
                    listOf(
                        HistoryPlaceUi(
                            "cafe",
                            "Cedar Cafe".toUiText(),
                            "4 visits".toUiText(),
                            category = "COFFEE",
                            visitCount = 4,
                            latestTimestampMs = 100L,
                        ),
                        HistoryPlaceUi(
                            "park",
                            "Amber Park".toUiText(),
                            "1 visit".toUiText(),
                            category = "OUTDOORS",
                            visitCount = 1,
                            latestTimestampMs = 200L,
                        ),
                    ),
            )
        assertEquals(listOf("park", "cafe"), state.copy(placesSort = PlacesSortOption.Recent).filteredPlaces().map { it.id })
        assertEquals(listOf("cafe", "park"), state.copy(placesSort = PlacesSortOption.MostVisited).filteredPlaces().map { it.id })
        assertEquals(listOf("park", "cafe"), state.copy(placesSort = PlacesSortOption.Alphabetical).filteredPlaces().map { it.id })
        assertEquals(listOf("cafe"), state.copy(placesCategoryFilter = "COFFEE").filteredPlaces().map { it.id })
    }
}
