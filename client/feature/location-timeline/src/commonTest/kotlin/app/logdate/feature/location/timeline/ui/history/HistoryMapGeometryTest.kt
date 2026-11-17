package app.logdate.feature.location.timeline.ui.history

import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryMapGeometryTest {
    private val start = Instant.parse("2026-09-29T10:00:00Z")

    @Test
    fun `only observed journey evidence becomes a path`() {
        val first = PlaceVisit("home", start, start, listOf("home"), 36.0, -115.0, true)
        val gap = HistoryGap("gap", start, start)
        val second = PlaceVisit("cafe", start, start, listOf("cafe"), 36.001, -115.001, true)
        val route =
            JourneyLeg(
                "walk",
                start,
                start,
                listOf("a", "b"),
                TravelMode.WALKING,
                listOf(point("a", 36.001, -115.001), point("b", 36.002, -115.002)),
            )

        val geometry = historyMapGeometry(listOf(first, gap, second, route))

        assertEquals(2, geometry.markers.size)
        assertEquals(1, geometry.paths.size)
        assertEquals("walk", geometry.paths.single().id)
        assertEquals(
            2,
            geometry.paths
                .single()
                .points.size,
        )
    }

    @Test
    fun `projection keeps a single place centered and multiple places inside the canvas`() {
        val single = historyMapProjection(listOf(HistoryCoordinate(36.0, -115.0)), 300f, 200f).project(HistoryCoordinate(36.0, -115.0))
        assertEquals(150f, single.x)
        assertEquals(100f, single.y)

        val points = listOf(HistoryCoordinate(36.0, -115.0), HistoryCoordinate(36.01, -115.02))
        points.forEach { coordinate ->
            val position = historyMapProjection(points, 300f, 200f).project(coordinate)
            assertTrue(position.x in 16f..284f)
            assertTrue(position.y in 16f..184f)
        }
    }

    private fun point(
        id: String,
        latitude: Double,
        longitude: Double,
    ) = LocationObservation(id, "owner", "device", start, latitude, longitude)
}
