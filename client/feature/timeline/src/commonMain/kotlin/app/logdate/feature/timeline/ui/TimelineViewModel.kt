@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.timeline.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.awareness.daylight.DaylightClassifier
import app.logdate.client.domain.notes.RemoveNoteUseCase
import app.logdate.client.domain.recommendation.GetHomeRecommendationUseCase
import app.logdate.client.domain.recommendation.HomeRecommendation
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.domain.timeline.GetStreamingTimelineUseCase
import app.logdate.client.domain.timeline.Moment
import app.logdate.client.domain.timeline.StreamingTimelineRequest
import app.logdate.client.domain.timeline.TimelineDay
import app.logdate.client.domain.timeline.TimelinePlaceVisit
import app.logdate.client.domain.timeline.capturedDuring
import app.logdate.client.domain.timeline.capturedOnWatch
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.isCapturedOnWatch
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Person
import app.logdate.ui.audio.TranscriptionState
import app.logdate.ui.location.PlaceUiState
import app.logdate.ui.profiles.PersonUiState
import app.logdate.ui.profiles.toUiState
import app.logdate.ui.timeline.AudioNoteUiState
import app.logdate.ui.timeline.HomeTimelineUiState
import app.logdate.ui.timeline.ImageNoteUiState
import app.logdate.ui.timeline.JournalBadgeUiState
import app.logdate.ui.timeline.MomentAudioUiState
import app.logdate.ui.timeline.MomentMediaUiState
import app.logdate.ui.timeline.MomentUiState
import app.logdate.ui.timeline.TextNoteUiState
import app.logdate.ui.timeline.TimelineDaySelection
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.TimelineLoadingState
import app.logdate.ui.timeline.TimelineSuggestionBlock
import app.logdate.ui.timeline.VideoNoteUiState
import app.logdate.ui.timeline.createSemanticTimelineDayUiState
import app.logdate.ui.timeline.toDayEventUiState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/**
 * A view model for the timeline overview screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(
    getStreamingTimeline: GetStreamingTimelineUseCase,
    private val getHomeRecommendation: GetHomeRecommendationUseCase,
    private val removeNoteUseCase: RemoveNoteUseCase,
    private val userStateRepository: UserStateRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val getJournalMembership: GetJournalMembershipUseCase,
) : ViewModel() {
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
                SharingStarted.WhileSubscribed(5000),
                TranscriptionState(requestTranscription = ::requestTranscription),
            )

    private val selectedItemUiState =
        MutableStateFlow<TimelineDaySelection>(TimelineDaySelection.NotSelected)

    /**
     * Sets the selected day for timeline detail view.
     *
     * @param date The date to select.
     */
    fun setSelectedDay(date: LocalDate) {
        selectedItemUiState.value = TimelineDaySelection.DateSelected(date)
    }

    fun clearSelectedDay() {
        selectedItemUiState.value = TimelineDaySelection.NotSelected
    }

    private val snackbarMessageState = MutableStateFlow<String?>(null)

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

    // Collect the user data and map to the birthday value
    val birthday =
        userStateRepository.userData
            .map { it.birthday }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                null,
            )

    // Use streaming timeline for incremental loading with immediate first paint
    val uiState: StateFlow<HomeTimelineUiState> =
        getStreamingTimeline(
            StreamingTimelineRequest.RecentTimeline(),
        ).flatMapLatest { timeline ->
            val allNoteIds =
                timeline.days
                    .flatMap { day -> day.entries.map { it.uid } }
                    .toSet()
            getJournalMembership(allNoteIds).map { membershipMap ->
                timeline to membershipMap
            }
        }.combine(selectedItemUiState) { (timeline, membershipMap), selection ->
            val items = timeline.days.map { day -> day.toUiState(membershipMap) }
            val loadingState = if (items.isEmpty()) TimelineLoadingState.InitialLoading else TimelineLoadingState.Loaded

            HomeTimelineUiState(
                items = items,
                selectedItem = selection,
                selectedDay =
                    when (selection) {
                        is TimelineDaySelection.DateSelected -> items.find { item -> item.date == selection.date }
                        is TimelineDaySelection.Selected -> items.find { item -> item.date == selection.day }
                        TimelineDaySelection.NotSelected -> null
                    },
                loadingState = loadingState,
                isLoading = items.isEmpty(),
                timelineSuggestion = null,
                snackbarMessage = snackbarMessageState.value,
            )
        }.withOptionalTimelineSuggestion(getHomeRecommendation().map { it.toTimelineSuggestionBlock() })
            .combine(snackbarMessageState) { state, snackbarMessage ->
                state.copy(snackbarMessage = snackbarMessage)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                // Immediate first paint with skeleton
                HomeTimelineUiState(
                    loadingState = TimelineLoadingState.InitialLoading,
                    isLoading = true,
                ),
            )

    /**
     * Removes a note from the timeline.
     */
    fun deleteItem(uid: Uuid) {
        viewModelScope.launch {
            removeNoteUseCase(uid)
        }
    }

    /**
     * Dismisses any currently showing snackbar
     */
    fun dismissSnackbar() {
        snackbarMessageState.value = null
    }

    fun updateVisibleAudioNoteIds(noteIds: Set<Uuid>) {
        visibleAudioNoteIds.value = noteIds
    }

    private fun TimelinePlaceVisit.toUiState(): PlaceUiState =
        PlaceUiState(
            id = id,
            title = name,
            latitude = latitude,
            longitude = longitude,
        )

    private fun TimelineDay.toUiState(membershipMap: Map<Uuid, List<Journal>> = emptyMap()): TimelineDayUiState {
        val noteUiStates = entries.toUiState(membershipMap)
        val placeUiStates = placesVisited.map { place -> place.toUiState() }
        val peopleUiStates = people.map(Person::toUiState)
        val momentUiStates = moments.toMomentUiStates(peopleUiStates, membershipMap)
        val eventUiStates = events.map { it.toDayEventUiState() }

        return createSemanticTimelineDayUiState(
            summary = tldr,
            summaryNotice = summaryUnavailableReason?.notice,
            date = date,
            moments = momentUiStates,
            people = peopleUiStates,
            notes = noteUiStates,
            placesVisited = placeUiStates,
            events = eventUiStates,
            isLoadingSummary = isSummaryPending && marks.isEmpty(),
            isLoadingPeople = people.isEmpty() && isSummaryPending && marks.isEmpty(),
            pendingMarks = marks,
        )
    }

    private fun List<Moment>.toMomentUiStates(
        dayPeople: List<PersonUiState>,
        membershipMap: Map<Uuid, List<Journal>> = emptyMap(),
    ): List<MomentUiState> {
        val heroIndex = selectHeroIndex(this)
        return mapIndexed { index, moment ->
            moment.toMomentUiState(isHero = index == heroIndex, dayPeople = dayPeople, membershipMap = membershipMap)
        }
    }

    private fun Moment.toMomentUiState(
        isHero: Boolean,
        dayPeople: List<PersonUiState>,
        membershipMap: Map<Uuid, List<Journal>> = emptyMap(),
    ): MomentUiState {
        val timeOfDay = DaylightClassifier().classifyWithoutLocation(estimatedStart)
        val resolvedPeople =
            people.mapNotNull { name ->
                dayPeople.find { it.name.equals(name, ignoreCase = true) }
            }
        return MomentUiState(
            id = id.toString(),
            capturedAt = estimatedStart,
            sourceNoteIds = sourceNotes.map { it.uid }.toSet(),
            label = label,
            labelPlaceName = labelPlace?.name,
            timeOfDay = timeOfDay,
            textSnippet = textFragments.firstOrNull()?.text,
            textNoteId = textFragments.firstOrNull()?.sourceNoteId,
            media = media.map { MomentMediaUiState(uri = it.uri, isVideo = it.isVideo, noteId = it.sourceNoteId) },
            handwriting =
                sourceNotes
                    .filterIsInstance<JournalNote.Handwriting>()
                    .toUiState()
                    .filterIsInstance<app.logdate.ui.timeline.HandwritingNoteUiState>(),
            audio =
                audio.firstOrNull()?.let {
                    MomentAudioUiState(
                        uri = it.uri,
                        durationMs = it.durationMs,
                        noteId = it.sourceNoteId,
                        recordedAt = estimatedStart,
                    )
                },
            places = places.map { PlaceUiState(id = it.id, title = it.name, latitude = it.latitude, longitude = it.longitude) },
            people = resolvedPeople,
            isHero = isHero,
            capturedOnWatch = capturedOnWatch(),
            capturedDuring = capturedDuring(),
        )
    }

    private fun selectHeroIndex(moments: List<Moment>): Int {
        if (moments.isEmpty()) return -1
        return moments.indices.maxByOrNull { index ->
            val m = moments[index]
            m.media.size * 3 + m.textFragments.size * 2 + m.audio.size
        } ?: 0
    }

    private fun HomeRecommendation.toTimelineSuggestionBlock(): TimelineSuggestionBlock? =
        when (this) {
            is HomeRecommendation.EmptyDay -> {
                TimelineSuggestionBlock.EmptyDay(
                    locationName = location?.toUiText(),
                )
            }

            is HomeRecommendation.CompleteYourDraft -> {
                TimelineSuggestionBlock.CompleteDraft(
                    draftId = draftId.toString(),
                )
            }

            is HomeRecommendation.MemoryRecall -> {
                TimelineSuggestionBlock.MemoryRecall(
                    memoryDate = date,
                    title = summary,
                    people = people,
                    mediaUris =
                        mediaUris.map { uri ->
                            app.logdate.ui.timeline
                                .MediaObjectUiState(uid = uri, uri = uri)
                        },
                    isAiGenerated = isAiGenerated,
                )
            }

            is HomeRecommendation.AfterActivity -> {
                TimelineSuggestionBlock.AfterActivity(minutes = minutes, trip = trip)
            }

            HomeRecommendation.MorningStart -> {
                TimelineSuggestionBlock.MorningStart
            }

            HomeRecommendation.None -> {
                null
            }

            // No timeline card for upcoming events yet — the events UI block is not built.
            is HomeRecommendation.UpcomingEvent -> {
                null
            }
        }

    private fun JournalNote.Handwriting.toHandwritingUiState(
        noteJournals: List<JournalBadgeUiState>,
    ): app.logdate.ui.timeline.HandwritingNoteUiState =
        app.logdate.ui.timeline.HandwritingNoteUiState(
            uid,
            document,
            creationTimestamp,
            noteJournals,
            isCapturedOnWatch,
            unreadable = unreadableContent != null,
            capturedDuring = capturedDuring,
        )

    private fun List<JournalNote>.toUiState(
        membershipMap: Map<Uuid, List<Journal>> = emptyMap(),
    ): List<app.logdate.ui.timeline.NoteUiState> =
        sortedByDescending { note -> note.creationTimestamp }.map { note ->
            val noteJournals =
                membershipMap[note.uid]
                    .orEmpty()
                    .map { JournalBadgeUiState(journalId = it.id, title = it.title) }
            when (note) {
                is JournalNote.Handwriting -> {
                    note.toHandwritingUiState(noteJournals)
                }

                is JournalNote.Text -> {
                    TextNoteUiState(
                        noteId = note.uid,
                        text = note.content,
                        timestamp = note.creationTimestamp,
                        journals = noteJournals,
                        capturedOnWatch = note.isCapturedOnWatch,
                        capturedDuring = note.capturedDuring,
                    )
                }

                is JournalNote.Image -> {
                    ImageNoteUiState(
                        noteId = note.uid,
                        uri = note.mediaRef,
                        timestamp = note.creationTimestamp,
                        caption = note.caption,
                        presentation = note.presentation,
                        journals = noteJournals,
                        capturedOnWatch = note.isCapturedOnWatch,
                        capturedDuring = note.capturedDuring,
                    )
                }

                is JournalNote.Audio -> {
                    AudioNoteUiState(
                        noteId = note.uid,
                        uri = note.mediaRef,
                        timestamp = note.creationTimestamp,
                        duration = note.durationMs,
                        journals = noteJournals,
                        capturedOnWatch = note.isCapturedOnWatch,
                        capturedDuring = note.capturedDuring,
                    )
                }

                is JournalNote.Video -> {
                    VideoNoteUiState(
                        noteId = note.uid,
                        uri = note.mediaRef,
                        timestamp = note.creationTimestamp,
                        caption = note.caption,
                        journals = noteJournals,
                        capturedOnWatch = note.isCapturedOnWatch,
                        capturedDuring = note.capturedDuring,
                    )
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
            Napier.d("Requesting transcription for note: $noteId")
            transcriptionRepository.requestTranscription(noteId)
        }
    }

    private fun requestMissingVisibleTranscriptions(transcriptions: Map<Uuid, TranscriptionData?>) {
        val noteIdsToRequest =
            autoRequestableNoteIds(
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
