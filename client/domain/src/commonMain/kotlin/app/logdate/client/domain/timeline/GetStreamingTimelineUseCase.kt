package app.logdate.client.domain.timeline

import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.shared.model.Event
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.transformLatest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Request type for streaming timeline queries.
 */
sealed interface StreamingTimelineRequest {
    data class RecentTimeline(
        val sortOrder: TimelineSortOrder = TimelineSortOrder.REVERSE_CHRONOLOGICAL,
        val pageSize: Int = 50,
    ) : StreamingTimelineRequest

    data class TimelineInRange(
        val start: Instant,
        val end: Instant,
        val sortOrder: TimelineSortOrder = TimelineSortOrder.REVERSE_CHRONOLOGICAL,
    ) : StreamingTimelineRequest
}

/**
 * Builds the timeline incrementally: the newest entries appear first, then their days are enriched,
 * instead of waiting for every note to load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetStreamingTimelineUseCase(
    private val notesRepository: JournalNotesRepository,
    private val getTimelineDayUseCase: TimelineDayBuilder,
    private val groupNotesByDayBoundsUseCase: GroupNotesByDayBoundsUseCase,
    private val eventRepository: EventRepository,
    private val marks: Flow<Map<LocalDate, List<app.logdate.client.repository.moments.MarkedMoment>>> =
        kotlinx.coroutines.flow.flowOf(
            emptyMap(),
        ),
) {
    /**
     * Gets streaming timeline based on the request type
     */
    operator fun invoke(request: StreamingTimelineRequest = StreamingTimelineRequest.RecentTimeline()): Flow<Timeline> =
        when (request) {
            is StreamingTimelineRequest.RecentTimeline -> getRecentTimeline(request.sortOrder, request.pageSize)
            is StreamingTimelineRequest.TimelineInRange -> getTimelineInRange(request.start, request.end, request.sortOrder)
        }.combine(marks.onStart { emit(emptyMap()) }) { timeline, markedDays ->
            val included =
                if (request is StreamingTimelineRequest.TimelineInRange) {
                    markedDays
                        .mapValues { (_, rows) -> rows.filter { it.capturedAt >= request.start && it.capturedAt < request.end } }
                        .filterValues { it.isNotEmpty() }
                } else {
                    markedDays
                }
            val merged =
                app.logdate.client.domain.moments
                    .withTimelineMarks(timeline, included)
            val order =
                when (request) {
                    is StreamingTimelineRequest.RecentTimeline -> request.sortOrder
                    is StreamingTimelineRequest.TimelineInRange -> request.sortOrder
                }
            merged.copy(days = applySorting(merged.days, order))
        }.runningReduce(::retainAvailableTimelineSummaries)

    private fun getRecentTimeline(
        sortOrder: TimelineSortOrder,
        pageSize: Int,
    ): Flow<Timeline> =
        notesRepository
            .observeRecentNotes(pageSize)
            .transformLatest { recentNotes ->
                val basicTimeline = createBasicTimeline(recentNotes, sortOrder, emptyList())
                emit(basicTimeline)

                if (basicTimeline.days.isEmpty()) {
                    return@transformLatest
                }

                val recentDates = basicTimeline.days.map(TimelineDay::date).toSet()
                // Only the recent days, widened by a day each side for journaling-day boundaries,
                // can change these cards, so watch that window instead of every note ever written.
                val timeZone = TimeZone.currentSystemDefault()
                val windowStart = recentDates.min().minus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
                val windowEnd = recentDates.max().plus(2, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
                emitAll(
                    notesRepository.observeNotesInRange(windowStart, windowEnd).mapLatest { allNotes ->
                        createEnrichedTimeline(
                            recentDates = recentDates,
                            allNotes = allNotes,
                            fallbackNotes = recentNotes,
                            events = emptyList(),
                            sortOrder = sortOrder,
                        )
                    },
                )
            }.combine(eventRepository.observeAllEvents().onStart { emit(emptyList()) }) { timeline, events ->
                timeline.copy(days = timeline.days.map { day -> day.copy(events = events.overlapping(day.entries)) })
            }.distinctUntilChanged()

    private fun getTimelineInRange(
        start: Instant,
        end: Instant,
        sortOrder: TimelineSortOrder,
    ): Flow<Timeline> =
        combine(
            notesRepository.observeNotesInRange(start, end),
            eventRepository.observeEventsForDateRange(start, end),
        ) { notesInRange, events ->
            val notesByDay = groupNotesByDayBoundsUseCase(notesInRange)

            val timelineDays =
                notesByDay.map { (date, entries) ->
                    getTimelineDayUseCase(date, entries, events.overlapping(entries))
                }

            val sortedDays =
                when (sortOrder) {
                    TimelineSortOrder.CHRONOLOGICAL -> timelineDays.sortedBy { it.date }
                    TimelineSortOrder.REVERSE_CHRONOLOGICAL -> timelineDays.sortedByDescending { it.date }
                }

            Timeline(sortedDays)
        }

    private suspend fun createBasicTimeline(
        notes: List<JournalNote>,
        sortOrder: TimelineSortOrder,
        events: List<Event>,
    ): Timeline {
        val notesByDay = groupNotesByDayBoundsUseCase(notes)
        val basicDays = notesByDay.map { (date, entries) -> buildBasicDay(date, entries, events) }
        return Timeline(applySorting(basicDays, sortOrder))
    }

    /**
     * Builds a summary-less [TimelineDay] for entries already assigned to [date]. Used for the
     * immediate (pre-summary) render and as a fallback when enriched data isn't ready.
     */
    private fun buildBasicDay(
        date: LocalDate,
        entries: List<JournalNote>,
        events: List<Event>,
    ): TimelineDay {
        val places = extractPlacesVisited(entries)
        return TimelineDay(
            start = entries.minOf { it.creationTimestamp },
            end = entries.maxOf { it.creationTimestamp },
            tldr = "", // Empty - UI will show loading placeholder via isLoadingSummary
            date = date,
            people = emptyList(),
            events = events.overlapping(entries),
            placesVisited = places,
            moments = inferMomentsHeuristically(entries, places),
            parts = extractDayParts(entries),
            entries = entries.sortedByDescending { it.creationTimestamp },
            isRecentWindow = true,
        )
    }

    private suspend fun createEnrichedTimeline(
        recentDates: Set<LocalDate>,
        allNotes: List<JournalNote>,
        fallbackNotes: List<JournalNote>,
        events: List<Event>,
        sortOrder: TimelineSortOrder,
    ): Timeline {
        val timeZone = TimeZone.currentSystemDefault()
        // A journaling day can pull in entries from the adjacent calendar dates (a 1 AM note
        // belongs to the previous day under a 3 AM boundary), so widen the calendar window by a
        // day on each side before regrouping by journaling day.
        val windowDates =
            recentDates
                .flatMap { date ->
                    listOf(date.minus(1, DateTimeUnit.DAY), date, date.plus(1, DateTimeUnit.DAY))
                }.toSet()
        val relevantNotes =
            allNotes.filter { note ->
                note.creationTimestamp.toLocalDateTime(timeZone).date in windowDates
            }
        val allNotesByDay = groupNotesByDayBoundsUseCase(relevantNotes)
        val fallbackByDay = groupNotesByDayBoundsUseCase(fallbackNotes)

        val days =
            recentDates.mapNotNull { date ->
                val enriched = allNotesByDay[date]
                if (enriched != null) {
                    getTimelineDayUseCase(date, enriched, events.overlapping(enriched))
                } else {
                    fallbackByDay[date]?.let { buildBasicDay(date, it, events) }
                }
            }

        return Timeline(applySorting(days, sortOrder))
    }

    private fun applySorting(
        days: List<TimelineDay>,
        sortOrder: TimelineSortOrder,
    ): List<TimelineDay> =
        when (sortOrder) {
            TimelineSortOrder.CHRONOLOGICAL -> days.sortedBy { it.date }
            TimelineSortOrder.REVERSE_CHRONOLOGICAL -> days.sortedByDescending { it.date }
        }
}

private fun List<Event>.overlapping(entries: List<JournalNote>): List<Event> {
    if (entries.isEmpty()) return emptyList()
    val dayStart = entries.minOf { it.creationTimestamp }
    val dayEnd = entries.maxOf { it.creationTimestamp }
    return filter { event ->
        event.startTime < dayEnd && (event.endTime ?: event.startTime) >= dayStart
    }
}
