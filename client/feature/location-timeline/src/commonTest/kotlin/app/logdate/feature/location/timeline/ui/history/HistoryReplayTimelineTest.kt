package app.logdate.feature.location.timeline.ui.history

import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HistoryReplayTimelineTest {
    private val start = Instant.parse("2026-10-12T15:00:00Z")

    @Test
    fun `samples dwell on visit and interpolate linearly along smoothed route`() {
        val home = PlaceVisit("home", start, start + 10.minutes, listOf("h1"), 37.7700, -122.4200, true)
        val p1 = LocationObservation("p1", "o", "d", start + 10.minutes, 37.7700, -122.4200)
        val p2 = LocationObservation("p2", "o", "d", start + 20.minutes, 37.7800, -122.4200)
        val leg =
            JourneyLeg(
                id = "walk",
                start = start + 10.minutes,
                end = start + 20.minutes,
                evidenceIds = listOf("p1", "p2"),
                mode = TravelMode.WALKING,
                route = listOf(p1, p2),
                smoothedRoute = listOf(p1, p2),
            )
        val timeline = buildReplayTimeline(listOf(home, leg))

        val dwell = assertNotNull(timeline.sampleAt(0.25f))
        assertEquals("home", dwell.activeItemId)
        assertEquals(37.7700, dwell.latitude, 1e-4)

        val midLeg = assertNotNull(timeline.sampleAt(0.75f))
        assertEquals("walk", midLeg.activeItemId)
        assertEquals(TravelMode.WALKING, midLeg.mode)
        assertEquals(37.7750, midLeg.latitude, 1e-4)
        assertTrue(midLeg.bearingDegrees in 0f..5f || midLeg.bearingDegrees >= 355f)
    }

    @Test
    fun `synthetic connector interpolates via endpoint coordinates`() {
        val connector =
            JourneyLeg(
                id = "conn",
                start = start,
                end = start + 10.minutes,
                evidenceIds = listOf("derived:journey:a:b"),
                mode = TravelMode.UNKNOWN,
                route = emptyList(),
                smoothedRoute = emptyList(),
                endpointCoordinates = (37.0 to -122.0) to (37.02 to -122.0),
            )
        val timeline = buildReplayTimeline(listOf(connector))
        val mid = assertNotNull(timeline.sampleAt(0.5f))
        assertEquals(37.01, mid.latitude, 1e-4)
    }
}
