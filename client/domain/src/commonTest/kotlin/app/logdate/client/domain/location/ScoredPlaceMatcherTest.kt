package app.logdate.client.domain.location

import app.logdate.client.domain.location.history.ScoredPlaceMatcher
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ScoredPlaceMatcherTest {
    private val base = Instant.parse("2026-10-12T16:00:00Z")
    private val matcher = ScoredPlaceMatcher()

    @Test
    fun `adjacent places within 65m assign top match and surface disambiguation candidates`() {
        val visit =
            PlaceVisit(
                id = "visit-1",
                start = base,
                end = base + 30.minutes,
                evidenceIds = listOf("s0"),
                latitude = 37.7749,
                longitude = -122.4194,
                confirmedStay = true,
                uncertaintyRadiusMeters = 20f,
            )
        val cafe =
            SemanticPlace(
                id = "cafe",
                name = "Corner Cafe",
                latitude = 37.7749,
                longitude = -122.4194,
                userConfirmed = true,
                radiusMeters = 75.0,
            )
        val coworking =
            SemanticPlace(
                id = "cowork",
                name = "Loft Coworking",
                latitude = 37.7751,
                longitude = -122.4194,
                userConfirmed = true,
                radiusMeters = 75.0,
            )
        val result = matcher.match(visit, listOf(cafe, coworking))
        assertEquals("cafe", result.assigned?.id)
        assertTrue(result.needsDisambiguation)
        assertEquals(listOf("cafe", "cowork"), result.candidatePlaceIds)
    }

    @Test
    fun `familiarity and explicit correction break ties decisively without disambiguation`() {
        val visit =
            PlaceVisit(
                id = "visit-2",
                start = base,
                end = base + 30.minutes,
                evidenceIds = listOf("s0"),
                latitude = 37.7749,
                longitude = -122.4194,
                confirmedStay = true,
                uncertaintyRadiusMeters = 20f,
            )
        val cafe = SemanticPlace("cafe", "Corner Cafe", 37.7749, -122.4194, userConfirmed = true)
        val coworking = SemanticPlace("cowork", "Loft Coworking", 37.7751, -122.4194, userConfirmed = true)

        val result =
            matcher.match(
                visit = visit,
                places = listOf(cafe, coworking),
                visitCountsByPlaceId = mapOf("cafe" to 30, "cowork" to 0),
                explicitCorrectionPlaceId = "cafe",
            )
        assertEquals("cafe", result.assigned?.id)
        assertFalse(result.needsDisambiguation)
    }

    @Test
    fun `places beyond custom radius and uncertainty margin are rejected`() {
        val visit =
            PlaceVisit(
                id = "visit-3",
                start = base,
                end = base + 30.minutes,
                evidenceIds = listOf("s0"),
                latitude = 37.7749,
                longitude = -122.4194,
                confirmedStay = true,
                uncertaintyRadiusMeters = 15f,
            )
        val distant =
            SemanticPlace(
                id = "distant",
                name = "Downtown",
                latitude = 37.7775,
                longitude = -122.4194,
                userConfirmed = true,
                radiusMeters = 75.0,
            )
        val result = matcher.match(visit, listOf(distant))
        assertNull(result.assigned)
        assertFalse(result.needsDisambiguation)
    }
}
