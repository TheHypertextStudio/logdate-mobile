package app.logdate.client.domain.recommendation

import app.logdate.client.domain.events.ObserveUpcomingEventsUseCase
import app.logdate.client.domain.location.ObserveLocationStopsUseCase
import app.logdate.client.domain.notes.HasNotesForTodayUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.toDisplayName
import app.logdate.client.domain.places.toPlaceKey
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GenerateAmbientPromptCandidatesUseCase(
    private val memoriesSettingsRepository: MemoriesSettingsRepository,
    private val hasNotesForToday: HasNotesForTodayUseCase,
    private val fetchMostRecentDraft: FetchMostRecentDraftUseCase,
    private val getMemoryRecall: GetMemoryRecallUseCase,
    private val observeLocationStops: ObserveLocationStopsUseCase,
    private val observeUpcomingEvents: ObserveUpcomingEventsUseCase,
    private val eventRepository: EventRepository,
    private val notesRepository: JournalNotesRepository,
    private val placeResolutionCache: PlaceResolutionCache,
    private val placeFamiliarityRepository: PlaceFamiliarityRepository,
    private val now: () -> Instant = { Clock.System.now() },
) {
    suspend operator fun invoke(triggerContext: AmbientPromptTriggerContext): List<AmbientPromptCandidate> {
        val settings = memoriesSettingsRepository.getSettings()
        if (!settings.contextualRecommendationsEnabled || !settings.ambientPromptsEnabled) {
            return emptyList()
        }

        val currentTime = now()
        val notes = notesRepository.allNotesObserved.first()
        val candidates = mutableListOf<AmbientPromptCandidate>()

        if (settings.draftRescueEnabled) {
            val draft = fetchMostRecentDraft().first()
            val hasSavedEntrySinceDraft =
                draft != null && notes.any { note -> note.creationTimestamp >= draft.updatedAt }
            if (draft != null && currentTime - draft.updatedAt >= DRAFT_RESCUE_AGE && !hasSavedEntrySinceDraft) {
                candidates +=
                    AmbientPromptCandidate(
                        family = AmbientPromptFamily.DRAFT_RESCUE,
                        score = 100,
                        dedupeKey = "draft:${draft.id}:${draft.updatedAt.toEpochMilliseconds()}",
                        payload = AmbientPromptPayload.DraftRescue(draft.id),
                    )
            }
        }

        if (settings.captureNudgesEnabled) {
            val hasNotesToday = hasNotesForToday().first()
            when (triggerContext) {
                AmbientPromptTriggerContext.MORNING_SCHEDULE -> {
                    if (settings.morningPromptEnabled && !hasNotesToday) {
                        candidates +=
                            AmbientPromptCandidate(
                                family = AmbientPromptFamily.CAPTURE_NUDGES,
                                score = 60,
                                dedupeKey = "capture:morning:${currentTime.toEpochMilliseconds()}",
                                payload = AmbientPromptPayload.CaptureNudge(AmbientCaptureNudgeStyle.MORNING),
                            )
                    }
                }

                AmbientPromptTriggerContext.EVENING_SCHEDULE -> {
                    if (settings.eveningPromptEnabled && !hasNotesToday) {
                        candidates +=
                            AmbientPromptCandidate(
                                family = AmbientPromptFamily.CAPTURE_NUDGES,
                                score = 50,
                                dedupeKey = "capture:evening:${currentTime.toEpochMilliseconds()}",
                                payload = AmbientPromptPayload.CaptureNudge(AmbientCaptureNudgeStyle.EVENING),
                            )
                    }
                }

                AmbientPromptTriggerContext.PERIODIC -> {
                    val novelPlaceCandidate = buildNovelPlaceCandidate(notes = notes, currentTime = currentTime)
                    if (novelPlaceCandidate != null) {
                        candidates += novelPlaceCandidate
                    }
                }
            }
        }

        if (triggerContext == AmbientPromptTriggerContext.PERIODIC && settings.memoryRecallNotificationsEnabled) {
            val recall =
                getMemoryRecall(
                    aiEnabled = settings.aiRecallEnabled,
                    recallMode = settings.recallMode,
                    contentTypes = settings.widgetContentTypes,
                ).first()
            if (recall != null) {
                candidates +=
                    AmbientPromptCandidate(
                        family = AmbientPromptFamily.MEMORY_RECALL,
                        score = 80,
                        dedupeKey = "recall:${recall.date}",
                        payload =
                            AmbientPromptPayload.MemoryRecall(
                                date = recall.date,
                                summary = recall.summary,
                            ),
                    )
            }
        }

        if (triggerContext == AmbientPromptTriggerContext.PERIODIC && settings.eventNudgesEnabled) {
            val upcoming = observeUpcomingEvents().first()
            for (event in upcoming) {
                // Skip events the user has already captured for — once a note is attached the
                // nudge no longer makes sense. Done as a one-shot fetch on the small candidate
                // set so we don't subscribe to a junction observation per event.
                val hasCaptures = eventRepository.observeNotesForEvent(event.id).first().isNotEmpty()
                if (hasCaptures) continue
                candidates +=
                    AmbientPromptCandidate(
                        family = AmbientPromptFamily.EVENT_NUDGE,
                        // Above MEMORY_RECALL (80), below DRAFT_RESCUE (100): an upcoming event
                        // is more time-sensitive than a generic recall but less urgent than
                        // recovering work the user already started.
                        score = 90,
                        dedupeKey = "event:${event.id}",
                        payload =
                            AmbientPromptPayload.EventNudge(
                                eventId = event.id,
                                title = event.title,
                                startTime = event.startTime,
                                isAllDay = event.isAllDay,
                            ),
                    )
            }
        }

        return candidates.sortedByDescending(AmbientPromptCandidate::score)
    }

    private suspend fun buildNovelPlaceCandidate(
        notes: List<app.logdate.client.repository.journals.JournalNote>,
        currentTime: Instant,
    ): AmbientPromptCandidate? {
        val candidateStop =
            observeLocationStops()
                .first()
                .firstOrNull { stop ->
                    stop.hasReliableDuration &&
                        stop.duration >= MINIMUM_NOVEL_PLACE_DURATION &&
                        currentTime - stop.endTime <= NOVEL_PLACE_LOOKBACK
                } ?: return null
        if (notes.any { note -> note.creationTimestamp >= candidateStop.endTime }) {
            return null
        }

        val resolved = placeResolutionCache.resolve(candidateStop.location)
        val placeKey = resolved.toPlaceKey() ?: return null
        val placeName = resolved.toDisplayName() ?: return null

        val familiarity = placeFamiliarityRepository.get(placeKey)
        placeFamiliarityRepository.recordVisit(
            placeKey = placeKey,
            displayName = placeName,
            visitedAt = candidateStop.endTime,
        )
        if (familiarity != null) {
            return null
        }

        return AmbientPromptCandidate(
            family = AmbientPromptFamily.CAPTURE_NUDGES,
            score = 90,
            dedupeKey = "capture:novel_place:$placeKey:${candidateStop.startTime.toEpochMilliseconds()}",
            payload =
                AmbientPromptPayload.CaptureNudge(
                    style = AmbientCaptureNudgeStyle.NOVEL_PLACE,
                    placeName = placeName,
                ),
        )
    }

    companion object {
        private val DRAFT_RESCUE_AGE = 6.hours
        private val MINIMUM_NOVEL_PLACE_DURATION = 45.minutes
        private val NOVEL_PLACE_LOOKBACK = 6.hours
    }
}
