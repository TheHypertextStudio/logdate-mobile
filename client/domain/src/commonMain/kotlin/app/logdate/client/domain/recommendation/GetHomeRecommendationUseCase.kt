package app.logdate.client.domain.recommendation

import app.logdate.client.domain.activity.ActivityKind
import app.logdate.client.domain.activity.ActivityUiState
import app.logdate.client.domain.events.ObserveUpcomingEventsUseCase
import app.logdate.client.domain.notes.HasNotesForTodayUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.repository.events.EventRepository
import app.logdate.shared.model.Event
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Returns the highest-priority home recommendation based on the current app state.
 *
 * Signals are evaluated in priority order:
 * 1. [HomeRecommendation.AfterActivity] or [HomeRecommendation.MorningStart] — from [activity], while
 *    activity awareness is on
 * 2. [HomeRecommendation.CompleteYourDraft] — an unfinished draft is waiting
 * 3. [HomeRecommendation.UpcomingEvent] — an event worth capturing is coming up
 * 4. [HomeRecommendation.MemoryRecall] — past entries worth revisiting
 * 5. [HomeRecommendation.EmptyDay] — the user has not logged anything today
 * 6. [HomeRecommendation.None] — no action needed
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetHomeRecommendationUseCase(
    private val hasNotesForToday: HasNotesForTodayUseCase,
    private val fetchMostRecentDraft: FetchMostRecentDraftUseCase,
    private val getMemoryRecall: GetMemoryRecallUseCase,
    private val observeUpcomingEvents: ObserveUpcomingEventsUseCase,
    private val eventRepository: EventRepository,
    private val clientLocationProvider: ClientLocationProvider,
    private val placeResolutionCache: PlaceResolutionCache,
    private val memoriesSettingsRepository: MemoriesSettingsRepository,
    private val activity: Flow<ActivityUiState> = flowOf(ActivityUiState()),
    private val dismissActivitySuggestion: suspend () -> Unit = {},
    private val useActivitySuggestion: suspend () -> Unit = {},
) {
    /** Puts away the activity suggestion or the morning start that is showing. */
    suspend fun dismissActivity() = dismissActivitySuggestion()

    /** Notes that the person started a moment from the activity suggestion or the morning start. */
    suspend fun useActivity() = useActivitySuggestion()

    private val locationFlow: Flow<CurrentLocationDescription?> =
        clientLocationProvider.currentLocation
            .distinctUntilChanged { a, b -> a.distanceTo(b) < 100.0 }
            .mapLatest { location ->
                try {
                    when (val result = placeResolutionCache.resolve(location)) {
                        is PlaceResolutionResult.UserDefinedPlace -> CurrentLocationDescription.NamedPlace(result.place.name)
                        is PlaceResolutionResult.ExternalSuggestion -> CurrentLocationDescription.NamedPlace(result.suggestion.name)
                        is PlaceResolutionResult.CoarseLocation -> describeCoarseLocation(result.address)
                        is PlaceResolutionResult.UnknownLocation -> null
                    }
                } catch (e: Exception) {
                    Napier.w("Failed to resolve location for suggestion", e)
                    null
                }
            }.onStart { emit(null) }

    private fun describeCoarseLocation(address: GeocodedAddress): CurrentLocationDescription? {
        val thoroughfare = address.thoroughfare
        val subLocality = address.subLocality
        val locality = address.locality
        val adminArea = address.adminArea
        return when {
            thoroughfare != null && locality != null -> CurrentLocationDescription.Street(thoroughfare, locality)
            subLocality != null && locality != null -> CurrentLocationDescription.Neighborhood(subLocality, locality)
            locality != null -> CurrentLocationDescription.Area(locality)
            adminArea != null -> CurrentLocationDescription.Area(adminArea)
            else -> null
        }
    }

    operator fun invoke(): Flow<HomeRecommendation> =
        memoriesSettingsRepository.observeSettings().flatMapLatest { settings ->
            if (!settings.contextualRecommendationsEnabled) return@flatMapLatest flowOf(HomeRecommendation.None)
            combine(contextual(settings), activity.onStart { emit(ActivityUiState()) }) { recommendation, doing ->
                doing.toRecommendation() ?: recommendation
            }
        }

    private fun ActivityUiState.toRecommendation(): HomeRecommendation? {
        suggestion?.let { return HomeRecommendation.AfterActivity(it.minutes, trip = it.activity.kind == ActivityKind.IN_VEHICLE) }
        if (morningStart != null) return HomeRecommendation.MorningStart
        return null
    }

    private fun contextual(settings: MemoriesSettings): Flow<HomeRecommendation> =
        combine(
            hasNotesForToday(),
            fetchMostRecentDraft(),
            getMemoryRecall(aiEnabled = settings.aiRecallEnabled),
            observeUpcomingEvents(),
            locationFlow,
        ) { hasNotes, recentDraft, recall, upcomingEvents, location ->
            val nextUncapturedEvent =
                if (settings.eventNudgesEnabled) firstUncapturedEvent(upcomingEvents) else null
            when {
                recentDraft != null -> {
                    HomeRecommendation.CompleteYourDraft(
                        draftId = recentDraft.id,
                    )
                }

                nextUncapturedEvent != null -> {
                    HomeRecommendation.UpcomingEvent(
                        eventId = nextUncapturedEvent.id,
                        title = nextUncapturedEvent.title,
                        startTime = nextUncapturedEvent.startTime,
                        placeName = null,
                    )
                }

                recall != null -> {
                    HomeRecommendation.MemoryRecall(
                        date = recall.date,
                        summary = recall.summary,
                        people = recall.people,
                        mediaUris = recall.mediaUris,
                    )
                }

                !hasNotes -> {
                    HomeRecommendation.EmptyDay(
                        location = location,
                    )
                }

                else -> {
                    HomeRecommendation.None
                }
            }
        }

    /**
     * Returns the soonest upcoming event that has no captures attached yet, or `null` when
     * none qualify. The capture check happens here on the small candidate list rather than
     * inside [ObserveUpcomingEventsUseCase] to avoid an N+1 across the full event stream.
     */
    private suspend fun firstUncapturedEvent(upcoming: List<Event>): Event? {
        for (event in upcoming) {
            val hasCaptures = eventRepository.observeNotesForEvent(event.id).first().isNotEmpty()
            if (!hasCaptures) return event
        }
        return null
    }
}
