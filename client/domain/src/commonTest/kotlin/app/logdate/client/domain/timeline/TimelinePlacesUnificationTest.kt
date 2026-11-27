package app.logdate.client.domain.timeline

import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.ResolveLocationToPlaceUseCase
import app.logdate.client.location.places.ExternalPlacesProvider
import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.location.places.PlaceSuggestion
import app.logdate.client.location.places.ReverseGeocodingProvider
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.model.Location
import app.logdate.shared.model.Place
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TimelinePlacesUnificationTest {
    private val base = Instant.parse("2026-10-12T15:00:00Z")

    @Test
    fun `unifies confirmed history visits and resolves raw coordinate notes without decimal labels`() =
        runTest {
            val gym = SemanticPlace("gym-1", "Neighborhood Gym", 37.7749, -122.4194, userConfirmed = true)
            val confirmedVisit =
                PlaceVisit(
                    id = "visit-gym",
                    start = base,
                    end = base + 1.hours,
                    evidenceIds = listOf("s0"),
                    latitude = gym.latitude,
                    longitude = gym.longitude,
                    confirmedStay = true,
                    place = gym,
                )
            val snapshot =
                LocationHistorySnapshot(
                    items = listOf(confirmedVisit),
                    places = listOf(gym),
                    notes = emptyList(),
                    recordingDevices = listOf("phone-1"),
                    selectedDeviceId = "phone-1",
                    conflicts = emptyList(),
                )
            val noteWithoutPlaceName =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = base + 3.hours,
                    lastUpdated = base + 3.hours,
                    content = "Quick sketch",
                    location = NoteLocation(coordinates = NoteCoordinates(37.7810, -122.4100)),
                )
            val cache =
                PlaceResolutionCache(
                    ResolveLocationToPlaceUseCase(
                        userPlacesRepository =
                            object : UserPlacesRepository {
                                override suspend fun getAllPlaces() = emptyList<Place>()

                                override fun observeAllPlaces(): Flow<List<Place>> = flowOf(emptyList())

                                override suspend fun getPlacesNear(
                                    latitude: Double,
                                    longitude: Double,
                                    radiusMeters: Double,
                                ) = emptyList<Place>()

                                override suspend fun getPlaceById(placeId: String): Place? = null

                                override suspend fun createPlace(place: Place) = Result.success(place)

                                override suspend fun updatePlace(place: Place) = Result.success(place)

                                override suspend fun deletePlace(placeId: String) = Result.success(Unit)

                                override suspend fun searchPlaces(query: String) = emptyList<Place>()
                            },
                        externalPlacesProvider =
                            object : ExternalPlacesProvider {
                                override suspend fun searchNearbyPlaces(location: Location) = emptyList<PlaceSuggestion>()
                            },
                        reverseGeocodingProvider =
                            object : ReverseGeocodingProvider {
                                override suspend fun reverseGeocode(location: Location): GeocodedAddress =
                                    GeocodedAddress("Market St", "SoMa", "San Francisco", "CA", subThoroughfare = "1420")
                            },
                    ),
                )

            val unified = extractUnifiedPlacesVisited(listOf(noteWithoutPlaceName), snapshot, cache)
            assertEquals(listOf("Neighborhood Gym", "1420 Market St"), unified.map { it.name })
            assertTrue(unified.none { it.name.contains("37.781") })
        }
}
