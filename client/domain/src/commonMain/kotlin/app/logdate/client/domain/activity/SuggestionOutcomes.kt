package app.logdate.client.domain.activity

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.domain.LenientJson
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What an activity suggestion offers. */
@Serializable
enum class SuggestionKind {
    /** Add a moment from a walk, ride or trip that just ended. */
    AFTER_ACTIVITY,

    /** Start the day with a voice note after waking. */
    MORNING_START,
}

/** What the person did with a suggestion. */
@Serializable
enum class SuggestionResult { SHOWN, ACTED_ON, DISMISSED }

/** One thing that happened to a suggestion for the activity that began at [activityStartedAt]. */
@Serializable
data class SuggestionOutcome(
    val kind: SuggestionKind,
    val activityKind: ActivityKind,
    val activityStartedAt: Instant,
    val result: SuggestionResult,
    val at: Instant,
) {
    fun isFor(
        suggestion: SuggestionKind,
        activity: Activity,
    ): Boolean = kind == suggestion && activityKind == activity.kind && activityStartedAt == activity.startedAt
}

/**
 * Which suggestions were shown, acted on or dismissed, for the last 30 days. It stays on this device
 * and is never synced. It keeps each suggestion to one showing per activity, and it is what a decision
 * model could learn from later.
 */
class SuggestionOutcomes(
    private val storage: KeyValueStorage,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    val outcomes: Flow<List<SuggestionOutcome>> = storage.observeString(KEY).map(::decode)

    suspend fun record(
        kind: SuggestionKind,
        activity: Activity,
        result: SuggestionResult,
    ) {
        mutex.withLock {
            val now = clock.now()
            val kept = decode(storage.getString(KEY)).filter { now - it.at <= RETENTION }
            val next = kept + SuggestionOutcome(kind, activity.kind, activity.startedAt, result, now)
            storage.putString(KEY, json.encodeToString(serializer, next))
        }
    }

    private fun decode(text: String?): List<SuggestionOutcome> {
        if (text == null) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            Napier.w("Saved suggestion outcomes could not be read; starting over", e)
            emptyList()
        }
    }

    companion object {
        const val KEY = "activity.suggestions"
        private val RETENTION = 30.days
        private val json = LenientJson
        private val serializer = ListSerializer(SuggestionOutcome.serializer())
    }
}
