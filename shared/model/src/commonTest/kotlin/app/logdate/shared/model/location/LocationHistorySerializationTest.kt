package app.logdate.shared.model.location

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LocationHistorySerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `HistoryField decodes unknown strings safely to UNKNOWN and round-trips new fields`() {
        val correctionJson =
            """{"id":"c1","targetEvidenceId":"ev-1","field":"FUTURE_V5_FIELD","value":"abc","supersedes":[]}"""
        val decoded = json.decodeFromString(HistoryCorrection.serializer(), correctionJson)
        assertEquals(HistoryField.UNKNOWN, decoded.field)

        val newFields =
            listOf(
                HistoryField.SUGGESTED_PLACE,
                HistoryField.RADIUS,
                HistoryField.CATEGORY,
                HistoryField.MERGE_WITH,
                HistoryField.SPLIT_AT,
                HistoryField.UNKNOWN,
            )
        newFields.forEach { field ->
            val correction = HistoryCorrection("id-${field.name}", "ev-1", field, "val")
            val roundTripped = json.decodeFromString(HistoryCorrection.serializer(), json.encodeToString(correction))
            assertEquals(field, roundTripped.field)
        }
    }

    @Test
    fun `LocationObservation and SemanticPlace decode legacy v3 payloads with additive defaults`() {
        val obsJson =
            """{"id":"o1","ownerId":"u1","deviceId":"d1","timestamp":"2026-10-12T16:00:00Z","latitude":37.77,"longitude":-122.41}"""
        val obs = json.decodeFromString(LocationObservation.serializer(), obsJson)
        assertEquals(Instant.parse("2026-10-12T16:00:00Z"), obs.timestamp)
        assertNull(obs.provider)
        assertNull(obs.speedAccuracyMetersPerSecond)
        assertNull(obs.verticalAccuracyMeters)
        assertNull(obs.elapsedRealtimeNanos)

        val placeJson =
            """{"id":"p1","name":"Cafe","latitude":37.77,"longitude":-122.41,"userConfirmed":true}"""
        val place = json.decodeFromString(SemanticPlace.serializer(), placeJson)
        assertEquals(75.0, place.radiusMeters)
        assertNull(place.category)
    }
}
