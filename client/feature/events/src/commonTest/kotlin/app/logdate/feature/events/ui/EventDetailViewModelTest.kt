package app.logdate.feature.events.ui

import app.logdate.client.domain.events.DeleteEventUseCase
import app.logdate.client.domain.events.GetAttachableNotesForEventUseCase
import app.logdate.client.domain.events.GetEventByIdUseCase
import app.logdate.client.domain.events.LinkNoteToEventUseCase
import app.logdate.client.domain.events.ObserveLinkedNotesForEventUseCase
import app.logdate.client.domain.events.ObserveUserPlacesUseCase
import app.logdate.client.domain.events.UnlinkNoteFromEventUseCase
import app.logdate.client.domain.events.UpdateEventUseCase
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.model.Event
import app.logdate.shared.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for [EventDetailViewModel].
 *
 * Each test wires fresh fake repositories into the real domain use cases and into a new
 * [EventDetailViewModel], drives the ViewModel through `runTest`, and inspects the final
 * [EventDetailUiState] via direct `value` reads. The dispatcher is overridden to a
 * [StandardTestDispatcher] so `advanceUntilIdle()` deterministically flushes coroutines
 * launched in `viewModelScope`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Calling `loadEvent` for an id that exists in the repository transitions the state from
     * the initial `Loading` to `Loaded` carrying that exact event.
     */
    @Test
    fun `load event existing id transitions to loaded`() =
        runTest {
            val event = sampleEvent(title = "Recital")
            val viewModel = newViewModel(events = listOf(event))

            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is EventDetailUiState.Loaded)
            assertEquals(event, state.event)
        }

    /**
     * If the requested event does not exist, the ViewModel ends in `NotFound` so the screen
     * can render its empty-state copy instead of the editor.
     */
    @Test
    fun `load event missing id transitions to not found`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.loadEvent(Uuid.random())
            advanceUntilIdle()

            assertEquals(EventDetailUiState.NotFound, viewModel.uiState.value)
        }

    /**
     * Editing the title changes the in-memory draft only — the repository's update method is
     * not called until the user explicitly saves.
     */
    @Test
    fun `update title mutates loaded draft without persisting`() =
        runTest {
            val event = sampleEvent(title = "Old")
            val eventRepo = FakeEventRepository(events = listOf(event))
            val viewModel = newViewModel(eventRepo = eventRepo)
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.updateTitle("New")
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals("New", state.event.title)
            assertNull(eventRepo.lastUpdated, "save should not be called yet")
        }

    /**
     * Pulling the start time earlier (still before the existing end) leaves the end alone — a
     * common edit when the user realizes the event started sooner than they remembered.
     */
    @Test
    fun `update start time keeps end when still after start`() =
        runTest {
            val event = sampleEvent()
            val viewModel = newViewModel(events = listOf(event))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            val newStart = event.startTime - 1.hours
            viewModel.updateStartTime(newStart)
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals(newStart, state.event.startTime)
            assertEquals(event.endTime, state.event.endTime)
        }

    /**
     * Pulling the start time past the existing end drops the end so the editor doesn't end
     * up in an inverted-range state. The next end-time pick re-establishes a range.
     */
    @Test
    fun `update start time drops end when pulled past it`() =
        runTest {
            val event = sampleEvent()
            val viewModel = newViewModel(events = listOf(event))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            val newStart = event.endTime!! + 1.hours
            viewModel.updateStartTime(newStart)
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals(newStart, state.event.startTime)
            assertNull(state.event.endTime)
        }

    /**
     * Toggling point-in-time on clears the end time; toggling it back off restores a default
     * one-hour range.
     */
    @Test
    fun `toggle point in time clears end when on and restores when off`() =
        runTest {
            val event = sampleEvent()
            val viewModel = newViewModel(events = listOf(event))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.togglePointInTime(true)
            advanceUntilIdle()
            assertNull((viewModel.uiState.value as EventDetailUiState.Loaded).event.endTime)

            viewModel.togglePointInTime(false)
            advanceUntilIdle()
            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertNotNull(state.event.endTime)
            assertEquals(state.event.startTime + 1.hours, state.event.endTime)
        }

    /**
     * Picking a new place stages the place id on the draft event without persisting it; the
     * change goes to the repository only when the user taps Save.
     */
    @Test
    fun `update place stages place id in draft`() =
        runTest {
            val event = sampleEvent()
            val placeId = Uuid.random()
            val viewModel = newViewModel(events = listOf(event))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.updatePlace(placeId)
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals(placeId, state.event.placeId)
        }

    /**
     * Opening and dismissing the place picker flips the visibility flag in the loaded state.
     * The flag lives on state (not local Compose state) so configuration changes don't
     * dismiss the picker mid-edit.
     */
    @Test
    fun `open place picker then dismiss toggles visibility flag`() =
        runTest {
            val event = sampleEvent()
            val viewModel = newViewModel(events = listOf(event))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.openPlacePicker()
            assertTrue((viewModel.uiState.value as EventDetailUiState.Loaded).isPlacePickerOpen)

            viewModel.dismissPlacePicker()
            assertEquals(
                false,
                (viewModel.uiState.value as EventDetailUiState.Loaded).isPlacePickerOpen,
            )
        }

    /**
     * Opening the attach sheet kicks off a one-shot fetch of nearby notes scoped to the
     * current draft's start time, and the resulting list lands on the loaded state for the
     * picker to render.
     */
    @Test
    fun `open attach sheet loads attachable notes for current draft`() =
        runTest {
            val event = sampleEvent()
            val nearbyNote = textNote(creationTime = event.startTime + 1.hours, content = "Nearby")
            val viewModel =
                newViewModel(
                    events = listOf(event),
                    notes = listOf(nearbyNote),
                )
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.openAttachSheet()
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertTrue(state.isAttachSheetOpen)
            assertEquals(listOf(nearbyNote), state.attachableNotes)
        }

    /**
     * Dismissing the attach sheet clears the loaded candidate list so the next open re-fetches
     * against whatever the user's draft looks like by then.
     */
    @Test
    fun `dismiss attach sheet clears loaded candidates`() =
        runTest {
            val event = sampleEvent()
            val nearbyNote = textNote(creationTime = event.startTime + 1.hours)
            val viewModel = newViewModel(events = listOf(event), notes = listOf(nearbyNote))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()
            viewModel.openAttachSheet()
            advanceUntilIdle()

            viewModel.dismissAttachSheet()
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals(false, state.isAttachSheetOpen)
            assertEquals(emptyList(), state.attachableNotes)
        }

    /**
     * Linking a note persists immediately through the repository — it's NOT staged like the
     * other field edits. This codifies the "Save semantics" comment on the ViewModel: the
     * user expects an Attach tap to stick the moment they tap it.
     */
    @Test
    fun `link note persists immediately through repository`() =
        runTest {
            val event = sampleEvent()
            val noteId = Uuid.random()
            val eventRepo = FakeEventRepository(events = listOf(event))
            val viewModel = newViewModel(eventRepo = eventRepo)
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.linkNote(noteId)
            advanceUntilIdle()

            assertEquals(setOf(event.id to noteId), eventRepo.linkedPairs)
        }

    /**
     * Unlinking a note also persists immediately, mirroring the link behavior so the two
     * sides of the same operation behave consistently.
     */
    @Test
    fun `unlink note persists immediately through repository`() =
        runTest {
            val event = sampleEvent()
            val noteId = Uuid.random()
            val eventRepo = FakeEventRepository(events = listOf(event))
            eventRepo.linkedPairs.add(event.id to noteId)
            val viewModel = newViewModel(eventRepo = eventRepo)
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.unlinkNote(noteId)
            advanceUntilIdle()

            assertEquals(emptySet(), eventRepo.linkedPairs)
        }

    /**
     * On a successful save the ViewModel clears the `isSaving` flag and any prior error,
     * keeps the screen in `Loaded`, and the repository captured the event.
     */
    @Test
    fun `save success clears is saving and keeps loaded state`() =
        runTest {
            val event = sampleEvent()
            val eventRepo = FakeEventRepository(events = listOf(event))
            val viewModel = newViewModel(eventRepo = eventRepo)
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals(false, state.isSaving)
            assertNull(state.errorMessage)
            assertNotNull(eventRepo.lastUpdated)
        }

    /**
     * When the repository fails the update, the ViewModel surfaces an error message in the
     * loaded state and clears `isSaving` so the user can try again.
     */
    @Test
    fun `save failure surfaces error message`() =
        runTest {
            val event = sampleEvent()
            val viewModel = newViewModel(eventRepo = FakeEventRepository(events = listOf(event), failUpdates = true))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertNotNull(state.errorMessage)
            assertEquals(false, state.isSaving)
        }

    /**
     * On a successful delete the ViewModel invokes the `onDeleted` callback (so the screen
     * can dismiss itself) and the repository sees the deletion request.
     */
    @Test
    fun `delete success invokes callback`() =
        runTest {
            val event = sampleEvent()
            val eventRepo = FakeEventRepository(events = listOf(event))
            val viewModel = newViewModel(eventRepo = eventRepo)
            viewModel.loadEvent(event.id)
            advanceUntilIdle()
            var deletedFired = false

            viewModel.delete { deletedFired = true }
            advanceUntilIdle()

            assertTrue(deletedFired)
            assertEquals(event.id, eventRepo.lastDeleted)
        }

    /**
     * When the repository fails to delete, the screen stays in `Loaded` with an error
     * message so the user is informed and can retry.
     */
    @Test
    fun `delete failure surfaces error message`() =
        runTest {
            val event = sampleEvent()
            val viewModel = newViewModel(eventRepo = FakeEventRepository(events = listOf(event), failDeletes = true))
            viewModel.loadEvent(event.id)
            advanceUntilIdle()

            viewModel.delete { }
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertNotNull(state.errorMessage)
        }

    /**
     * The linked notes section is reactive: pushing new note ids through the upstream junction
     * flow updates `linkedNotes` on the loaded state without re-issuing `loadEvent`.
     */
    @Test
    fun `linked notes update when repository emits`() =
        runTest {
            val event = sampleEvent()
            val firstNote = textNote(content = "first")
            val notesFlow = MutableStateFlow<List<Uuid>>(emptyList())
            val eventRepo = FakeEventRepository(events = listOf(event), notesForEventFlow = notesFlow)
            val notesRepo = FakeJournalNotesRepository(notes = listOf(firstNote))
            val viewModel = newViewModel(eventRepo = eventRepo, notesRepo = notesRepo)

            viewModel.loadEvent(event.id)
            advanceUntilIdle()
            assertEquals(emptyList(), (viewModel.uiState.value as EventDetailUiState.Loaded).linkedNotes)

            notesFlow.value = listOf(firstNote.uid)
            advanceUntilIdle()

            val state = viewModel.uiState.value as EventDetailUiState.Loaded
            assertEquals(listOf(firstNote), state.linkedNotes)
        }

    @Test
    fun `reloading the same event keeps a single subscription`() =
        runTest {
            val event = sampleEvent()
            val notesFlow = MutableStateFlow<List<Uuid>>(emptyList())
            val viewModel = newViewModel(eventRepo = FakeEventRepository(events = listOf(event), notesForEventFlow = notesFlow))

            repeat(3) {
                viewModel.loadEvent(event.id)
                advanceUntilIdle()
            }

            assertEquals(1, notesFlow.subscriptionCount.value)
        }

    @Test
    fun `loading another event replaces the previous draft`() =
        runTest {
            val first = sampleEvent(title = "First")
            val second = sampleEvent(title = "Second")
            val viewModel = newViewModel(events = listOf(first, second))
            viewModel.loadEvent(first.id)
            advanceUntilIdle()
            viewModel.updateTitle("Edited first")

            viewModel.loadEvent(second.id)
            advanceUntilIdle()

            assertEquals("Second", (viewModel.uiState.value as EventDetailUiState.Loaded).event.title)
        }

    /**
     * Helper that constructs a [EventDetailViewModel] wired to the real domain use cases over
     * the supplied fake repositories. Keeps individual tests focused on assertions.
     */
    private fun newViewModel(
        events: List<Event> = emptyList(),
        notes: List<JournalNote> = emptyList(),
        places: List<Place.UserDefined> = emptyList(),
        eventRepo: FakeEventRepository = FakeEventRepository(events = events),
        notesRepo: FakeJournalNotesRepository = FakeJournalNotesRepository(notes = notes),
        placesRepo: FakeUserPlacesRepository = FakeUserPlacesRepository(places = places),
    ): EventDetailViewModel =
        EventDetailViewModel(
            getEventById = GetEventByIdUseCase(eventRepo),
            observeLinkedNotesForEvent = ObserveLinkedNotesForEventUseCase(eventRepo, notesRepo),
            getAttachableNotesForEvent = GetAttachableNotesForEventUseCase(notesRepo, eventRepo),
            observeUserPlaces = ObserveUserPlacesUseCase(placesRepo),
            updateEvent = UpdateEventUseCase(eventRepo),
            deleteEvent = DeleteEventUseCase(eventRepo),
            linkNoteToEvent = LinkNoteToEventUseCase(eventRepo),
            unlinkNoteFromEvent = UnlinkNoteFromEventUseCase(eventRepo),
        )

    private fun sampleEvent(
        id: Uuid = Uuid.random(),
        title: String = "Sample",
    ): Event =
        Event(
            id = id,
            title = title,
            startTime = Instant.fromEpochSeconds(10_000),
            endTime = Instant.fromEpochSeconds(20_000),
            created = Instant.fromEpochSeconds(0),
            lastUpdated = Instant.fromEpochSeconds(0),
        )

    private fun textNote(
        uid: Uuid = Uuid.random(),
        creationTime: Instant = Instant.fromEpochSeconds(15_000),
        content: String = "note",
    ): JournalNote.Text =
        JournalNote.Text(
            uid = uid,
            content = content,
            creationTimestamp = creationTime,
            lastUpdated = creationTime,
        )
}

/**
 * In-memory [EventRepository] used by [EventDetailViewModelTest]. Tracks links via a mutable
 * `Set<Pair<eventId, noteId>>` so tests can assert immediate persistence.
 */
private class FakeEventRepository(
    events: List<Event> = emptyList(),
    private val failUpdates: Boolean = false,
    private val failDeletes: Boolean = false,
    val linkedPairs: MutableSet<Pair<Uuid, Uuid>> = mutableSetOf(),
    private val notesForEventFlow: MutableStateFlow<List<Uuid>>? = null,
) : EventRepository {
    private val state = MutableStateFlow(events)

    var lastUpdated: Event? = null
    var lastDeleted: Uuid? = null

    override fun observeAllEvents(): Flow<List<Event>> = state

    override fun observeEvent(eventId: Uuid): Flow<Event?> = state.map { list -> list.firstOrNull { it.id == eventId } }

    override fun observeEventsForDateRange(
        start: Instant,
        end: Instant,
    ): Flow<List<Event>> = MutableStateFlow(emptyList())

    override suspend fun getEventById(eventId: Uuid): Event? = state.value.firstOrNull { it.id == eventId }

    override suspend fun findByExternalCalendarId(externalId: String): Event? =
        state.value.firstOrNull { it.externalCalendarId == externalId }

    override suspend fun createEvent(event: Event): Result<Unit> = Result.success(Unit)

    override suspend fun updateEvent(event: Event): Result<Unit> {
        if (failUpdates) return Result.failure(RuntimeException("boom"))
        lastUpdated = event
        state.value = state.value.map { if (it.id == event.id) event else it }
        return Result.success(Unit)
    }

    override suspend fun deleteEvent(eventId: Uuid): Result<Unit> {
        if (failDeletes) return Result.failure(RuntimeException("boom"))
        lastDeleted = eventId
        state.value = state.value.filterNot { it.id == eventId }
        return Result.success(Unit)
    }

    override fun observeEventsForNote(noteId: Uuid): Flow<List<Event>> = MutableStateFlow(emptyList())

    override fun observeNotesForEvent(eventId: Uuid): Flow<List<Uuid>> =
        notesForEventFlow ?: MutableStateFlow(linkedPairs.filter { it.first == eventId }.map { it.second })

    override suspend fun linkNoteToEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> {
        linkedPairs.add(eventId to noteId)
        return Result.success(Unit)
    }

    override suspend fun unlinkNoteFromEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> {
        linkedPairs.remove(eventId to noteId)
        return Result.success(Unit)
    }
}

/**
 * Minimal [JournalNotesRepository] used by [EventDetailViewModelTest]. Only the methods the
 * ViewModel actually exercises are real; the rest throw to surface unexpected calls in tests.
 */
private class FakeJournalNotesRepository(
    private val notes: List<JournalNote> = emptyList(),
) : JournalNotesRepository {
    override val allNotesObserved: Flow<List<JournalNote>>
        get() = flowOf(notes)

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = flowOf(notes.filter { it.creationTimestamp in start..end })

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = flowOf(emptyList())

    override suspend fun create(note: JournalNote): Uuid = note.uid

    override suspend fun create(
        note: JournalNote,
        journalId: Uuid,
    ) = Unit

    override suspend fun remove(note: JournalNote) = Unit

    override suspend fun removeById(noteId: Uuid) = Unit

    override suspend fun removeFromJournal(
        noteId: Uuid,
        journalId: Uuid,
    ) = Unit

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = notes.firstOrNull { it.uid == noteId }

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()
}

/**
 * In-memory [UserPlacesRepository] for tests. Only `observeAllPlaces` is real.
 */
private class FakeUserPlacesRepository(
    private val places: List<Place.UserDefined> = emptyList(),
) : UserPlacesRepository {
    override fun observeAllPlaces(): Flow<List<Place>> = flowOf(places)

    override suspend fun getAllPlaces(): List<Place> = places

    override suspend fun getPlacesNear(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): List<Place> = emptyList()

    override suspend fun getPlaceById(placeId: String): Place? = null

    override suspend fun createPlace(place: Place): Result<Place> = Result.success(place)

    override suspend fun updatePlace(place: Place): Result<Place> = Result.success(place)

    override suspend fun deletePlace(placeId: String): Result<Unit> = Result.success(Unit)

    override suspend fun searchPlaces(query: String): List<Place> = emptyList()
}
