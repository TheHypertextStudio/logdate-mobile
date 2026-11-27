package app.logdate.client.domain.places

import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.location.places.PlaceSuggestion
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Thread-safe two-tier LRU + Room cache for coordinate-to-place lookups.
 *
 * Uses `floor(coord * 2000)` (~55m lattice) to prevent 0-degree truncation collapse, coalesces
 * concurrent in-flight requests per cell under a [Mutex], applies a short negative backoff for
 * offline errors, and supports 8-neighbor invalidation whenever user places change.
 *
 * Singleton instance is shared across all callers (HumanLocationHistoryViewModel,
 * GetHomeRecommendationUseCase, etc.). All resolve() calls are deduplicated here.
 */
class PlaceResolutionCache(
    private val resolveLocationToPlaceUseCase: ResolveLocationToPlaceUseCase,
    private val ttl: Duration = 30.days,
    private val negativeTtl: Duration = 5.minutes,
    private val maxSize: Int = 256,
    private val store: PlaceResolutionCacheStore? = null,
    private val clock: Clock = Clock.System,
) {
    private data class CacheEntry(
        val result: PlaceResolutionResult,
        val resolvedAt: Instant,
        val expiresAt: Instant,
    )

    private val mutex = Mutex()
    private val cache = linkedMapOf<Pair<Int, Int>, CacheEntry>()
    private val inFlight = mutableMapOf<Pair<Int, Int>, CompletableDeferred<PlaceResolutionResult>>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun resolve(location: Location): PlaceResolutionResult = resolveWithContext(location)

    suspend fun resolveWithContext(
        location: Location,
        searchRadiusMeters: Double = 100.0,
        dwellDuration: Duration? = null,
        isOvernightResidential: Boolean = false,
    ): PlaceResolutionResult {
        val key = location.toCacheKey()
        val bypassCachedCommercial = isOvernightResidential || (dwellDuration != null && dwellDuration > 45.minutes)
        return when (val lookup = lookupOrJoinInFlight(key, bypassCachedCommercial)) {
            is CacheLookup.Hit -> {
                lookup.result
            }

            is CacheLookup.Follower -> {
                lookup.deferred.await()
            }

            is CacheLookup.Leader -> {
                fetchAndCache(key, location, searchRadiusMeters, dwellDuration, isOvernightResidential, lookup.deferred)
            }
        }
    }

    private suspend fun lookupOrJoinInFlight(
        key: Pair<Int, Int>,
        bypassCachedCommercial: Boolean,
    ): CacheLookup =
        mutex.withLock {
            val now = clock.now()
            val memoryHit = cache[key]
            if (memoryHit != null && now < memoryHit.expiresAt && !shouldBypass(memoryHit.result, bypassCachedCommercial)) {
                // Re-insert for true access-order LRU eviction.
                cache.remove(key)
                cache[key] = memoryHit
                return@withLock CacheLookup.Hit(memoryHit.result)
            }
            if (!bypassCachedCommercial) {
                val diskHit = readStoreEntry(key, now)
                if (diskHit != null) {
                    putMemoryLocked(key, diskHit)
                    return@withLock CacheLookup.Hit(diskHit.result)
                }
            }
            val existingFlight = inFlight[key]
            if (existingFlight != null) {
                CacheLookup.Follower(existingFlight)
            } else {
                val deferred = CompletableDeferred<PlaceResolutionResult>()
                inFlight[key] = deferred
                CacheLookup.Leader(deferred)
            }
        }

    private suspend fun fetchAndCache(
        key: Pair<Int, Int>,
        location: Location,
        searchRadiusMeters: Double,
        dwellDuration: Duration?,
        isOvernightResidential: Boolean,
        ownedDeferred: CompletableDeferred<PlaceResolutionResult>,
    ): PlaceResolutionResult =
        try {
            val result =
                resolveLocationToPlaceUseCase(
                    location = location,
                    searchRadiusMeters = searchRadiusMeters,
                    dwellDuration = dwellDuration,
                    isOvernightResidential = isOvernightResidential,
                )
            val resolvedAt = clock.now()
            val expiresAt = if (result is PlaceResolutionResult.UnknownLocation) resolvedAt + negativeTtl else resolvedAt + ttl
            val entry = CacheEntry(result, resolvedAt, expiresAt)
            mutex.withLock {
                if (result !is PlaceResolutionResult.UserDefinedPlace) putMemoryLocked(key, entry)
                if (result is PlaceResolutionResult.ExternalSuggestion || result is PlaceResolutionResult.CoarseLocation) {
                    persistEntry(key, result, resolvedAt, expiresAt)
                }
                inFlight.remove(key)
            }
            ownedDeferred.complete(result)
            result
        } catch (error: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            ownedDeferred.completeExceptionally(error)
            throw error
        }

    private sealed interface CacheLookup {
        data class Hit(
            val result: PlaceResolutionResult,
        ) : CacheLookup

        data class Follower(
            val deferred: CompletableDeferred<PlaceResolutionResult>,
        ) : CacheLookup

        data class Leader(
            val deferred: CompletableDeferred<PlaceResolutionResult>,
        ) : CacheLookup
    }

    suspend fun invalidateNear(
        latitude: Double,
        longitude: Double,
    ) {
        val center = toCellKey(latitude, longitude)
        mutex.withLock {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    cache.remove((center.first + dx) to (center.second + dy))
                }
            }
            store?.deleteNeighborhood(
                minX = center.first - 1,
                maxX = center.first + 1,
                minY = center.second - 1,
                maxY = center.second + 1,
            )
        }
    }

    private fun shouldBypass(
        result: PlaceResolutionResult,
        bypassCommercial: Boolean,
    ): Boolean = bypassCommercial && result is PlaceResolutionResult.ExternalSuggestion

    private fun putMemoryLocked(
        key: Pair<Int, Int>,
        entry: CacheEntry,
    ) {
        cache.remove(key)
        cache[key] = entry
        while (cache.size > maxSize) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
    }

    private suspend fun readStoreEntry(
        key: Pair<Int, Int>,
        now: Instant,
    ): CacheEntry? {
        val row = store?.get(key.first, key.second) ?: return null
        if (now.toEpochMilliseconds() >= row.expiresAtMs) return null
        val result = decodePayload(row) ?: return null
        return CacheEntry(
            result = result,
            resolvedAt = Instant.fromEpochMilliseconds(row.resolvedAtMs),
            expiresAt = Instant.fromEpochMilliseconds(row.expiresAtMs),
        )
    }

    private suspend fun persistEntry(
        key: Pair<Int, Int>,
        result: PlaceResolutionResult,
        resolvedAt: Instant,
        expiresAt: Instant,
    ) {
        val activeStore = store ?: return
        val (kind, payloadJson) =
            when (result) {
                is PlaceResolutionResult.ExternalSuggestion -> {
                    val s = result.suggestion
                    val dto =
                        CachedExternalDto(
                            name = s.name,
                            address = s.address,
                            latitude = s.latitude,
                            longitude = s.longitude,
                            confidence = s.confidence,
                            category = s.category,
                            externalId = s.externalId,
                            types = s.types,
                        )
                    KIND_EXTERNAL to json.encodeToString(dto)
                }

                is PlaceResolutionResult.CoarseLocation -> {
                    val dto =
                        CachedCoarseDto(
                            latitude = result.location.latitude,
                            longitude = result.location.longitude,
                            thoroughfare = result.address.thoroughfare,
                            subLocality = result.address.subLocality,
                            locality = result.address.locality,
                            adminArea = result.address.adminArea,
                            subThoroughfare = result.address.subThoroughfare,
                            countryCode = result.address.countryCode,
                        )
                    KIND_COARSE to json.encodeToString(dto)
                }

                else -> {
                    return
                }
            }
        activeStore.upsert(
            CachedPlaceRow(
                cellX = key.first,
                cellY = key.second,
                resultKind = kind,
                payloadJson = payloadJson,
                resolvedAtMs = resolvedAt.toEpochMilliseconds(),
                expiresAtMs = expiresAt.toEpochMilliseconds(),
            ),
        )
    }

    private fun decodePayload(row: CachedPlaceRow): PlaceResolutionResult? =
        runCatching {
            when (row.resultKind) {
                KIND_EXTERNAL -> {
                    val dto = json.decodeFromString(CachedExternalDto.serializer(), row.payloadJson)
                    PlaceResolutionResult.ExternalSuggestion(
                        PlaceSuggestion(
                            name = dto.name,
                            address = dto.address,
                            latitude = dto.latitude,
                            longitude = dto.longitude,
                            confidence = dto.confidence,
                            category = dto.category,
                            externalId = dto.externalId,
                            types = dto.types,
                        ),
                    )
                }

                KIND_COARSE -> {
                    val dto = json.decodeFromString(CachedCoarseDto.serializer(), row.payloadJson)
                    PlaceResolutionResult.CoarseLocation(
                        location = Location(dto.latitude, dto.longitude, LocationAltitude(0.0, AltitudeUnit.METERS)),
                        address =
                            GeocodedAddress(
                                thoroughfare = dto.thoroughfare,
                                subLocality = dto.subLocality,
                                locality = dto.locality,
                                adminArea = dto.adminArea,
                                subThoroughfare = dto.subThoroughfare,
                                countryCode = dto.countryCode,
                            ),
                    )
                }

                else -> {
                    null
                }
            }
        }.getOrNull()

    private fun Location.toCacheKey(): Pair<Int, Int> = toCellKey(latitude, longitude)

    private fun toCellKey(
        latitude: Double,
        longitude: Double,
    ): Pair<Int, Int> = floor(latitude * GRID_SCALE).toInt() to floor(longitude * GRID_SCALE).toInt()

    @Serializable
    private data class CachedExternalDto(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val confidence: Int,
        val category: String? = null,
        val externalId: String? = null,
        val types: List<String> = emptyList(),
    )

    @Serializable
    private data class CachedCoarseDto(
        val latitude: Double,
        val longitude: Double,
        val thoroughfare: String? = null,
        val subLocality: String? = null,
        val locality: String? = null,
        val adminArea: String? = null,
        val subThoroughfare: String? = null,
        val countryCode: String? = null,
    )

    private companion object {
        const val GRID_SCALE = 2000.0
        const val KIND_EXTERNAL = "EXTERNAL"
        const val KIND_COARSE = "COARSE"
    }
}
