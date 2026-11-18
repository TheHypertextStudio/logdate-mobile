package app.logdate.client.data.events

import app.logdate.client.database.dao.EventDao
import app.logdate.client.database.dao.EventNoteLinkDao
import app.logdate.client.database.entities.EventNoteLinkEntity
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.util.platformDataDispatcher
import app.logdate.client.util.runCatchingUnlessCancelled
import app.logdate.shared.model.Event
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Offline-first implementation of [EventRepository] backed by Room.
 *
 * The local database is the source of truth. Sync metadata is intentionally minimal for now —
 * future iterations will plumb events through the standard sync pipeline alongside journals
 * and notes.
 *
 * Suspend methods run on the injected [dispatcher] (defaults to [platformDataDispatcher]) so callers
 * never block the main thread on a Room write. Flow-returning observe methods don't need
 * explicit wrapping because Room's `setQueryCoroutineContext` already dispatches each emission
 * onto the configured database dispatcher.
 */
class OfflineFirstEventRepository(
    private val eventDao: EventDao,
    private val eventNoteLinkDao: EventNoteLinkDao,
    private val dispatcher: CoroutineDispatcher = platformDataDispatcher,
    private val mediaClaimFence: MediaClaimFence = MediaClaimFence.Disabled,
) : EventRepository {
    override fun observeAllEvents(): Flow<List<Event>> = eventDao.observeAll().map { entities -> entities.map { it.toModel() } }

    override fun observeEvent(eventId: Uuid): Flow<Event?> = eventDao.observeById(eventId).map { entity -> entity?.toModel() }

    override fun observeEventsForDateRange(
        start: Instant,
        end: Instant,
    ): Flow<List<Event>> =
        eventDao
            .observeForDateRange(start, end)
            .map { entities -> entities.map { it.toModel() } }

    override suspend fun getEventById(eventId: Uuid): Event? =
        withContext(dispatcher) {
            eventDao.getById(eventId)?.toModel()
        }

    override suspend fun findByExternalCalendarId(externalId: String): Event? =
        withContext(dispatcher) {
            eventDao.getByExternalCalendarId(externalId)?.toModel()
        }

    override suspend fun createEvent(event: Event): Result<Unit> =
        withContext(dispatcher) {
            runCatchingUnlessCancelled {
                mediaClaimFence.withClaim { eventDao.insert(event.toEntity()) }
            }.onFailure { error -> Napier.e("Failed to create event ${event.id}", error) }
        }

    override suspend fun updateEvent(event: Event): Result<Unit> =
        withContext(dispatcher) {
            runCatchingUnlessCancelled {
                mediaClaimFence.withClaim { eventDao.update(event.copy(lastUpdated = Clock.System.now()).toEntity()) }
            }.onFailure { error -> Napier.e("Failed to update event ${event.id}", error) }
        }

    override suspend fun deleteEvent(eventId: Uuid): Result<Unit> =
        withContext(dispatcher) {
            runCatchingUnlessCancelled {
                mediaClaimFence.withClaim { eventDao.softDelete(eventId, Clock.System.now().toEpochMilliseconds()) }
            }.onFailure { error -> Napier.e("Failed to delete event $eventId", error) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEventsForNote(noteId: Uuid): Flow<List<Event>> =
        eventNoteLinkDao.getEventsForNote(noteId).flatMapLatest { eventIds ->
            flow {
                val events =
                    if (eventIds.isEmpty()) emptyList() else eventDao.getByIds(eventIds).map { it.toModel() }
                emit(events)
            }
        }

    override fun observeNotesForEvent(eventId: Uuid): Flow<List<Uuid>> = eventNoteLinkDao.getNotesForEvent(eventId)

    override suspend fun linkNoteToEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> =
        withContext(dispatcher) {
            runCatchingUnlessCancelled {
                eventNoteLinkDao.insert(
                    EventNoteLinkEntity(
                        eventId = eventId,
                        noteId = noteId,
                    ),
                )
            }.onFailure { error -> Napier.e("Failed to link note $noteId to event $eventId", error) }
        }

    override suspend fun unlinkNoteFromEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> =
        withContext(dispatcher) {
            runCatchingUnlessCancelled {
                eventNoteLinkDao.delete(eventId, noteId)
            }.onFailure { error -> Napier.e("Failed to unlink note $noteId from event $eventId", error) }
        }
}
