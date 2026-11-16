package app.logdate.client.domain.places

import app.logdate.shared.model.Location
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Caches place resolution results to avoid repeated API calls for the same location.
 *
 * Uses a grid-based key (quantized to ~100m cells) to group nearby locations together,
 * dramatically reducing cache misses from GPS micro-jitter while remaining tight enough
 * to distinguish different POIs.
 *
 * Singleton instance is shared across all callers (HumanLocationHistoryViewModel,
 * GetHomeRecommendationUseCase, etc.). All resolve() calls are deduplicated here.
 */
class PlaceResolutionCache(
    private val resolveLocationToPlaceUseCase: ResolveLocationToPlaceUseCase,
    private val ttl: Duration = 24.hours,
    private val maxSize: Int = 200,
) {
    private data class CacheEntry(
        val result: PlaceResolutionResult,
        val resolvedAt: Instant,
    )

    /**
     * LinkedHashMap with access-order mode: least-recently-used entry is
     * at the front and will be evicted first when maxSize is exceeded.
     */
    private val cache: MutableMap<Pair<Int, Int>, CacheEntry> =
        linkedMapOf()

    suspend fun resolve(location: Location): PlaceResolutionResult {
        val key = location.toCacheKey()
        val now = Clock.System.now()
        val cached = cache[key]

        // Return cached result if it exists and is still fresh
        if (cached != null && (now - cached.resolvedAt) <= ttl) {
            return cached.result
        }

        // Resolve fresh, then cache and return
        return resolveLocationToPlaceUseCase(location).also { result ->
            // Evict LRU entry if cache is full
            if (cache.size >= maxSize) {
                val firstKey = cache.keys.first()
                cache.remove(firstKey)
            }
            cache[key] = CacheEntry(result, now)
        }
    }

    /**
     * Quantize lat/lon to a grid cell key.
     *
     * Multiplying by 1000 gives approximately:
     * - lat: 111m per degree → 0.111m per unit → ~111m per 1000 units
     * - lon: ~88m per degree at 45°N → ~88m per 1000 units
     *
     * This is tight enough to distinguish nearby POIs while loose enough to handle
     * GPS jitter (±5–10m) without cache misses.
     */
    private fun Location.toCacheKey(): Pair<Int, Int> = Pair((latitude * 1000).toInt(), (longitude * 1000).toInt())
}
