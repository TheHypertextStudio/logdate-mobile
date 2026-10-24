@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.events.LinkNoteToEventUseCase
import app.logdate.client.domain.recommendation.GetHomeRecommendationUseCase
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.domain.timeline.GetStreamingTimelineUseCase
import app.logdate.client.domain.timeline.GetTimelinePageUseCase
import app.logdate.client.domain.timeline.StreamingTimelineRequest
import app.logdate.client.domain.timeline.Timeline
import app.logdate.client.domain.timeline.TimelineDay
import app.logdate.client.domain.timeline.TimelinePage
import app.logdate.client.domain.timeline.TimelinePageRequest
import app.logdate.client.domain.timeline.retainAvailableTimelineSummaries
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.ui.audio.TranscriptionState
import app.logdate.ui.timeline.HomeTimelineUiState
import app.logdate.ui.timeline.TimelineDaySelection
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.TimelineLoadingState
import app.logdate.ui.timeline.TimelineSuggestionBlock
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/**
 * ViewModel for the home screen.
 *
 * Combines the streaming timeline, per-day note selection, and the home recommendation signal
 * into a single [HomeTimelineUiState] flow. The recommendation is produced by
 * [GetHomeRecommendationUseCase], which aggregates multiple data signals (today's entries,
 * unfinished drafts, etc.) and converts the result into a [TimelineSuggestionBlock] for
 * display at the top of the timeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val recentTimelineFlow: Flow<Timeline>,
    private val loadTimelinePage: suspend (TimelinePageRequest) -> TimelinePage,
    private val notesRepository: JournalNotesRepository,
    private val getHomeRecommendation: GetHomeRecommendationUseCase,
    private val linkNoteToEvent: LinkNoteToEventUseCase,
    private val getJournalMembership: GetJournalMembershipUseCase,
    private val transcriptionRepository: TranscriptionRepository,
    private val libraryEnabledFlow: Flow<Boolean>,
) : ViewModel() {
    companion object {
        private const val RECENT_TIMELINE_PAGE_SIZE = 50
        private const val APPEND_ERROR_MESSAGE = "Couldn't load older entries."
    }

    constructor(
        getStreamingTimelineUseCase: GetStreamingTimelineUseCase,
        getTimelinePageUseCase: GetTimelinePageUseCase,
        notesRepository: JournalNotesRepository,
        getHomeRecommendation: GetHomeRecommendationUseCase,
        linkNoteToEvent: LinkNoteToEventUseCase,
        getJournalMembership: GetJournalMembershipUseCase,
        transcriptionRepository: TranscriptionRepository,
        preferencesDataSource: LogdatePreferencesDataSource,
    ) : this(
        recentTimelineFlow =
            getStreamingTimelineUseCase(
                StreamingTimelineRequest.RecentTimeline(
                    pageSize = RECENT_TIMELINE_PAGE_SIZE,
                ),
            ).flowOn(Dispatchers.Default),
        loadTimelinePage = { request -> getTimelinePageUseCase(request) },
        notesRepository = notesRepository,
        getHomeRecommendation = getHomeRecommendation,
        linkNoteToEvent = linkNoteToEvent,
        getJournalMembership = getJournalMembership,
        transcriptionRepository = transcriptionRepository,
        libraryEnabledFlow = preferencesDataSource.observeLibraryEnabled(),
    )

    private val selectedDayFlow = MutableStateFlow<LocalDate?>(null)
    private val appendedTimelineDays = MutableStateFlow<List<TimelineDay>>(emptyList())
    private val isLoadingMore = MutableStateFlow(false)
    private val appendError = MutableStateFlow<String?>(null)
    private val hasLoadedRecentTimeline = MutableStateFlow(false)
    private val visibleAudioNoteIds = MutableStateFlow<Set<Uuid>>(emptySet())
    private val transcriptionCache = MutableStateFlow<Map<Uuid, TranscriptionData?>>(emptyMap())
    private val autoRequestedNoteIds = mutableSetOf<Uuid>()

    val transcriptionState: StateFlow<TranscriptionState> =
        transcriptionCache
            .map { cache ->
                TranscriptionState(
                    requestTranscription = ::requestTranscription,
                    getTranscriptionText = { noteId ->
                        cache[noteId]
                            ?.text
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                    },
                    isTranscriptionInProgress = { noteId ->
                        cache[noteId]?.status in setOf(TranscriptionStatus.PENDING, TranscriptionStatus.IN_PROGRESS)
                    },
                    getTranscriptionError = { noteId ->
                        cache[noteId]
                            ?.takeIf { it.status == TranscriptionStatus.FAILED }
                            ?.errorMessage
                    },
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                TranscriptionState(requestTranscription = ::requestTranscription),
            )

    /**
     * Whether the Library tab should appear in the home navigation. Backed by the `library_enabled`
     * user setting so toggling it in Settings reactively shows or hides the tab.
     */
    val isLibraryEnabled: StateFlow<Boolean> =
        libraryEnabledFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            false,
        )

    init {
        viewModelScope.launch {
            visibleAudioNoteIds
                .flatMapLatest(::observeVisibleTranscriptions)
                .onEach { transcriptions ->
                    transcriptionCache.value = transcriptions
                    requestMissingVisibleTranscriptions(transcriptions)
                }.collect {}
        }
    }

    private val recentTimelineState: StateFlow<Timeline> =
        recentTimelineFlow
            .map(::retainRecentSnapshot)
            .onEach { hasLoadedRecentTimeline.value = true }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                Timeline(emptyList()),
            )

    private val timelineFeedDays: StateFlow<List<TimelineDay>> =
        combine(recentTimelineState, appendedTimelineDays) { recentTimeline, appendedDays ->
            mergeTimelineDays(existing = appendedDays, incoming = recentTimeline.days)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    private val selectedItemUiState: StateFlow<TimelineDaySelection> =
        selectedDayFlow
            .map { selectedDay ->
                selectedDay?.let(TimelineDaySelection::DateSelected) ?: TimelineDaySelection.NotSelected
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                TimelineDaySelection.NotSelected,
            )

    private val selectedDayNotes: StateFlow<SelectedDayNotes?> =
        selectedDayFlow
            .flatMapLatest { selectedDay ->
                if (selectedDay == null) {
                    flowOf(null)
                } else {
                    notesRepository.observeNotesForDay(selectedDay).map { notes -> SelectedDayNotes(selectedDay, notes) }
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                null,
            )

    private val journalMembership: StateFlow<Map<Uuid, List<app.logdate.shared.model.Journal>>> by lazy {
        combine(timelineFeedDays, selectedDayNotes) { days, selected ->
            (days.flatMap { it.entries } + selected?.notes.orEmpty()).map(JournalNote::uid).toSet()
        }.distinctUntilChanged()
            .flatMapLatest(getJournalMembership::invoke)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    private val timelineItems: StateFlow<List<TimelineDayUiState>> =
        combine(timelineFeedDays, journalMembership) { timelineDays, membershipMap ->
            timelineDays.map { timelineDay -> timelineDay.toHomeTimelineUiState(membershipMap = membershipMap) }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    private val selectedDayUiState: StateFlow<TimelineDayUiState?> =
        combine(
            selectedDayFlow,
            selectedDayNotes,
            timelineFeedDays,
            journalMembership,
        ) { selectedDayDate, selectedNotes, timelineDays, membershipMap ->
            val day = selectedDayDate ?: return@combine null
            val timelineDay = timelineDays.find { timelineEntry -> timelineEntry.date == day } ?: return@combine null
            val notes = selectedNotes?.takeIf { it.date == day }?.notes ?: timelineDay.entries
            timelineDay.toHomeTimelineUiState(
                overrideNotes = notes,
                membershipMap = membershipMap,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null,
        )

    private val hasMoreOlderContent: StateFlow<Boolean> =
        timelineFeedDays
            .mapLatest { timelineDays ->
                val oldestLoadedTimestamp = timelineDays.oldestLoadedTimestamp() ?: return@mapLatest false
                notesRepository.hasNotesBefore(oldestLoadedTimestamp)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false,
            )

    /** Puts away the suggestion after an activity, or the morning start. */
    fun dismissActivitySuggestion() {
        viewModelScope.launch { getHomeRecommendation.dismissActivity() }
    }

    /** Notes that a moment was started from the suggestion after an activity, or the morning start. */
    fun useActivitySuggestion() {
        viewModelScope.launch { getHomeRecommendation.useActivity() }
    }

    private val timelineSuggestionState: StateFlow<TimelineSuggestionBlock?> =
        getHomeRecommendation()
            .map { recommendation -> recommendation.toTimelineSuggestionBlock() }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                null,
            )

    val uiState: StateFlow<HomeTimelineUiState> =
        combine(
            combine(
                timelineItems,
                selectedItemUiState,
                selectedDayUiState,
                timelineSuggestionState,
            ) { items, selection, selectedDay, suggestion ->
                HomeTimelineVisualState(
                    items = items,
                    selection = selection,
                    selectedDay = selectedDay,
                    suggestion = suggestion,
                )
            },
            combine(
                hasLoadedRecentTimeline,
                isLoadingMore,
                hasMoreOlderContent,
                appendError,
            ) { hasLoadedRecent, isLoadingMoreOlder, hasMoreOlder, appendOlderError ->
                HomeTimelineLoadingState(
                    hasLoadedRecentTimeline = hasLoadedRecent,
                    isLoadingMore = isLoadingMoreOlder,
                    hasMoreOlderContent = hasMoreOlder,
                    appendError = appendOlderError,
                )
            },
        ) { visualState, loadingState ->
            val showInitialLoading = !loadingState.hasLoadedRecentTimeline && visualState.items.isEmpty()
            HomeTimelineUiState(
                items = visualState.items,
                selectedItem = visualState.selection,
                selectedDay =
                    visualState.selectedDay?.takeIf { selected ->
                        when (val selection = visualState.selection) {
                            is TimelineDaySelection.DateSelected -> selected.date == selection.date
                            is TimelineDaySelection.Selected -> selected.date == selection.day
                            TimelineDaySelection.NotSelected -> false
                        }
                    },
                showEmptyState = loadingState.hasLoadedRecentTimeline && visualState.items.isEmpty(),
                timelineSuggestion = visualState.suggestion,
                isLoading = showInitialLoading,
                isLoadingMore = loadingState.isLoadingMore,
                hasMoreOlderContent = loadingState.hasMoreOlderContent,
                appendError = loadingState.appendError,
                loadingState = if (showInitialLoading) TimelineLoadingState.InitialLoading else TimelineLoadingState.Loaded,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeTimelineUiState(
                isLoading = true,
                loadingState = TimelineLoadingState.InitialLoading,
            ),
        )

    private fun retainRecentSnapshot(incoming: Timeline): Timeline = retainAvailableTimelineSummaries(recentTimelineState.value, incoming)

    /**
     * Selects a timeline day for detailed viewing.
     *
     * @param date The date of the day to select
     */
    fun selectDay(date: LocalDate) {
        selectedDayFlow.value = date
    }

    /**
     * Clears the current day selection.
     */
    fun clearSelection() {
        selectedDayFlow.value = null
    }

    /**
     * Attaches a note to an event from the timeline drag-and-drop gesture. The drop
     * payload is plain text, so [noteIdString] and [eventIdString] are the parsed UUID
     * strings — anything that doesn't parse is silently ignored (the drop came from a
     * non-LogDate source). Errors are logged but not surfaced to the user; a failed link
     * is recoverable on the next attempt and the gesture is best-effort.
     */
    fun attachNoteToEvent(
        noteIdString: String,
        eventIdString: String,
    ) {
        val noteId = runCatching { Uuid.parse(noteIdString) }.getOrNull() ?: return
        val eventId = runCatching { Uuid.parse(eventIdString) }.getOrNull() ?: return
        viewModelScope.launch {
            val result = linkNoteToEvent(eventId = eventId, noteId = noteId)
            if (result.isFailure) {
                Napier.w(
                    "Failed to attach note $noteId to event $eventId via drag",
                    result.exceptionOrNull(),
                )
            }
        }
    }

    fun updateVisibleAudioNoteIds(noteIds: Set<Uuid>) {
        visibleAudioNoteIds.value = noteIds
    }

    fun loadMoreOlder() {
        if (isLoadingMore.value) {
            return
        }

        val oldestLoadedTimestamp = timelineFeedDays.value.oldestLoadedTimestamp() ?: return
        if (!hasMoreOlderContent.value) {
            return
        }

        viewModelScope.launch {
            isLoadingMore.value = true
            appendError.value = null

            try {
                val olderPage =
                    loadTimelinePage(
                        TimelinePageRequest(
                            beforeExclusive = oldestLoadedTimestamp,
                            pageSize = RECENT_TIMELINE_PAGE_SIZE,
                        ),
                    )

                if (olderPage.days.isNotEmpty()) {
                    appendedTimelineDays.update { existing ->
                        mergeTimelineDays(existing = existing, incoming = olderPage.days)
                    }
                }
            } catch (error: Exception) {
                Napier.e("Failed to load older timeline history", error)
                appendError.value = APPEND_ERROR_MESSAGE
            } finally {
                isLoadingMore.value = false
            }
        }
    }

    private fun observeVisibleTranscriptions(noteIds: Set<Uuid>): Flow<Map<Uuid, TranscriptionData?>> {
        val sortedIds = noteIds.sorted()
        if (sortedIds.isEmpty()) {
            return flowOf(emptyMap())
        }

        return combine(
            sortedIds.map { noteId ->
                transcriptionRepository.observeTranscription(noteId).map { transcription ->
                    noteId to transcription
                }
            },
        ) { notePairs ->
            notePairs.toMap()
        }
    }

    private fun requestTranscription(noteId: Uuid) {
        viewModelScope.launch {
            transcriptionRepository.requestTranscription(noteId)
        }
    }

    private fun requestMissingVisibleTranscriptions(transcriptions: Map<Uuid, TranscriptionData?>) {
        val noteIdsToRequest =
            autoRequestableTimelineTranscriptionIds(
                visibleNoteIds = visibleAudioNoteIds.value,
                transcriptions = transcriptions,
                alreadyRequestedNoteIds = autoRequestedNoteIds,
            )

        noteIdsToRequest.forEach { noteId ->
            autoRequestedNoteIds.add(noteId)
            viewModelScope.launch {
                val queued = transcriptionRepository.requestTranscription(noteId)
                if (!queued) {
                    autoRequestedNoteIds.remove(noteId)
                }
            }
        }
    }
}

private fun autoRequestableTimelineTranscriptionIds(
    visibleNoteIds: Set<Uuid>,
    transcriptions: Map<Uuid, TranscriptionData?>,
    alreadyRequestedNoteIds: Set<Uuid>,
): Set<Uuid> =
    visibleNoteIds
        .filterNot { noteId -> noteId in alreadyRequestedNoteIds }
        .filter { noteId ->
            when (transcriptions[noteId]?.status) {
                null,
                TranscriptionStatus.FAILED,
                -> true

                TranscriptionStatus.COMPLETED,
                TranscriptionStatus.IN_PROGRESS,
                TranscriptionStatus.PENDING,
                -> false
            }
        }.toSet()

private fun mergeTimelineDays(
    existing: List<TimelineDay>,
    incoming: List<TimelineDay>,
): List<TimelineDay> {
    val daysByDate = LinkedHashMap<LocalDate, TimelineDay>()

    existing.sortedByDescending(TimelineDay::date).forEach { day ->
        daysByDate[day.date] = day
    }
    incoming.sortedByDescending(TimelineDay::date).forEach { day ->
        daysByDate[day.date] = day
    }

    return daysByDate.values.sortedByDescending(TimelineDay::date)
}

private fun List<TimelineDay>.oldestLoadedTimestamp() = flatMap(TimelineDay::entries).minOfOrNull { note -> note.creationTimestamp }

private data class HomeTimelineVisualState(
    val items: List<TimelineDayUiState>,
    val selection: TimelineDaySelection,
    val selectedDay: TimelineDayUiState?,
    val suggestion: TimelineSuggestionBlock?,
)

private data class SelectedDayNotes(
    val date: LocalDate,
    val notes: List<JournalNote>,
)

private data class HomeTimelineLoadingState(
    val hasLoadedRecentTimeline: Boolean,
    val isLoadingMore: Boolean,
    val hasMoreOlderContent: Boolean,
    val appendError: String?,
)
