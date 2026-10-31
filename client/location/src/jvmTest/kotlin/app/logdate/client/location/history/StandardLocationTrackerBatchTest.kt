package app.logdate.client.location.history

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StandardLocationTrackerBatchTest {
    @Test
    fun readingsAreStoredInOneRepositoryWriteUnderOneOwnerLookup() =
        runTest {
            val calls = mutableListOf<String>()
            var saved: List<LocationLogRecord> = emptyList()
            val repository =
                Proxy.newProxyInstance(
                    LocationHistoryRepository::class.java.classLoader,
                    arrayOf(LocationHistoryRepository::class.java),
                ) { _, method, arguments ->
                    // Members returning Result carry a mangled JVM name suffix.
                    val name = method.name.substringBefore('-')
                    calls += name
                    @Suppress("UNCHECKED_CAST")
                    if (name == "logLocations") saved = arguments.first() as List<LocationLogRecord>
                    Unit
                } as LocationHistoryRepository
            val location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS))
            val provider =
                object : ClientLocationProvider {
                    override val currentLocation = MutableSharedFlow<Location>()

                    override fun hasLocationPermission() = true

                    override suspend fun getCurrentLocation() = location

                    override suspend fun refreshLocation() = Unit
                }
            var ownerLookups = 0
            val ownerProvider =
                object : CanonicalOwnerProvider {
                    override suspend fun getCanonicalOwnerId(): String {
                        ownerLookups++
                        return "canonical-owner"
                    }

                    override suspend fun hasBoundOwner() = true
                }
            val tracker = StandardLocationTracker(provider, repository, "device", canonicalOwnerProvider = ownerProvider)

            tracker
                .logLocations(
                    (1..3).map { index ->
                        LocationReading(location, Instant.fromEpochMilliseconds(index * 1000L), mapOf("accuracyMeters" to index.toFloat()))
                    },
                ).getOrThrow()

            assertEquals(listOf("logLocations"), calls)
            assertEquals(1, ownerLookups)
            assertEquals(listOf(1000L, 2000L, 3000L), saved.map { it.timestamp.toEpochMilliseconds() })
            assertEquals(listOf(1f, 2f, 3f), saved.map { it.accuracyMeters })
            assertEquals(setOf("canonical-owner"), saved.map { it.userId }.toSet())
        }
}
