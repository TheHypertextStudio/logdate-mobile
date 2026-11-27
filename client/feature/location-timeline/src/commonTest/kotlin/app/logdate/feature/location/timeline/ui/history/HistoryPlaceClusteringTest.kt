package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.text.toUiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryPlaceClusteringTest {
    private val coffeeA =
        HistoryMapMarker("a", HistoryCoordinate(37.7750, -122.4190), "Cafe A".toUiText(), category = "COFFEE", visitCount = 10)
    private val coffeeB =
        HistoryMapMarker("b", HistoryCoordinate(37.7810, -122.4190), "Shop B".toUiText(), category = "SHOPPING", visitCount = 2)
    private val farPlace =
        HistoryMapMarker("c", HistoryCoordinate(37.9000, -122.4190), "North Park".toUiText(), category = "OUTDOORS", visitCount = 1)

    @Test
    fun `zoom thresholds group nearby places and uncluster above 13_5`() {
        val midZoom = clusterMapMarkers(listOf(coffeeA, coffeeB, farPlace), zoom = 12f)
        assertEquals(2, midZoom.size)
        val merged = midZoom.first { it.placeCount == 2 }
        assertEquals("COFFEE", merged.dominantCategory)
        // Weighted toward 10-visit Cafe A rather than midpoint (37.7780).
        assertTrue(merged.coordinate.latitude < 37.7765)

        val detailZoom = clusterMapMarkers(listOf(coffeeA, coffeeB, farPlace), zoom = 14f)
        assertEquals(3, detailZoom.size)
        assertTrue(detailZoom.all { it.placeCount == 1 })
    }
}
