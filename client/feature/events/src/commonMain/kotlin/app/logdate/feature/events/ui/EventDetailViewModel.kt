package app.logdate.feature.events.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.events.DeleteEventUseCase
import app.logdate.client.domain.events.GetAttachableNotesForEventUseCase
import app.logdate.client.domain.events.GetEventByIdUseCase
import app.logdate.client.domain.events.LinkNoteToEventUseCase
import app.logdate.client.domain.events.ObserveLinkedNotesForEventUseCase
import app.logdate.client.domain.events.ObserveUserPlacesUseCase
import app.logdate.client.domain.events.UnlinkNoteFromEventUseCase
import app.logdate.client.domain.events.UpdateEventUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Event
import app.logdate.shared.model.Place
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logdate.client.feature.events.generated.resources.Res
import logdate.client.feature.events.generated.resources.event_detail_error_attach_capture
import logdate.client.feature.events.generated.resources.event_detail_error_delete
import logdate.client.feature.events.generated.resources.event_detail_error_detach_capture
import logdate.client.feature.events.generated.resources.event_detail_error_save
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * UI state surface for the event detail / edit screen.
 *
 * The screen always sits in exactly one of three states. The transition diagram is:
 *
 * ```
 * Loading ──► Loaded   (event resolved from the repository)
 * Loading ──► NotFound (no event with the requested id, or it was soft-deleted)
 * Loaded  ──► NotFound (the event is deleted while the screen is open)
 * ```
 *
 * The [Loaded] state holds the *draft* event being edited, which differs from the persisted
 * event until [EventDetailViewModel.save] is called. Picker visibility flags are part of the
 * state so configuration changes (rotation, theme switch, screen unlock) don't dismiss them.
 */
sealed interface EventDetailUiState {
    /**
     * Initial state, shown while the event is being fetched for the first time. The screen
     * displays a centered progress indicator.
     */
    data object Loading : EventDetailUiState

    /**
     * Terminal state shown when the requested event id does not exist (or has been soft-deleted).
     * The screen displays a friendly "Event not found" message instead of the editor.
     */
    data object NotFound : EventDetailUiState

    /**
     * The event has loaded successfully and the user can view or edit it.
     *
     * @property event The current draft of the event. Differs from the persisted event when
     *   the user has typed edits that haven't been saved yet.
     * @property linkedNotes The journal notes currently attached to this event, refreshed
     *   whenever the junction table changes upstream.
     * @property availablePlaces The user's saved [Place.UserDefined] entries that the place
     *   picker offers. Empty when the user has not created any places yet.
     * @property attachableNotes Candidate notes the attach sheet shows when the user opens it.
     *   Loaded lazily on `openAttachSheet`, scoped to the current draft's start time.
     * @property isPlacePickerOpen Whether the place picker sheet/dialog is currently visible.
     * @property isAttachSheetOpen Whether the attach-notes sheet/dialog is currently visible.
     * @property isSaving `true` while a save round-trip to the repository is in flight. The
     *   Save button is disabled and shows "Saving…" during this period to prevent double-submit.
     * @property errorMessage Non-null if the most recent save, delete, attach, or detach failed.
     *   Cleared on the next successful save.
     */
    data class Loaded(
        val event: Event,
        val linkedNotes: List<JournalNote> = emptyList(),
        val availablePlaces: List<Place.UserDefined> = emptyList(),
        val attachableNotes: List<JournalNote> = emptyList(),
        val isPlacePickerOpen: Boolean = false,
        val isAttachSheetOpen: Boolean = false,
        val isSaving: Boolean = false,
        val errorMessage: StringResource? = null,
    ) : EventDetailUiState {
        /**
         * Resolved [Place.UserDefined] for the current draft, or `null` when the event has no
         * place set. Derived from [event] and [availablePlaces] so the screen doesn't have to
         * thread the lookup itself.
         */
        val resolvedPlace: Place.UserDefined?
            get() {
                val placeId = event.placeId ?: return null
                return availablePlaces.firstOrNull { it.id == placeId }
            }
    }
}

/**
 * ViewModel powering the event detail / edit screen.
 *
 * Responsibilities:
 *
 * 1. Resolve an event by id and expose it as [uiState] for the screen to render.
 * 2. Observe the linked notes and the user's saved places, combining everything into one
 *    [EventDetailUiState.Loaded] without torn intermediate states.
 * 3. Hold the in-progress edit draft separately from the persisted event so the user can type,
 *    pick a new time, swap places, etc., and only commit on Save.
 * 4. Lazily fetch candidate notes for the attach sheet when the user opens it, scoped to the
 *    current draft's time window.
 * 5. Mediate Save and Delete through domain use cases, surfacing success/failure as state.
 *
 * Implements [EventDetailActions] directly so the screen can pass the ViewModel instance as
 * its actions slot — this is the Android architecture guide's recommended state+actions
 * pattern and keeps lambda identity stable across recompositions without manual `remember`.
 *
 * Per project rules: no repository calls; everything goes through use cases.
 *
 * **Save semantics:**
 * - Title, description, time bounds, place, and cover image are *staged* in the loaded state
 *   and only persisted when [save] is called. Discarding the screen drops them.
 * - **Linking and unlinking notes is persisted immediately**, on the theory that the user
 *   expects an "Attach" tap to stick the moment they tap it (otherwise the attach sheet would
 *   need its own commit/cancel, which is a worse UX). The `linkedNotes` flow updates from
 *   upstream so the change is visible right after the use case returns.
 *
 * @param getEventById Reactive lookup that emits the latest copy of an event by id, or `null`
 *   when the event does not exist or has been soft-deleted.
 * @param observeLinkedNotesForEvent Reactive lookup that resolves the linked-note junction
 *   into full [JournalNote] objects for the event detail's "Attached" section.
 * @param getAttachableNotesForEvent One-shot fetch of candidate notes near the event in time,
 *   triggered when the user opens the attach sheet.
 * @param observeUserPlaces Reactive lookup of the user's saved places for the place picker.
 * @param updateEvent Persists edits to an event's mutable metadata.
 * @param deleteEvent Soft-deletes the event so it disappears from the timeline and detail view.
 * @param linkNoteToEvent Persists a new note attachment immediately.
 * @param unlinkNoteFromEvent Persists a note detachment immediately.
 */
class EventDetailViewModel(
    private val getEventById: GetEventByIdUseCase,
    private val observeLinkedNotesForEvent: ObserveLinkedNotesForEventUseCase,
    private val getAttachableNotesForEvent: GetAttachableNotesForEventUseCase,
    private val observeUserPlaces: ObserveUserPlacesUseCase,
    private val updateEvent: UpdateEventUseCase,
    private val deleteEvent: DeleteEventUseCase,
    private val linkNoteToEvent: LinkNoteToEventUseCase,
    private val unlinkNoteFromEvent: UnlinkNoteFromEventUseCase,
) : ViewModel(),
    EventDetailActions {
    private val _uiState = MutableStateFlow<EventDetailUiState>(EventDetailUiState.Loading)

    /**
     * Observable UI state. The screen collects this with `collectAsStateWithLifecycle()` and
     * branches on the sealed [EventDetailUiState] hierarchy to choose what to render.
     */
    val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

    /**
     * Begins observing the event with the given id along with its linked notes and the user's
     * saved places. The three reactive sources are combined at the top level so we don't
     * re-subscribe to the inner queries every time the upstream event re-emits.
     *
     * Once the user starts editing, upstream re-emits only refresh `linkedNotes` and
     * `availablePlaces` — the user's draft event itself is preserved so typing isn't clobbered.
     *
     * `attachableNotes` is NOT observed here. It's a one-shot fetch performed when the user
     * opens the attach sheet, scoped to the *current draft's* start time.
     */
    override fun loadEvent(eventId: Uuid) {
        viewModelScope.launch {
            combine(
                getEventById(eventId),
                observeLinkedNotesForEvent(eventId),
                observeUserPlaces(),
            ) { event, linked, places ->
                Triple(event, linked, places)
            }.collect { (event, linked, places) ->
                _uiState.update { current ->
                    when {
                        event == null -> {
                            EventDetailUiState.NotFound
                        }

                        current is EventDetailUiState.Loaded -> {
                            current.copy(
                                linkedNotes = linked,
                                availablePlaces = places,
                            )
                        }

                        else -> {
                            EventDetailUiState.Loaded(
                                event = event,
                                linkedNotes = linked,
                                availablePlaces = places,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Stages a new title in the in-memory draft. No-op when the screen is not [Loaded]. */
    override fun updateTitle(title: String) {
        updateLoaded { it.copy(event = it.event.copy(title = title)) }
    }

    /**
     * Stages a new description in the in-memory draft. Blank input is normalized to `null`
     * so the persisted column matches the schema convention of "no description = null".
     * No-op when the screen is not [Loaded].
     */
    override fun updateDescription(description: String) {
        updateLoaded { it.copy(event = it.event.copy(description = description.ifBlank { null })) }
    }

    /**
     * Stages a new start time. If the existing end time would now be earlier than the new
     * start, the end is dropped so the editor doesn't end up in an inverted-range state — the
     * next end-time pick re-establishes a range. No-op when the screen is not [Loaded].
     */
    override fun updateStartTime(startTime: Instant) {
        updateLoaded { state ->
            val keptEnd = state.event.endTime?.takeIf { it >= startTime }
            state.copy(event = state.event.copy(startTime = startTime, endTime = keptEnd))
        }
    }

    /** Stages a new end time, or clears it (point-in-time) when null is passed. */
    override fun updateEndTime(endTime: Instant?) {
        updateLoaded { it.copy(event = it.event.copy(endTime = endTime)) }
    }

    /**
     * Toggles the event between point-in-time (no end) and ranged. When converting to ranged,
     * the new end time defaults to one hour after the start; when converting to point-in-time,
     * the end is cleared.
     */
    override fun togglePointInTime(pointInTime: Boolean) {
        updateLoaded { state ->
            val newEnd = if (pointInTime) null else state.event.endTime ?: (state.event.startTime + 1.hours)
            state.copy(event = state.event.copy(endTime = newEnd))
        }
    }

    /** Sets or clears the linked place id in the draft. */
    override fun updatePlace(placeId: Uuid?) {
        updateLoaded { it.copy(event = it.event.copy(placeId = placeId)) }
    }

    /** Sets or clears the cover image URI in the draft. */
    override fun updateCoverImage(uri: String?) {
        updateLoaded { it.copy(event = it.event.copy(coverImageUri = uri)) }
    }

    /** Marks the place picker visible. No-op when the screen is not [Loaded]. */
    override fun openPlacePicker() = updateLoaded { it.copy(isPlacePickerOpen = true) }

    /** Marks the place picker dismissed. No-op when the screen is not [Loaded]. */
    override fun dismissPlacePicker() = updateLoaded { it.copy(isPlacePickerOpen = false) }

    /**
     * Opens the attach-notes sheet and kicks off a one-shot fetch of candidate notes scoped
     * to the *current draft's* start time. The sheet becomes visible immediately so the user
     * sees the surface; the candidate list arrives a tick later when the fetch completes.
     */
    override fun openAttachSheet() {
        val current = _uiState.value as? EventDetailUiState.Loaded ?: return
        _uiState.value = current.copy(isAttachSheetOpen = true)
        viewModelScope.launch {
            val notes =
                runCatching { getAttachableNotesForEvent(current.event.id, current.event.startTime) }
                    .onFailure { error ->
                        Napier.e("Failed to load attachable notes for ${current.event.id}", error)
                    }.getOrNull()
                    .orEmpty()
            updateLoaded { it.copy(attachableNotes = notes) }
        }
    }

    /**
     * Closes the attach sheet and clears the candidate list so the next open re-fetches
     * against the latest draft.
     */
    override fun dismissAttachSheet() {
        updateLoaded { it.copy(isAttachSheetOpen = false, attachableNotes = emptyList()) }
    }

    /**
     * Persists a new note attachment immediately (see "Save semantics" on the class doc for
     * the rationale). On failure surfaces an error message; on success the linked-notes flow
     * picks up the change and re-renders the section.
     */
    override fun linkNote(noteId: Uuid) {
        val current = _uiState.value as? EventDetailUiState.Loaded ?: return
        viewModelScope.launch {
            linkNoteToEvent(current.event.id, noteId).onFailure { error ->
                Napier.e("Failed to link note $noteId to event ${current.event.id}", error)
                updateLoaded { it.copy(errorMessage = Res.string.event_detail_error_attach_capture) }
            }
        }
    }

    /**
     * Persists a note detachment immediately. On failure surfaces an error message; on
     * success the linked-notes flow picks up the change and re-renders the section.
     */
    override fun unlinkNote(noteId: Uuid) {
        val current = _uiState.value as? EventDetailUiState.Loaded ?: return
        viewModelScope.launch {
            unlinkNoteFromEvent(current.event.id, noteId).onFailure { error ->
                Napier.e("Failed to unlink note $noteId from event ${current.event.id}", error)
                updateLoaded { it.copy(errorMessage = Res.string.event_detail_error_detach_capture) }
            }
        }
    }

    /**
     * Persists the current draft via [updateEvent].
     *
     * Marks the state as saving for the duration of the round-trip so the screen can disable
     * the Save button and show a "Saving…" label. On failure, populates [errorMessage] with a
     * generic message and logs the underlying error via Napier — the user-visible message
     * intentionally does not leak exception details.
     */
    override fun save() {
        val current = _uiState.value as? EventDetailUiState.Loaded ?: return
        _uiState.value = current.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            updateEvent(current.event)
                .onFailure { error ->
                    Napier.e("Failed to save event ${current.event.id}", error)
                    updateLoaded { it.copy(isSaving = false, errorMessage = Res.string.event_detail_error_save) }
                }.onSuccess {
                    updateLoaded { it.copy(isSaving = false) }
                }
        }
    }

    /**
     * Soft-deletes the current event, then invokes [onDeleted] so the caller can dismiss the
     * screen. On failure [onDeleted] is not invoked and an error message is surfaced through
     * [EventDetailUiState.Loaded.errorMessage].
     *
     * @param onDeleted Callback invoked exactly once on successful delete; typically pops the
     *   navigation back stack.
     */
    override fun delete(onDeleted: () -> Unit) {
        val current = _uiState.value as? EventDetailUiState.Loaded ?: return
        viewModelScope.launch {
            deleteEvent(current.event.id)
                .onSuccess { onDeleted() }
                .onFailure { error ->
                    Napier.e("Failed to delete event ${current.event.id}", error)
                    updateLoaded { it.copy(errorMessage = Res.string.event_detail_error_delete) }
                }
        }
    }

    /**
     * Helper that applies [transform] only when the current state is [EventDetailUiState.Loaded],
     * leaving Loading and NotFound states untouched. Keeps every action implementation a single
     * line and avoids the same `if (current is Loaded) ...` boilerplate at every call site.
     */
    private fun updateLoaded(transform: (EventDetailUiState.Loaded) -> EventDetailUiState.Loaded) {
        _uiState.update { current ->
            if (current is EventDetailUiState.Loaded) transform(current) else current
        }
    }
}
