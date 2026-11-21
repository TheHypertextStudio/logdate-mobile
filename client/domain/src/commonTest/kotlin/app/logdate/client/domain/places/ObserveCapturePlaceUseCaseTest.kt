package app.logdate.client.domain.places

import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ObserveCapturePlaceUseCaseTest {
    private val park = Location(30.2669, -97.7729, LocationAltitude(0.0, AltitudeUnit.METERS))

    @Test
    fun namesTheSpotWithThePersonsOwnPlaceFirst() =
        runTest {
            val home = Place.UserDefined(Uuid.random(), "Home", park.latitude, park.longitude)
            val useCase = ObserveCapturePlaceUseCase(Provider(), { PlaceResolutionResult.UserDefinedPlace(home) }, Settings())

            assertEquals(CapturePlace(park, "Home", home.id), useCase().first())
        }

    @Test
    fun fallsBackToTheNeighborhoodWhenTheSpotHasNoName() =
        runTest {
            val address = GeocodedAddress(thoroughfare = "Barton Springs Rd", subLocality = "Zilker", locality = "Austin", adminArea = "TX")
            val useCase =
                ObserveCapturePlaceUseCase(Provider(), { PlaceResolutionResult.CoarseLocation(it, address) }, Settings())

            assertEquals(CapturePlace(park, "Zilker"), useCase().first())
        }

    @Test
    fun showsNoPlaceWhenEntriesDontRecordWhereTheyWereWritten() =
        runTest {
            val useCase =
                ObserveCapturePlaceUseCase(
                    Provider(),
                    { error("must not look up a place") },
                    Settings(LocationTrackingSettings(autoTrackForJournalEntries = false)),
                )

            assertTrue(useCase().toList().isEmpty())
        }

    @Test
    fun showsNoPlaceWithoutLocationAccess() =
        runTest {
            val useCase = ObserveCapturePlaceUseCase(Provider(permitted = false), { error("must not look up a place") }, Settings())

            assertTrue(useCase().toList().isEmpty())
        }

    @Test
    fun aFailedLookupStillKnowsWhereTheShotIsWithoutNamingIt() =
        runTest {
            val useCase = ObserveCapturePlaceUseCase(Provider(), { error("offline") }, Settings())

            assertEquals(CapturePlace(park, null), useCase().first())
        }

    private inner class Provider(
        private val permitted: Boolean = true,
    ) : ClientLocationProvider {
        override val currentLocation: SharedFlow<Location> = MutableSharedFlow()

        override fun hasLocationPermission(): Boolean = permitted

        override suspend fun getCurrentLocation(): Location = park

        override suspend fun refreshLocation() {}
    }

    private class Settings(
        private val settings: LocationTrackingSettings = LocationTrackingSettings(),
    ) : LocationTrackingSettingsRepository {
        override suspend fun getSettings(): LocationTrackingSettings = settings

        override fun observeSettings(): Flow<LocationTrackingSettings> = flowOf(settings)

        override suspend fun updateSettings(settings: LocationTrackingSettings) {}

        override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {}

        override suspend fun setTrackingInterval(intervalMinutes: Long) {}
    }
}
