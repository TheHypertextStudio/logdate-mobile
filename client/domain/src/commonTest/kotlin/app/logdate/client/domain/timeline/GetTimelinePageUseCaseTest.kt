package app.logdate.client.domain.timeline

import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.shared.model.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests the paginated retrieval and date-based grouping of journal entries for the timeline.
 *
 * Ensures that [GetTimelinePageUseCase] correctly handles page requests, maintains
 * chronological order, avoids duplicating entries at page boundaries, and accurately
 * reports when more historical content is available.
 */
class GetTimelinePageUseCaseTest {
    @Test
    fun `invoke returns newest complete days and indicates more history`() =
        runTest {
            val repository =
                FakeJournalNotesRepository(
                    notes =
                        listOf(
                            textNote("Jan 3 late", "2025-01-03T18:00:00Z"),
                            textNote("Jan 3 early", "2025-01-03T15:00:00Z"),
                            textNote("Jan 2 late", "2025-01-02T20:00:00Z"),
                            textNote("Jan 2 early", "2025-01-02T16:00:00Z"),
                            textNote("Jan 1", "2025-01-01T18:00:00Z"),
                        ),
                )

            val result =
                GetTimelinePageUseCase(repository, calendarDateGrouper(), PageNoOpEventRepository, PageTimelineDayBuilder)(
                    TimelinePageRequest(
                        pageSize = 3,
                    ),
                )

            assertEquals(2, result.days.size)
            assertEquals(listOf(2025, 2025), result.days.map { it.date.year })
            assertEquals(listOf(3, 2), result.days.map { it.date.day })
            assertEquals(2, result.days[0].entries.size)
            assertEquals(2, result.days[1].entries.size)
            assertTrue(result.hasMoreOlderContent)
            assertNotNull(result.oldestLoadedTimestamp)
        }

    @Test
    fun `invoke with cursor loads older days without duplicating boundary day`() =
        runTest {
            val repository =
                FakeJournalNotesRepository(
                    notes =
                        listOf(
                            textNote("Jan 3 late", "2025-01-03T18:00:00Z"),
                            textNote("Jan 3 early", "2025-01-03T15:00:00Z"),
                            textNote("Jan 2 late", "2025-01-02T20:00:00Z"),
                            textNote("Jan 2 early", "2025-01-02T16:00:00Z"),
                            textNote("Jan 1", "2025-01-01T18:00:00Z"),
                        ),
                )
            val useCase = GetTimelinePageUseCase(repository, calendarDateGrouper(), PageNoOpEventRepository, PageTimelineDayBuilder)

            val firstPage = useCase(TimelinePageRequest(pageSize = 3))
            val secondPage =
                useCase(
                    TimelinePageRequest(
                        beforeExclusive = firstPage.oldestLoadedTimestamp,
                        pageSize = 3,
                    ),
                )

            assertEquals(1, secondPage.days.size)
            assertEquals(
                1,
                secondPage.days
                    .single()
                    .date.day,
            )
            assertFalse(secondPage.hasMoreOlderContent)
        }

    @Test
    fun `invoke reports end of history when cursor is already at the oldest note`() =
        runTest {
            val repository =
                FakeJournalNotesRepository(
                    notes =
                        listOf(
                            textNote("Only day late", "2025-01-03T18:00:00Z"),
                            textNote("Only day early", "2025-01-03T15:00:00Z"),
                        ),
                )
            val useCase = GetTimelinePageUseCase(repository, calendarDateGrouper(), PageNoOpEventRepository, PageTimelineDayBuilder)

            val firstPage = useCase(TimelinePageRequest(pageSize = 1))
            val secondPage =
                useCase(
                    TimelinePageRequest(
                        beforeExclusive = firstPage.oldestLoadedTimestamp,
                        pageSize = 10,
                    ),
                )

            assertTrue(secondPage.days.isEmpty())
            assertFalse(secondPage.hasMoreOlderContent)
            assertEquals(null, secondPage.oldestLoadedTimestamp)
        }

    @Test
    fun `invoke returns complete dense day even when it exceeds page size`() =
        runTest {
            val repository =
                FakeJournalNotesRepository(
                    notes =
                        listOf(
                            textNote("Jan 3 late", "2025-01-03T18:00:00Z"),
                            textNote("Jan 3 afternoon", "2025-01-03T15:00:00Z"),
                            textNote("Jan 3 morning", "2025-01-03T09:00:00Z"),
                            textNote("Jan 2", "2025-01-02T18:00:00Z"),
                        ),
                )

            val result =
                GetTimelinePageUseCase(repository, calendarDateGrouper(), PageNoOpEventRepository, PageTimelineDayBuilder)(
                    TimelinePageRequest(pageSize = 1),
                )

            assertEquals(1, result.days.size)
            assertEquals(
                3,
                result.days
                    .single()
                    .date
                    .day,
            )
            assertEquals(
                3,
                result.days
                    .single()
                    .entries
                    .size,
            )
            assertEquals(Instant.parse("2025-01-03T09:00:00Z"), result.oldestLoadedTimestamp)
            assertTrue(result.hasMoreOlderContent)
        }

    private fun textNote(
        content: String,
        createdAt: String,
    ): JournalNote.Text {
        val instant = Instant.parse(createdAt)
        return JournalNote.Text(
            uid = Uuid.random(),
            content = content,
            creationTimestamp = instant,
            lastUpdated = instant,
        )
    }

    private class FakeJournalNotesRepository(
        private val notes: List<JournalNote>,
    ) : JournalNotesRepository {
        override val allNotesObserved: Flow<List<JournalNote>> = flowOf(notes)

        override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesInRange(
            start: Instant,
            end: Instant,
        ): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesPage(
            pageSize: Int,
            offset: Int,
        ): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = flowOf(notes.take(limit))

        override suspend fun getNoteById(noteId: Uuid): JournalNote? = notes.firstOrNull { it.uid == noteId }

        override suspend fun create(note: JournalNote): Uuid = note.uid

        override suspend fun remove(note: JournalNote) = Unit

        override suspend fun removeById(noteId: Uuid) = Unit

        override suspend fun create(
            note: JournalNote,
            journalId: Uuid,
        ) = Unit

        override suspend fun removeFromJournal(
            noteId: Uuid,
            journalId: Uuid,
        ) = Unit

        override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()
    }
}

private object PageNoOpEventRepository : EventRepository {
    override fun observeAllEvents(): Flow<List<Event>> = flowOf(emptyList())

    override fun observeEvent(eventId: Uuid): Flow<Event?> = flowOf(null)

    override fun observeEventsForDateRange(
        start: Instant,
        end: Instant,
    ): Flow<List<Event>> = flowOf(emptyList())

    override suspend fun getEventById(eventId: Uuid): Event? = null

    override suspend fun findByExternalCalendarId(externalId: String): Event? = null

    override suspend fun createEvent(event: Event): Result<Unit> = Result.success(Unit)

    override suspend fun updateEvent(event: Event): Result<Unit> = Result.success(Unit)

    override suspend fun deleteEvent(eventId: Uuid): Result<Unit> = Result.success(Unit)

    override fun observeEventsForNote(noteId: Uuid): Flow<List<Event>> = flowOf(emptyList())

    override fun observeNotesForEvent(eventId: Uuid): Flow<List<Uuid>> = flowOf(emptyList())

    override suspend fun linkNoteToEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun unlinkNoteFromEvent(
        eventId: Uuid,
        noteId: Uuid,
    ): Result<Unit> = Result.success(Unit)
}

private val PageTimelineDayBuilder =
    TimelineDayBuilder { date, entries, events ->
        val places = extractPlacesVisited(entries)
        TimelineDay(
            start = entries.minOf { it.creationTimestamp },
            end = entries.maxOf { it.creationTimestamp },
            tldr = "Test summary",
            date = date,
            events = events,
            placesVisited = places,
            moments = inferMomentsHeuristically(entries, places),
            parts = extractDayParts(entries),
            entries = entries.sortedByDescending { it.creationTimestamp },
        )
    }
