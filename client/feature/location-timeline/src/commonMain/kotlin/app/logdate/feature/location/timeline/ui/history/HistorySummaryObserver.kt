package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.domain.location.history.LocationHistoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Observes lightweight week and month day-activity summaries (`hasVisits`, `hasMemories`) for the timeline
 * strip and calendar picker without retaining full daily snapshot flows.
 */
internal class HistorySummaryObserver(
    private val history: LocationHistoryService,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HumanLocationHistoryState>,
) {
    private var weekJob: Job? = null
    private var monthJob: Job? = null

    fun observeWeek(
        date: LocalDate,
        zone: TimeZone,
    ) {
        weekJob?.cancel()
        val (weekStart, weekEnd) = weekRangeFor(date)
        weekJob =
            scope.launch {
                val latest = runCatching { history.latestRecordedDate(zone) }.getOrNull()
                val today =
                    Clock.System
                        .now()
                        .toLocalDateTime(zone)
                        .date
                runCatching {
                    history.observeRecordedDaySummaries(weekStart, weekEnd, zone).collect { map ->
                        state.update {
                            it.copy(
                                selectedDate = date,
                                weekDays = toRecordedDayUiList(map, today),
                                latestRecordedDate = latest ?: it.latestRecordedDate,
                            )
                        }
                    }
                }
            }
    }

    fun observeCalendar(
        calendarMonth: LocalDate,
        zone: TimeZone,
    ) {
        monthJob?.cancel()
        val (monthStart, monthEnd) = monthRangeFor(calendarMonth)
        val label = monthTitle(calendarMonth)
        monthJob =
            scope.launch {
                val today =
                    Clock.System
                        .now()
                        .toLocalDateTime(zone)
                        .date
                runCatching {
                    history.observeRecordedDaySummaries(monthStart, monthEnd, zone).collect { map ->
                        state.update { it.copy(calendarMonthLabel = label, calendarDays = toRecordedDayUiList(map, today)) }
                    }
                }
            }
    }
}
