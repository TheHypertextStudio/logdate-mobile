package app.logdate.client.domain.recommendation

import app.logdate.client.datastore.KeyValueStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

interface AmbientPromptHistoryRepository {
    suspend fun canShow(candidate: AmbientPromptCandidate): Boolean

    suspend fun recordShown(
        candidate: AmbientPromptCandidate,
        shownAt: Instant = Clock.System.now(),
    )
}

class DefaultAmbientPromptHistoryRepository(
    private val keyValueStorage: KeyValueStorage,
    private val now: () -> Instant = { Clock.System.now() },
) : AmbientPromptHistoryRepository {
    private val mutex = Mutex()
    private val pendingHistory = mutableListOf<AmbientPromptHistoryRecord>()

    override suspend fun canShow(candidate: AmbientPromptCandidate): Boolean =
        mutex.withLock {
            val currentTime = now()
            val records = loadRecords(currentTime)
            if (records.any { record -> currentTime - record.shownAt < GLOBAL_SUPPRESSION_WINDOW }) {
                return@withLock false
            }

            val policy = candidate.family.policy()
            val familyRecords = records.filter { it.family == candidate.family }
            if (familyRecords.any { record -> record.dedupeKey == candidate.dedupeKey }) {
                return@withLock false
            }
            if (familyRecords.any { record -> currentTime - record.shownAt < policy.minGap }) {
                return@withLock false
            }

            val today = currentTime.toLocalDateTime(TimeZone.currentSystemDefault()).date
            val shownToday =
                familyRecords.count { record ->
                    record.shownAt.toLocalDateTime(TimeZone.currentSystemDefault()).date == today
                }
            shownToday < policy.maxPerDay
        }

    override suspend fun recordShown(
        candidate: AmbientPromptCandidate,
        shownAt: Instant,
    ) = mutex.withLock {
        pendingHistory +=
            AmbientPromptHistoryRecord(candidate.family, candidate.dedupeKey, Instant.fromEpochMilliseconds(shownAt.toEpochMilliseconds()))
        loadRecords(shownAt)
        Unit
    }

    private suspend fun loadRecords(referenceTime: Instant): List<AmbientPromptHistoryRecord> {
        val stored = keyValueStorage.getStringOrThrow(KEY_PROMPT_HISTORY)
        val decoded =
            runCatching { stored?.let { json.decodeFromString<List<AmbientPromptHistoryRecord>>(it) }.orEmpty() }
                .getOrDefault(emptyList())
        val cutoff = referenceTime - HISTORY_RETENTION
        val records = (decoded + pendingHistory).distinct().filter { it.shownAt >= cutoff }
        if (pendingHistory.isNotEmpty()) {
            persist(records)
            pendingHistory.clear()
        }
        return records
    }

    private suspend fun persist(records: List<AmbientPromptHistoryRecord>) {
        keyValueStorage.putStringOrThrow(KEY_PROMPT_HISTORY, json.encodeToString(records.sortedBy(AmbientPromptHistoryRecord::shownAt)))
    }

    private fun AmbientPromptFamily.policy(): AmbientPromptPolicy =
        when (this) {
            AmbientPromptFamily.CAPTURE_NUDGES -> {
                AmbientPromptPolicy(
                    maxPerDay = 2,
                    minGap = 3.hours,
                )
            }

            AmbientPromptFamily.DRAFT_RESCUE,
            AmbientPromptFamily.MEMORY_RECALL,
            -> {
                AmbientPromptPolicy(maxPerDay = 1, minGap = 24.hours)
            }

            AmbientPromptFamily.EVENT_NUDGE -> {
                // Up to three event nudges per day with at least six hours between them. The
                // dedupeKey is `event:<id>` so the same event can never fire twice regardless
                // of these limits.
                AmbientPromptPolicy(maxPerDay = 3, minGap = 6.hours)
            }
        }

    companion object {
        private const val KEY_PROMPT_HISTORY = "ambient_prompt_history"
        private val json = Json { ignoreUnknownKeys = true }
        private val GLOBAL_SUPPRESSION_WINDOW: Duration = 1.hours
        private val HISTORY_RETENTION: Duration = 14.days
    }
}

private data class AmbientPromptPolicy(
    val maxPerDay: Int,
    val minGap: Duration,
)

@Serializable
private data class AmbientPromptHistoryRecord(
    val family: AmbientPromptFamily,
    val dedupeKey: String,
    @Serializable(with = InstantAsLongSerializer::class)
    val shownAt: Instant,
)
