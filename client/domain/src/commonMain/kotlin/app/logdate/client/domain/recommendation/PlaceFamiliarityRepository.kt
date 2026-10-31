package app.logdate.client.domain.recommendation

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.domain.LenientJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

interface PlaceFamiliarityRepository {
    suspend fun get(placeKey: String): PlaceFamiliarityRecord?

    suspend fun recordVisit(
        placeKey: String,
        displayName: String?,
        visitedAt: Instant = Clock.System.now(),
    )
}

/** Stores how often each place was visited. Visits are applied one at a time so concurrent arrivals are all counted. */
class DefaultPlaceFamiliarityRepository(
    private val keyValueStorage: KeyValueStorage,
) : PlaceFamiliarityRepository {
    private val mutex = Mutex()

    override suspend fun get(placeKey: String): PlaceFamiliarityRecord? = mutex.withLock { loadRecords()[placeKey] }

    override suspend fun recordVisit(
        placeKey: String,
        displayName: String?,
        visitedAt: Instant,
    ) = mutex.withLock {
        val records = loadRecords().toMutableMap()
        val existing = records[placeKey]
        val updated =
            when {
                existing == null ->
                    PlaceFamiliarityRecord(
                        placeKey = placeKey,
                        displayName = displayName,
                        visitCount = 1,
                        lastSeenAt = visitedAt,
                    )
                visitedAt - existing.lastSeenAt < REVISIT_COLLAPSE_WINDOW ->
                    existing.copy(
                        displayName = displayName ?: existing.displayName,
                        lastSeenAt = visitedAt,
                    )
                else ->
                    existing.copy(
                        displayName = displayName ?: existing.displayName,
                        visitCount = existing.visitCount + 1,
                        lastSeenAt = visitedAt,
                    )
            }
        records[placeKey] = updated
        val encoded = json.encodeToString(records.values.sortedBy(PlaceFamiliarityRecord::placeKey))
        keyValueStorage.putStringOrThrow(KEY_PLACE_FAMILIARITY, encoded)
    }

    /** An unreadable record throws rather than reporting every place as new, so a known place never triggers a novel arrival. */
    private suspend fun loadRecords(): Map<String, PlaceFamiliarityRecord> {
        val stored = keyValueStorage.getStringOrThrow(KEY_PLACE_FAMILIARITY) ?: return emptyMap()
        return try {
            val records = json.decodeFromString<List<PlaceFamiliarityRecord>>(stored)
            check(records.all { it.placeKey.isNotBlank() && it.visitCount > 0 }) { "Invalid place familiarity" }
            records.associateBy(PlaceFamiliarityRecord::placeKey)
        } catch (e: Exception) {
            throw IllegalStateException("Unreadable place familiarity", e)
        }
    }

    companion object {
        private const val KEY_PLACE_FAMILIARITY = "ambient_place_familiarity"
        private val json = LenientJson
        private val REVISIT_COLLAPSE_WINDOW = 12.hours
    }
}

@Serializable
data class PlaceFamiliarityRecord(
    val placeKey: String,
    val displayName: String?,
    val visitCount: Int,
    @Serializable(with = InstantAsLongSerializer::class)
    val lastSeenAt: Instant,
)
