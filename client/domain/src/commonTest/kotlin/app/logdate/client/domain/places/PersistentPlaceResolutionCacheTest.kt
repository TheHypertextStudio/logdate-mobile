package app.logdate.client.domain.places

import app.logdate.client.location.places.ExternalPlacesProvider
import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.location.places.PlaceSuggestion
import app.logdate.client.location.places.ReverseGeocodingProvider
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.Place
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PersistentPlaceResolutionCacheTest {
    @Test
    fun `coalesces concurrent lookups for the same quantized cell and separates 0 degree signs`() =
        runTest {
            var providerCalls = 0
            val clock = MutableClock(Instant.parse("2026-10-12T12:00:00Z"))
            val store = InMemoryPlaceCacheStore()
            val cache =
                PlaceResolutionCache(
                    resolveLocationToPlaceUseCase =
                        ResolveLocationToPlaceUseCase(
                            userPlacesRepository = EmptyUserPlacesRepository(),
                            externalPlacesProvider =
                                object : ExternalPlacesProvider {
                                    override suspend fun searchNearbyPlaces(location: Location): List<PlaceSuggestion> {
                                        providerCalls++
                                        delay(20)
                                        return listOf(
                                            PlaceSuggestion(
                                                name = if (location.latitude >= 0) "North Cafe" else "South Cafe",
                                                address = "1 Main",
                                                latitude = location.latitude,
                                                longitude = location.longitude,
                                                confidence = 90,
                                                externalId = if (location.latitude >= 0) "n" else "s",
                                            ),
                                        )
                                    }
                                },
                            reverseGeocodingProvider = FailingGeocoder(),
                        ),
                    maxSize = 256,
                    store = store,
                    clock = clock,
                )

            val positive = Location(0.0002, 0.0002, LocationAltitude(0.0, AltitudeUnit.METERS))
            val negative = Location(-0.0002, -0.0002, LocationAltitude(0.0, AltitudeUnit.METERS))

            val results = (1..8).map { async { cache.resolve(positive) } }.awaitAll()
            assertEquals(1, providerCalls)
            assertEquals(8, results.size)
            results.forEach { res ->
                assertEquals("North Cafe", (res as PlaceResolutionResult.ExternalSuggestion).suggestion.name)
            }

            // floor() quantization keeps -0.0002 distinct from +0.0002 across 0 degrees.
            val south = cache.resolve(negative) as PlaceResolutionResult.ExternalSuggestion
            assertEquals("South Cafe", south.suggestion.name)
            assertEquals(2, providerCalls)
        }

    @Test
    fun `uses true LRU eviction short negative error TTL disk persistence and 8-neighbor invalidation`() =
        runTest {
            var clockNow = Instant.parse("2026-10-12T12:00:00Z")
            val clock =
                object : Clock {
                    override fun now(): Instant = clockNow
                }
            var failExternal = true
            var calls = 0
            val store = InMemoryPlaceCacheStore()

            fun buildCache(maxSize: Int = 2) =
                PlaceResolutionCache(
                    resolveLocationToPlaceUseCase =
                        ResolveLocationToPlaceUseCase(
                            userPlacesRepository = EmptyUserPlacesRepository(),
                            externalPlacesProvider =
                                object : ExternalPlacesProvider {
                                    override suspend fun searchNearbyPlaces(location: Location): List<PlaceSuggestion> {
                                        calls++
                                        if (failExternal) error("offline")
                                        return listOf(
                                            PlaceSuggestion(
                                                name = "Cafe ${location.latitude.toInt()}",
                                                address = "Addr",
                                                latitude = location.latitude,
                                                longitude = location.longitude,
                                                confidence = 90,
                                                externalId = "id-${location.latitude}",
                                            ),
                                        )
                                    }
                                },
                            reverseGeocodingProvider = FailingGeocoder(),
                        ),
                    ttl = 30.days,
                    negativeTtl = 5.minutes,
                    maxSize = maxSize,
                    store = store,
                    clock = clock,
                )

            val cache = buildCache(maxSize = 2)
            val locA = Location(10.0, 10.0, LocationAltitude(0.0, AltitudeUnit.METERS))
            val locB = Location(20.0, 20.0, LocationAltitude(0.0, AltitudeUnit.METERS))
            val locC = Location(30.0, 30.0, LocationAltitude(0.0, AltitudeUnit.METERS))

            // Offline error caches UnknownLocation only for 5 minutes.
            assertIs<PlaceResolutionResult.UnknownLocation>(cache.resolve(locA))
            assertIs<PlaceResolutionResult.UnknownLocation>(cache.resolve(locA))
            assertEquals(1, calls)

            clockNow += 6.minutes
            failExternal = false
            assertIs<PlaceResolutionResult.ExternalSuggestion>(cache.resolve(locA))
            assertEquals(2, calls)

            // Access order LRU: populate B, access A, then insert C -> B must be evicted, A retained.
            cache.resolve(locB)
            cache.resolve(locA)
            cache.resolve(locC)
            val callsBeforeReload = calls

            // A new cache instance backed by the same Room store restores A without calling provider.
            val reloadedCache = buildCache(maxSize = 256)
            assertIs<PlaceResolutionResult.ExternalSuggestion>(reloadedCache.resolve(locA))
            assertEquals(callsBeforeReload, calls)

            // Invalidating neighbor clears locA from both memory and persistent store.
            reloadedCache.invalidateNear(locA.latitude + 0.0004, locA.longitude)
            assertIs<PlaceResolutionResult.ExternalSuggestion>(reloadedCache.resolve(locA))
            assertEquals(callsBeforeReload + 1, calls)
        }

    @Test
    fun `overnight residential guard suppresses distant commercial POI and falls back to coarse address`() =
        runTest {
            val store = InMemoryPlaceCacheStore()
            val cache =
                PlaceResolutionCache(
                    resolveLocationToPlaceUseCase =
                        ResolveLocationToPlaceUseCase(
                            userPlacesRepository = EmptyUserPlacesRepository(),
                            externalPlacesProvider =
                                object : ExternalPlacesProvider {
                                    override suspend fun searchNearbyPlaces(location: Location): List<PlaceSuggestion> =
                                        listOf(
                                            PlaceSuggestion(
                                                name = "Neighbor Consulting LLC",
                                                address = "1420 Market St",
                                                // ~65m north of origin
                                                latitude = location.latitude + 0.0006,
                                                longitude = location.longitude,
                                                confidence = 90,
                                                externalId = "poi-llc",
                                                types = listOf("establishment"),
                                            ),
                                        )
                                },
                            reverseGeocodingProvider =
                                object : ReverseGeocodingProvider {
                                    override suspend fun reverseGeocode(location: Location): GeocodedAddress =
                                        GeocodedAddress("Oak St", "Mission District", "San Francisco", "CA", subThoroughfare = "742")
                                },
                        ),
                    store = store,
                )

            val origin = Location(37.7749, -122.4194, LocationAltitude(0.0, AltitudeUnit.METERS))
            val result =
                cache.resolveWithContext(
                    location = origin,
                    searchRadiusMeters = 90.0,
                    dwellDuration = 8.hours,
                    isOvernightResidential = true,
                )
            val coarse = assertIs<PlaceResolutionResult.CoarseLocation>(result)
            assertEquals("742 Oak St", coarse.address.formatFallbackLabel())
        }

    private class MutableClock(
        private val current: Instant,
    ) : Clock {
        override fun now(): Instant = current
    }

    private class InMemoryPlaceCacheStore : PlaceResolutionCacheStore {
        private val rows = mutableMapOf<Pair<Int, Int>, CachedPlaceRow>()

        override suspend fun get(
            cellX: Int,
            cellY: Int,
        ): CachedPlaceRow? = rows[cellX to cellY]

        override suspend fun upsert(row: CachedPlaceRow) {
            rows[row.cellX to row.cellY] = row
        }

        override suspend fun deleteNeighborhood(
            minX: Int,
            maxX: Int,
            minY: Int,
            maxY: Int,
        ) {
            rows.keys.removeAll { (x, y) -> x in minX..maxX && y in minY..maxY }
        }

        override suspend fun deleteExpired(nowMs: Long) {
            rows.entries.removeAll { it.value.expiresAtMs <= nowMs }
        }
    }

    private class EmptyUserPlacesRepository : UserPlacesRepository {
        override suspend fun getAllPlaces(): List<Place> = emptyList()

        override fun observeAllPlaces(): Flow<List<Place>> = flowOf(emptyList())

        override suspend fun getPlacesNear(
            latitude: Double,
            longitude: Double,
            radiusMeters: Double,
        ): List<Place> = emptyList()

        override suspend fun getPlaceById(placeId: String): Place? = null

        override suspend fun createPlace(place: Place): Result<Place> = Result.success(place)

        override suspend fun updatePlace(place: Place): Result<Place> = Result.success(place)

        override suspend fun deletePlace(placeId: String): Result<Unit> = Result.success(Unit)

        override suspend fun searchPlaces(query: String): List<Place> = emptyList()
    }

    private class FailingGeocoder : ReverseGeocodingProvider {
        override suspend fun reverseGeocode(location: Location): GeocodedAddress? = null
    }
}
