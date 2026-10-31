package app.logdate.client.domain.events

import app.logdate.client.domain.location.LocationStop
import app.logdate.client.domain.location.LocationStopEvidenceKind
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.client.domain.places.toDisplayName
import app.logdate.client.domain.places.toPlaceKey
import app.logdate.client.domain.places.toUserPlaceId
import app.logdate.client.domain.recommendation.PlaceFamiliarityRepository
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.events.EventCluster
import app.logdate.client.intelligence.events.EventName
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.client.text.UiText
import app.logdate.client.text.resolve
import app.logdate.client.text.toUiText
import app.logdate.shared.model.Event
import app.logdate.shared.model.Location
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.domain.generated.resources.Res
import logdate.client.domain.generated.resources.inferred_event_description_at_place
import logdate.client.domain.generated.resources.inferred_event_description_at_place_with_captures
import logdate.client.domain.generated.resources.inferred_event_description_time_of_day
import logdate.client.domain.generated.resources.inferred_event_description_time_of_day_with_captures
import logdate.client.domain.generated.resources.inferred_event_time_afternoon
import logdate.client.domain.generated.resources.inferred_event_time_evening
import logdate.client.domain.generated.resources.inferred_event_time_late_night
import logdate.client.domain.generated.resources.inferred_event_time_midday
import logdate.client.domain.generated.resources.inferred_event_time_morning
import logdate.client.domain.generated.resources.inferred_event_title_at_place
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Walks the user's recent location stops, photos, and notes and turns ambiguous "you spent
 * time somewhere and captured something" patterns into concrete [Event]s the timeline can
 * surface.
 *
 * The pipeline is intentionally heuristic: it is run by a background worker on the user's
 * device, so the cost of a missed cluster is small (it'll be picked up on the next sweep)
 * and the cost of a false positive is significant (the user sees an event in their timeline
 * that doesn't represent anything they remember). The thresholds favor restraint.
 *
 * Naming is the only AI-touched step. When the cache and the model are unavailable — or
 * when [aiNamingEnabled] is `false` — every cluster falls back to a deterministic
 * "<time of day> at <place>" label so the worker still produces useful results offline.
 *
 * The fallback name is saved with the event, so it is written once in the device language.
 * [resolveText] turns it into words; tests pass a stand-in because they cannot read Compose
 * resources.
 */
class InferEventsUseCase(
    private val observeLocationStops: (since: Instant) -> Flow<List<LocationStop>>,
    private val indexedMediaRepository: IndexedMediaRepository,
    private val notesRepository: JournalNotesRepository,
    private val placeFamiliarity: PlaceFamiliarityRepository,
    private val resolvePlaceForLocation: suspend (Location) -> PlaceResolutionResult,
    private val eventRepository: EventRepository,
    private val suggestEventName: suspend (EventCluster) -> AIResult<EventName>,
    private val now: () -> Instant = { Clock.System.now() },
    private val resolveText: suspend (UiText) -> String = { text -> text.resolve() },
) {
    /**
     * Runs one inference pass.
     *
     * @param window How far back to scan for stops, photos, and notes. Anything older is
     *   assumed to already have been processed by an earlier run.
     * @param sensitivity Controls the minimum signal count a cluster must reach before it
     *   becomes an event. Backed by the user's auto-events settings.
     * @param aiNamingEnabled Whether to invoke [suggestEventName] for a label. When false,
     *   the use case never calls the extractor and every event uses the heuristic name.
     * @return The number of events created on this pass, or `Result.failure` if a stage
     *   threw something the use case couldn't recover from.
     */
    suspend operator fun invoke(
        window: Duration = 7.days,
        sensitivity: EventInferenceSensitivity = EventInferenceSensitivity.MEDIUM,
        aiNamingEnabled: Boolean = true,
    ): Result<Int> =
        runCatching {
            val nowInstant = now()
            val windowStart = nowInstant - window
            val stops =
                observeLocationStops(windowStart)
                    .first()
                    .filter { stop ->
                        stop.evidenceKind == LocationStopEvidenceKind.STAY &&
                            stop.hasReliableDuration &&
                            stop.duration >= MIN_STOP_DURATION &&
                            stop.startTime >= windowStart
                    }
            if (stops.isEmpty()) {
                return@runCatching 0
            }

            // Pull media, notes, and existing events ONCE for the whole window. Each per-stop
            // cluster then filters in-memory by its [stop.startTime - SLACK, stop.endTime + SLACK]
            // bounds. Three reads for the whole pass instead of three per stop.
            val windowEnd = nowInstant + SLACK
            val windowFetchStart = windowStart - SLACK
            val mediaInWindow = indexedMediaRepository.getForPeriod(windowFetchStart, windowEnd).first()
            val notesInWindow = notesRepository.observeNotesInRange(windowFetchStart, windowEnd).first()
            val eventsInWindow = eventRepository.observeEventsForDateRange(windowFetchStart, windowEnd).first()

            var created = 0
            for (stop in stops) {
                val place = resolvePlace(stop)
                if (place == null) {
                    Napier.d(tag = TAG, message = "Skipping stop with no resolvable place")
                    continue
                }
                if (isFamiliarPlace(place.placeKey)) {
                    continue
                }

                val clusterStart = stop.startTime - SLACK
                val clusterEnd = stop.endTime + SLACK
                if (clusterOverlapsExistingEvent(eventsInWindow, clusterStart, clusterEnd)) {
                    continue
                }

                val media = mediaInWindow.filter { it.timestamp in clusterStart..clusterEnd }
                val notes = notesInWindow.filter { it.creationTimestamp in clusterStart..clusterEnd }
                if (media.size + notes.size < sensitivity.signalThreshold) {
                    continue
                }

                val cluster =
                    EventCluster(
                        placeName = place.displayName,
                        timeOfDay = resolveText(describeTimeOfDay(stop.startTime).toUiText()),
                        mediaCount = media.size,
                        noteCount = notes.size,
                        firstNoteSnippet = firstTextSnippet(notes),
                    )
                val name = nameCluster(cluster, aiNamingEnabled)
                val event =
                    Event(
                        title = name.title,
                        description = name.description,
                        startTime = stop.startTime,
                        endTime = stop.endTime,
                        placeId = place.userPlaceId,
                    )
                val result = eventRepository.createEvent(event)
                if (result.isSuccess) {
                    created += 1
                } else {
                    Napier.w(
                        tag = TAG,
                        message = "Failed to persist inferred event",
                        throwable = result.exceptionOrNull(),
                    )
                }
            }
            created
        }.onFailure { error ->
            Napier.e(tag = TAG, message = "Event inference pass failed", throwable = error)
        }

    private suspend fun resolvePlace(stop: LocationStop): ResolvedPlace? {
        val resolved = resolvePlaceForLocation(stop.location)
        val key = resolved.toPlaceKey() ?: return null
        val name = resolved.toDisplayName() ?: return null
        return ResolvedPlace(
            displayName = name,
            placeKey = key,
            userPlaceId = resolved.toUserPlaceId(),
        )
    }

    private suspend fun isFamiliarPlace(placeKey: String): Boolean {
        val familiarity = placeFamiliarity.get(placeKey) ?: return false
        return familiarity.visitCount >= FAMILIAR_VISIT_THRESHOLD
    }

    /**
     * True when any pre-fetched event overlaps the cluster's `[clusterStart, clusterEnd)`
     * range. Matches the half-open form used by [EventDao.observeForDateRange] so a cluster
     * starting at exactly `existing.endTime` is treated as immediately-after, not overlapping.
     */
    private fun clusterOverlapsExistingEvent(
        candidates: List<Event>,
        clusterStart: Instant,
        clusterEnd: Instant,
    ): Boolean =
        candidates.any { existing ->
            existing.startTime < clusterEnd && (existing.endTime ?: existing.startTime) >= clusterStart
        }

    private suspend fun nameCluster(
        cluster: EventCluster,
        aiNamingEnabled: Boolean,
    ): EventNameResult {
        if (!aiNamingEnabled) {
            return heuristicName(cluster)
        }
        return when (val response = suggestEventName(cluster)) {
            is AIResult.Success -> EventNameResult(response.value.title, response.value.description)
            is AIResult.Error, is AIResult.Unavailable -> heuristicName(cluster)
        }
    }

    private suspend fun heuristicName(cluster: EventCluster): EventNameResult {
        val placeName = cluster.placeName
        val title =
            if (placeName != null) {
                resolveText(Res.string.inferred_event_title_at_place.toUiText(cluster.timeOfDay, placeName))
            } else {
                cluster.timeOfDay
            }
        val description =
            when {
                placeName != null && cluster.mediaCount > 0 ->
                    Res.plurals.inferred_event_description_at_place_with_captures
                        .toUiText(cluster.mediaCount, placeName, cluster.mediaCount)
                placeName != null -> Res.string.inferred_event_description_at_place.toUiText(placeName)
                cluster.mediaCount > 0 ->
                    Res.plurals.inferred_event_description_time_of_day_with_captures
                        .toUiText(cluster.mediaCount, cluster.timeOfDay.lowercase(), cluster.mediaCount)
                else -> Res.string.inferred_event_description_time_of_day.toUiText(cluster.timeOfDay.lowercase())
            }
        return EventNameResult(title = title, description = resolveText(description))
    }

    private fun firstTextSnippet(notes: List<JournalNote>): String? {
        val text =
            notes
                .filterIsInstance<JournalNote.Text>()
                .minByOrNull { it.creationTimestamp }
                ?.content
                ?: return null
        return text.take(MAX_SNIPPET_LENGTH)
    }

    /**
     * Picks a coarse, prompt-friendly time-of-day phrase for [instant]. Used as both the
     * heuristic title and as the cluster context handed to the AI extractor. The hour is
     * resolved in the device's local timezone — the user's "evening" should not get labeled
     * "early morning" because the cluster timestamp happens to fall after UTC midnight.
     */
    private fun describeTimeOfDay(instant: Instant): StringResource {
        val hour = instant.toLocalDateTime(TimeZone.currentSystemDefault()).hour
        return when (hour) {
            in 5..10 -> Res.string.inferred_event_time_morning
            in 11..13 -> Res.string.inferred_event_time_midday
            in 14..17 -> Res.string.inferred_event_time_afternoon
            in 18..21 -> Res.string.inferred_event_time_evening
            else -> Res.string.inferred_event_time_late_night
        }
    }

    private data class ResolvedPlace(
        val displayName: String,
        val placeKey: String,
        val userPlaceId: Uuid?,
    )

    private data class EventNameResult(
        val title: String,
        val description: String,
    )

    companion object {
        private const val TAG = "InferEventsUseCase"
        private const val MAX_SNIPPET_LENGTH = 200
        private const val FAMILIAR_VISIT_THRESHOLD = 5
        private val MIN_STOP_DURATION = 30.minutes
        private val SLACK = 15.minutes
    }
}
