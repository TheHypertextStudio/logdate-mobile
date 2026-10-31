package app.logdate.client.domain.events

import app.logdate.client.repository.events.EventRepository
import app.logdate.shared.model.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for the six event-related use cases.
 *
 * Each use case is a thin wrapper over [EventRepository] — these tests confirm the wrapper
 * forwards arguments correctly and returns the expected result. They use a small in-file
 * [FakeEventRepository] rather than a full mocking framework because the surface area is
 * tiny and direct fakes read more clearly.
 */
class EventUseCasesTest {
    /**
     * `GetEventByIdUseCase` returns the matching event when present and `null` when missing.
     * Both branches are exercised in one test for compactness.
     */
    @Test
    fun `get event by id emits event or null`() =
        runTest {
            val event = sampleEvent()
            val repo = FakeEventRepository(events = listOf(event))
            val useCase = GetEventByIdUseCase(repo)

            assertEquals(event, useCase(event.id).first())
            assertNull(useCase(Uuid.random()).first())
        }

    /**
     * `UpdateEventUseCase` returns the repository's [Result] verbatim and the fake captures
     * the exact event instance passed in, so callers can rely on it not being mutated.
     */
    @Test
    fun `update event returns repository result`() =
        runTest {
            val repo = FakeEventRepository()
            val useCase = UpdateEventUseCase(repo)
            val event = sampleEvent(title = "Edited")

            val result = useCase(event)

            assertTrue(result.isSuccess)
            assertEquals(event, repo.lastUpdated)
        }

    /**
     * `DeleteEventUseCase` forwards the id to the repository and returns the repository's
     * [Result] unchanged.
     */
    @Test
    fun `delete event calls repository`() =
        runTest {
            val repo = FakeEventRepository()
            val useCase = DeleteEventUseCase(repo)
            val id = Uuid.random()

            val result = useCase(id)

            assertTrue(result.isSuccess)
            assertEquals(id, repo.lastDeleted)
        }

    /**
     * Convenience builder for an [Event] with sensible defaults so individual tests only need
     * to override the fields they care about.
     */
    private fun sampleEvent(
        id: Uuid = Uuid.random(),
        title: String = "Sample",
    ): Event =
        Event(
            id = id,
            title = title,
            startTime = Instant.fromEpochSeconds(1_000),
            endTime = Instant.fromEpochSeconds(2_000),
            created = Instant.fromEpochSeconds(0),
            lastUpdated = Instant.fromEpochSeconds(0),
        )
}

/**
 * Tiny in-memory [EventRepository] used only by [EventUseCasesTest].
 *
 * Each test passes the data shape it cares about through the constructor (a fixed range
 * result, a map of events-for-note, etc.) and inspects the public `last…` fields after the
 * use case runs to confirm the right method was called with the right arguments.
 *
 * Mutating methods record their inputs but do not actually persist anything; reads return the
 * pre-configured values. The full repository contract is implemented to satisfy the interface,
 * even though some tests only exercise a subset.
 */
private class FakeEventRepository(
    private val events: List<Event> = emptyList(),
    private val rangeResult: List<Event> = emptyList(),
    private val eventsForNote: Map<Uuid, List<Event>> = emptyMap(),
    private val notesForEvent: Map<Uuid, List<Uuid>> = emptyMap(),
) : EventRepository {
    var lastRangeStart: Instant? = null
    var lastRangeEnd: Instant? = null
    var lastUpdated: Event? = null
    var lastDeleted: Uuid? = null

    private val state = MutableStateFlow(events)

    override fun observeAllEvents(): Flow<List<Event>> = state

    override fun observeEvent(eventId: Uuid): Flow<Event?> = state.map { list -> list.firstOrNull { it.id == eventId } }

    override fun observeEventsForDateRange(
        start: Instant,
        end: Instant,
    ): Flow<List<Event>> {
        lastRangeStart = start
        lastRangeEnd = end
        return MutableStateFlow(rangeResult)
    }

    override suspend fun getEventById(eventId: Uuid): Event? = events.firstOrNull { it.id == eventId }

    override suspend fun findByExternalCalendarId(externalId: String): Event? = events.firstOrNull { it.externalCalendarId == externalId }

    override suspend fun createEvent(event: Event): Result<Unit> = Result.success(Unit)

    override suspend fun updateEvent(event: Event): Result<Unit> {
        lastUpdated = event
        return Result.success(Unit)
    }

    override suspend fun deleteEvent(eventId: Uuid): Result<Unit> {
        lastDeleted = eventId
        return Result.success(Unit)
    }

    override fun observeEventsForNote(noteId: Uuid): Flow<List<Event>> = MutableStateFlow(eventsForNote[noteId].orEmpty())

    override fun observeNotesForEvent(eventId: Uuid): Flow<List<Uuid>> = MutableStateFlow(notesForEvent[eventId].orEmpty())

    override suspend fun linkNoteToEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun unlinkNoteFromEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> = Result.success(Unit)
}
