@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.domain.dayboundary.DayBoundarySettings
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.timeline.GetDayBoundsUseCase
import app.logdate.client.domain.timeline.GetTimelinePageUseCase
import app.logdate.client.domain.timeline.GroupNotesByDayBoundsUseCase
import app.logdate.client.domain.timeline.TimelineDay
import app.logdate.client.domain.timeline.TimelineDayBuilder
import app.logdate.client.domain.timeline.TimelinePageRequest
import app.logdate.client.health.HealthDataAvailability
import app.logdate.client.health.LocalFirstHealthRepository
import app.logdate.client.health.model.DayBounds
import app.logdate.client.health.model.SleepSession
import app.logdate.client.health.model.TimeOfDay
import app.logdate.client.health.util.LogdatePreferencesDataSource
import app.logdate.client.health.util.UserPreferences
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.shared.model.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TimelineHotPathBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val fixture = TimelineHotPathFixture()

    @Test
    fun groupTimelineNotesByCalendarDay() {
        benchmarkRule.measureRepeated {
            val grouped =
                runBlocking {
                    fixture.groupNotesByDayBoundsUseCase(
                        notes = fixture.notes,
                        timeZone = fixture.timeZone,
                    )
                }
            check(grouped.values.sumOf { it.size } == fixture.notes.size) {
                "Every note should be preserved by calendar grouping"
            }
        }
    }

    @Test
    fun shapeRecentTimelinePage() {
        benchmarkRule.measureRepeated {
            val page =
                runBlocking {
                    fixture.timelinePageUseCase(
                        TimelinePageRequest(pageSize = fixture.notes.size),
                    )
                }
            check(page.days.size == fixture.expectedDayCount) {
                "Expected ${fixture.expectedDayCount} days but got ${page.days.size}"
            }
            check(page.days.sumOf { it.entries.size } == fixture.notes.size) {
                "Every note should be preserved by timeline page shaping"
            }
        }
    }
}

private class TimelineHotPathFixture {
    val timeZone: TimeZone = TimeZone.currentSystemDefault()
    val notes: List<JournalNote> = buildTimelineNotes(timeZone)
    private val notesByDay = notes.groupBy { it.creationTimestamp.toLocalDateTime(timeZone).date }
    val expectedDayCount: Int = notesByDay.size

    private val dayBoundarySettingsRepository = FakeDayBoundarySettingsRepository()
    private val healthRepository = FakeHealthRepository()
    private val getDayBoundsUseCase = GetDayBoundsUseCase(healthRepository, dayBoundarySettingsRepository)

    val groupNotesByDayBoundsUseCase =
        GroupNotesByDayBoundsUseCase(
            getDayBoundsUseCase = getDayBoundsUseCase,
            dayBoundarySettingsRepository = dayBoundarySettingsRepository,
            preferencesDataSource = NoPreferences,
        )

    val timelinePageUseCase =
        GetTimelinePageUseCase(
            notesRepository = FakeJournalNotesRepository(notes, notesByDay),
            groupNotesByDayBoundsUseCase = groupNotesByDayBoundsUseCase,
            eventRepository = NoEvents,
            timelineDayBuilder = EntriesOnlyTimelineDay,
        )
}

private object NoPreferences : LogdatePreferencesDataSource {
    override suspend fun getPreferences(): UserPreferences = UserPreferences()
}

private object NoEvents : EventRepository {
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

/** Builds each day from its entries alone, so the benchmark measures paging and grouping. */
private val EntriesOnlyTimelineDay =
    TimelineDayBuilder { date, entries, events ->
        TimelineDay(
            start = entries.minOf { it.creationTimestamp },
            end = entries.maxOf { it.creationTimestamp },
            tldr = "",
            date = date,
            events = events,
            entries = entries.sortedByDescending { it.creationTimestamp },
        )
    }

private class FakeDayBoundarySettingsRepository : DayBoundarySettingsRepository {
    override suspend fun getSettings(): DayBoundarySettings = DayBoundarySettings(sleepBasedBoundariesEnabled = false)

    override fun observeSettings(): Flow<DayBoundarySettings> = flowOf(DayBoundarySettings(sleepBasedBoundariesEnabled = false))

    override suspend fun setSleepBasedBoundariesEnabled(enabled: Boolean) = Unit
}

private class FakeHealthRepository : LocalFirstHealthRepository {
    override suspend fun getHealthDataAvailability(): HealthDataAvailability = HealthDataAvailability.NOT_AVAILABLE

    override suspend fun hasSleepPermissions(): Boolean = false

    override suspend fun requestSleepPermissions(): Boolean = false

    override suspend fun isHealthDataAvailable(): Boolean = false

    override suspend fun getAvailableDataTypes(): List<String> = emptyList()

    override suspend fun getSleepSessions(
        start: Instant,
        end: Instant,
    ): List<SleepSession> = emptyList()

    override suspend fun getAverageWakeUpTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? = null

    override suspend fun getAverageSleepTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? = null

    override suspend fun getDayBoundsForDate(
        date: LocalDate,
        timeZone: TimeZone,
        sleepBasedBoundariesEnabled: Boolean,
    ): DayBounds {
        val start = LocalDateTime(date, LocalTime(4, 0)).toInstant(timeZone)
        val end = LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(4, 0)).toInstant(timeZone)
        return DayBounds(start = start, end = end)
    }
}

private class FakeJournalNotesRepository(
    notes: List<JournalNote>,
    private val notesByDay: Map<LocalDate, List<JournalNote>>,
) : JournalNotesRepository {
    private val notesDescending = notes.sortedByDescending(JournalNote::creationTimestamp)

    override val allNotesObserved: Flow<List<JournalNote>> = flowOf(notesDescending)

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = flowOf(notesDescending.drop(offset).take(pageSize))

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = flowOf(notesDescending.take(pageSize))

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = flowOf(notesDescending.take(limit))

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = notesDescending.firstOrNull { it.uid == noteId }

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

    override fun observeNotesForDay(day: LocalDate): Flow<List<JournalNote>> = flowOf(notesByDay[day].orEmpty())

    override suspend fun getNotesForDay(day: LocalDate): List<JournalNote> = notesByDay[day].orEmpty()

    override suspend fun getNotesBefore(
        beforeExclusive: Instant,
        limit: Int,
    ): List<JournalNote> = notesDescending.filter { it.creationTimestamp < beforeExclusive }.take(limit)

    override suspend fun hasNotesBefore(beforeExclusive: Instant): Boolean = notesDescending.any { it.creationTimestamp < beforeExclusive }
}

private fun buildTimelineNotes(timeZone: TimeZone): List<JournalNote> =
    buildList {
        repeat(TIMELINE_DAYS) { dayOffset ->
            val day = START_DATE.plus(dayOffset, DateTimeUnit.DAY)
            add(textNote(day, 7, 20, "Wake up and plan the day #$dayOffset", timeZone))
            add(textNote(day, 8, 45, "Breakfast and notes #$dayOffset", timeZone, location = locationFor(dayOffset % PLACES.size)))
            add(imageNote(day, 11, 30, "photo-$dayOffset", timeZone, location = locationFor((dayOffset + 1) % PLACES.size)))
            add(audioNote(day, 13, 15, "audio-$dayOffset", timeZone))
            add(videoNote(day, 16, 40, "video-$dayOffset", timeZone, location = locationFor((dayOffset + 2) % PLACES.size)))
            add(textNote(day, 18, 10, "Afternoon wrap-up #$dayOffset", timeZone))
            add(textNote(day, 21, 5, "Evening reflection #$dayOffset", timeZone, location = locationFor(dayOffset % PLACES.size)))
            add(textNote(day, 23, 20, "Late-night note #$dayOffset", timeZone, location = locationFor((dayOffset + 1) % PLACES.size)))
        }
    }.sortedByDescending { it.creationTimestamp }

private fun textNote(
    day: LocalDate,
    hour: Int,
    minute: Int,
    content: String,
    timeZone: TimeZone,
    location: NoteLocation? = null,
): JournalNote.Text {
    val instant = benchmarkInstant(day, hour, minute, timeZone)
    return JournalNote.Text(
        creationTimestamp = instant,
        lastUpdated = instant,
        content = content,
        location = location,
    )
}

private fun imageNote(
    day: LocalDate,
    hour: Int,
    minute: Int,
    mediaRef: String,
    timeZone: TimeZone,
    location: NoteLocation? = null,
): JournalNote.Image {
    val instant = benchmarkInstant(day, hour, minute, timeZone)
    return JournalNote.Image(
        creationTimestamp = instant,
        lastUpdated = instant,
        mediaRef = mediaRef,
        caption = "Caption for $mediaRef",
        location = location,
    )
}

private fun videoNote(
    day: LocalDate,
    hour: Int,
    minute: Int,
    mediaRef: String,
    timeZone: TimeZone,
    location: NoteLocation? = null,
): JournalNote.Video {
    val instant = benchmarkInstant(day, hour, minute, timeZone)
    return JournalNote.Video(
        creationTimestamp = instant,
        lastUpdated = instant,
        mediaRef = mediaRef,
        caption = "Caption for $mediaRef",
        location = location,
    )
}

private fun audioNote(
    day: LocalDate,
    hour: Int,
    minute: Int,
    mediaRef: String,
    timeZone: TimeZone,
    location: NoteLocation? = null,
): JournalNote.Audio {
    val instant = benchmarkInstant(day, hour, minute, timeZone)
    return JournalNote.Audio(
        mediaRef = mediaRef,
        durationMs = 45_000,
        creationTimestamp = instant,
        lastUpdated = instant,
        location = location,
    )
}

private fun benchmarkInstant(
    day: LocalDate,
    hour: Int,
    minute: Int,
    timeZone: TimeZone,
): Instant = LocalDateTime(day, LocalTime(hour, minute)).toInstant(timeZone)

private fun locationFor(index: Int): NoteLocation {
    val place = PLACES[index % PLACES.size]
    return NoteLocation(
        coordinates = NoteCoordinates(latitude = checkNotNull(place.latitude), longitude = checkNotNull(place.longitude)),
        place = place,
    )
}

private val PLACES =
    listOf(
        NotePlace(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "Home",
            latitude = 37.3317,
            longitude = -122.0301,
        ),
        NotePlace(
            id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
            name = "Cafe",
            latitude = 37.7749,
            longitude = -122.4194,
        ),
        NotePlace(
            id = Uuid.parse("00000000-0000-0000-0000-000000000003"),
            name = "Work",
            latitude = 37.789,
            longitude = -122.401,
        ),
    )

private const val TIMELINE_DAYS = 24

private val START_DATE = LocalDate(2025, 2, 1)
