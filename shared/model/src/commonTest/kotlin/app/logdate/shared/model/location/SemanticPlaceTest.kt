package app.logdate.shared.model.location

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SemanticPlaceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun placeRecordedBeforeQualifiersReadsWithoutOne() {
        val stored = """{"id":"p1","name":"Near Market Street","latitude":37.7,"longitude":-122.4}"""

        val place = json.decodeFromString(SemanticPlace.serializer(), stored)

        assertEquals("Near Market Street", place.name)
        assertNull(place.nameQualifier)
    }

    @Test
    fun nameAndQualifierAreStoredSeparately() {
        val place =
            SemanticPlace(
                id = "p2",
                name = "Market Street",
                latitude = 37.7,
                longitude = -122.4,
                nameQualifier = PlaceNameQualifier.NEAR,
            )

        val decoded = json.decodeFromString(SemanticPlace.serializer(), json.encodeToString(SemanticPlace.serializer(), place))

        assertEquals("Market Street", decoded.name)
        assertEquals(PlaceNameQualifier.NEAR, decoded.nameQualifier)
    }
}
