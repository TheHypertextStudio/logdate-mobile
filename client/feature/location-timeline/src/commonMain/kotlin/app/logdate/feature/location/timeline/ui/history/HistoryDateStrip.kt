@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.client.text.UiText
import app.logdate.client.text.asString
import app.logdate.ui.common.ModalBottomSheet
import app.logdate.ui.theme.Spacing
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_day_accessibility_label
import logdate.client.feature.location.timeline.generated.resources.history_jump_to_latest
import logdate.client.feature.location.timeline.generated.resources.history_month_april
import logdate.client.feature.location.timeline.generated.resources.history_month_august
import logdate.client.feature.location.timeline.generated.resources.history_month_december
import logdate.client.feature.location.timeline.generated.resources.history_month_february
import logdate.client.feature.location.timeline.generated.resources.history_month_january
import logdate.client.feature.location.timeline.generated.resources.history_month_july
import logdate.client.feature.location.timeline.generated.resources.history_month_june
import logdate.client.feature.location.timeline.generated.resources.history_month_march
import logdate.client.feature.location.timeline.generated.resources.history_month_may
import logdate.client.feature.location.timeline.generated.resources.history_month_november
import logdate.client.feature.location.timeline.generated.resources.history_month_october
import logdate.client.feature.location.timeline.generated.resources.history_month_september
import logdate.client.feature.location.timeline.generated.resources.history_month_year
import logdate.client.feature.location.timeline.generated.resources.history_next_month
import logdate.client.feature.location.timeline.generated.resources.history_previous_month
import logdate.client.feature.location.timeline.generated.resources.history_timezone_badge
import logdate.client.feature.location.timeline.generated.resources.history_timezone_utc_hm
import logdate.client.feature.location.timeline.generated.resources.history_timezone_utc_whole
import logdate.client.feature.location.timeline.generated.resources.history_weekday_fri_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_fri_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_mon_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_mon_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_sat_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_sat_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_sun_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_sun_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_thu_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_thu_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_tue_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_tue_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_wed_initial
import logdate.client.feature.location.timeline.generated.resources.history_weekday_wed_short
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Instant

@Immutable
data class RecordedDayUi(
    val date: LocalDate,
    val dayOfWeekRes: StringResource,
    val dayOfMonth: Int,
    val hasVisits: Boolean,
    val hasMemories: Boolean,
    val isToday: Boolean,
)

@Composable
internal fun HistoryWeekStrip(
    days: List<RecordedDayUi>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { day ->
            val isSelected = day.date == selectedDate
            val dayName = stringResource(day.dayOfWeekRes)
            val accessibilityText = stringResource(Res.string.history_day_accessibility_label, dayName, day.dayOfMonth)
            val containerColor =
                when {
                    isSelected -> MaterialTheme.colorScheme.secondaryContainer
                    day.isToday -> MaterialTheme.colorScheme.surfaceContainerLow
                    else -> Color.Transparent
                }
            val contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
            Surface(
                onClick = { onSelectDate(day.date) },
                modifier =
                    Modifier.weight(1f).semantics {
                        selected = isSelected
                        contentDescription = accessibilityText
                    },
                shape = MaterialTheme.shapes.medium,
                color = containerColor,
                contentColor = contentColor,
                border = if (day.isToday && !isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected || day.isToday) FontWeight.Bold else FontWeight.Medium,
                    )
                    DayActivityDot(hasVisits = day.hasVisits, hasMemories = day.hasMemories)
                }
            }
        }
    }
}

@Composable
private fun DayActivityDot(
    hasVisits: Boolean,
    hasMemories: Boolean,
) {
    when {
        hasVisits && hasMemories -> {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }

        hasVisits -> {
            Surface(
                modifier = Modifier.size(6.dp),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            ) {}
        }

        hasMemories -> {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
        }

        else -> {
            Spacer(Modifier.size(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryCalendarSheet(
    monthLabel: UiText,
    days: List<RecordedDayUi>,
    selectedDate: LocalDate,
    latestRecordedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    onMonthOffset: (Int) -> Unit,
    onJumpToLatest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = monthLabel.asString(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { onMonthOffset(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.history_previous_month))
                }
                IconButton(onClick = { onMonthOffset(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(Res.string.history_next_month))
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                WEEKDAY_HEADER_RESOURCES.forEach { headerRes ->
                    Text(
                        text = stringResource(headerRes),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val firstDayOffset =
                days
                    .firstOrNull()
                    ?.date
                    ?.dayOfWeek
                    ?.let(::mondayZeroIndex) ?: 0
            val paddedCells: List<RecordedDayUi?> = List(firstDayOffset) { null } + days
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                paddedCells.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (index in 0 until 7) {
                            val cell = week.getOrNull(index)
                            if (cell == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                val isSelected = cell.date == selectedDate
                                Surface(
                                    onClick = {
                                        onSelectDate(cell.date)
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.small,
                                    color =
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                            cell.hasVisits || cell.hasMemories -> MaterialTheme.colorScheme.surfaceContainerLow
                                            else -> Color.Transparent
                                        },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = cell.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected || cell.isToday) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        DayActivityDot(hasVisits = cell.hasVisits, hasMemories = cell.hasMemories)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (latestRecordedDate != null && latestRecordedDate != selectedDate) {
                FilledTonalButton(
                    onClick = {
                        onJumpToLatest()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.history_jump_to_latest))
                }
            }
        }
    }
}

private val WEEKDAY_HEADER_RESOURCES =
    listOf(
        Res.string.history_weekday_mon_initial,
        Res.string.history_weekday_tue_initial,
        Res.string.history_weekday_wed_initial,
        Res.string.history_weekday_thu_initial,
        Res.string.history_weekday_fri_initial,
        Res.string.history_weekday_sat_initial,
        Res.string.history_weekday_sun_initial,
    )

internal fun weekRangeFor(anchor: LocalDate): Pair<LocalDate, LocalDate> {
    val offset = mondayZeroIndex(anchor.dayOfWeek)
    val start = anchor.minus(DatePeriod(days = offset))
    return start to start.plus(DatePeriod(days = 6))
}

internal fun monthRangeFor(monthAnchor: LocalDate): Pair<LocalDate, LocalDate> {
    val first = LocalDate(monthAnchor.year, monthAnchor.month, 1)
    var last = first
    while (last.plus(DatePeriod(days = 1)).month == monthAnchor.month) last = last.plus(DatePeriod(days = 1))
    return first to last
}

internal fun monthTitle(date: LocalDate): UiText {
    val monthRes =
        when (date.month) {
            Month.JANUARY -> Res.string.history_month_january
            Month.FEBRUARY -> Res.string.history_month_february
            Month.MARCH -> Res.string.history_month_march
            Month.APRIL -> Res.string.history_month_april
            Month.MAY -> Res.string.history_month_may
            Month.JUNE -> Res.string.history_month_june
            Month.JULY -> Res.string.history_month_july
            Month.AUGUST -> Res.string.history_month_august
            Month.SEPTEMBER -> Res.string.history_month_september
            Month.OCTOBER -> Res.string.history_month_october
            Month.NOVEMBER -> Res.string.history_month_november
            Month.DECEMBER -> Res.string.history_month_december
        }
    return UiText.Resource(Res.string.history_month_year, listOf(UiText.Resource(monthRes), date.year))
}

internal fun shortDayOfWeekResource(dayOfWeek: DayOfWeek): StringResource =
    when (dayOfWeek) {
        DayOfWeek.MONDAY -> Res.string.history_weekday_mon_short
        DayOfWeek.TUESDAY -> Res.string.history_weekday_tue_short
        DayOfWeek.WEDNESDAY -> Res.string.history_weekday_wed_short
        DayOfWeek.THURSDAY -> Res.string.history_weekday_thu_short
        DayOfWeek.FRIDAY -> Res.string.history_weekday_fri_short
        DayOfWeek.SATURDAY -> Res.string.history_weekday_sat_short
        DayOfWeek.SUNDAY -> Res.string.history_weekday_sun_short
    }

internal fun mondayZeroIndex(dayOfWeek: DayOfWeek): Int = dayOfWeek.ordinal

internal fun formatTimezoneBadge(
    effectiveZoneId: String?,
    instant: Instant,
    deviceZone: TimeZone,
): UiText? {
    if (effectiveZoneId.isNullOrBlank()) return null
    val zone = runCatching { TimeZone.of(effectiveZoneId) }.getOrNull() ?: return null
    if (zone.id == deviceZone.id && zone.offsetAt(instant) == deviceZone.offsetAt(instant)) return null
    val totalSeconds = zone.offsetAt(instant).totalSeconds
    val hours = totalSeconds / 3600
    val minutes = abs((totalSeconds % 3600) / 60)
    val sign = if (totalSeconds >= 0) "+" else "-"
    val absHours = abs(hours)
    val offset =
        if (minutes == 0) {
            UiText.Resource(Res.string.history_timezone_utc_whole, listOf(sign, absHours))
        } else {
            UiText.Resource(Res.string.history_timezone_utc_hm, listOf(sign, absHours, minutes.toString().padStart(2, '0')))
        }
    val region = zone.id.substringAfterLast('/').replace('_', ' ')
    return UiText.Resource(Res.string.history_timezone_badge, listOf(region, offset))
}
