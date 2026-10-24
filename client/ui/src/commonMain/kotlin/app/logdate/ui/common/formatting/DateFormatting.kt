package app.logdate.ui.common.formatting

import androidx.compose.runtime.Composable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.date_formatting_april
import logdate.client.ui.generated.resources.date_formatting_august
import logdate.client.ui.generated.resources.date_formatting_december
import logdate.client.ui.generated.resources.date_formatting_february
import logdate.client.ui.generated.resources.date_formatting_friday
import logdate.client.ui.generated.resources.date_formatting_january
import logdate.client.ui.generated.resources.date_formatting_july
import logdate.client.ui.generated.resources.date_formatting_june
import logdate.client.ui.generated.resources.date_formatting_last_weekday
import logdate.client.ui.generated.resources.date_formatting_march
import logdate.client.ui.generated.resources.date_formatting_may
import logdate.client.ui.generated.resources.date_formatting_monday
import logdate.client.ui.generated.resources.date_formatting_month_day
import logdate.client.ui.generated.resources.date_formatting_month_day_year
import logdate.client.ui.generated.resources.date_formatting_november
import logdate.client.ui.generated.resources.date_formatting_october
import logdate.client.ui.generated.resources.date_formatting_saturday
import logdate.client.ui.generated.resources.date_formatting_september
import logdate.client.ui.generated.resources.date_formatting_sunday
import logdate.client.ui.generated.resources.date_formatting_thursday
import logdate.client.ui.generated.resources.date_formatting_today
import logdate.client.ui.generated.resources.date_formatting_tuesday
import logdate.client.ui.generated.resources.date_formatting_wednesday
import logdate.client.ui.generated.resources.date_formatting_yesterday
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Returns a relative date string for this [LocalDate].
 *
 * This function assumes that the date is in the current time zone, using the following rules to
 * produce a formatted a local date:
 * - If the date is today, return "Today"
 * - If the date is yesterday, return "Yesterday"
 * - If the date is within the last week, return the day of the week (e.g. "last Tuesday")
 * - If the date is after the last week but otherwise within the current year, return the month and day (e.g. "May 12")
 * - If the date is in a different year, return the full date (e.g. "May 12, 2021")
 */
@Composable
fun LocalDate.asRelativeDate(): String = asRelativeDate(Clock.System.todayIn(TimeZone.currentSystemDefault()))

/**
 * Returns a relative date string for this [LocalDate], measured against [today].
 *
 * Taking today as an argument is what makes this testable and what makes a screenshot of a recent
 * date reproducible. Reading the clock inside means "Yesterday" becomes "May 4" the following day,
 * and any recorded image of it is wrong from then on.
 */
@Composable
fun LocalDate.asRelativeDate(today: LocalDate): String {
    when {
        this == today -> return stringResource(Res.string.date_formatting_today)
        this == today.minus(1, DateTimeUnit.DAY) -> return stringResource(Res.string.date_formatting_yesterday)
    }

    val weekAgo = today.minus(7, DateTimeUnit.DAY)

    return when {
        this > weekAgo -> {
            val dayName = stringResource(dayOfWeek.nameResource())
            if (this.dayOfWeek > today.dayOfWeek) stringResource(Res.string.date_formatting_last_weekday, dayName) else dayName
        }

        this.year == today.year -> {
            stringResource(Res.string.date_formatting_month_day, stringResource(month.nameResource()), day)
        }

        else -> {
            stringResource(Res.string.date_formatting_month_day_year, stringResource(month.nameResource()), day, year)
        }
    }
}

private fun Month.nameResource(): StringResource =
    when (this) {
        Month.JANUARY -> Res.string.date_formatting_january
        Month.FEBRUARY -> Res.string.date_formatting_february
        Month.MARCH -> Res.string.date_formatting_march
        Month.APRIL -> Res.string.date_formatting_april
        Month.MAY -> Res.string.date_formatting_may
        Month.JUNE -> Res.string.date_formatting_june
        Month.JULY -> Res.string.date_formatting_july
        Month.AUGUST -> Res.string.date_formatting_august
        Month.SEPTEMBER -> Res.string.date_formatting_september
        Month.OCTOBER -> Res.string.date_formatting_october
        Month.NOVEMBER -> Res.string.date_formatting_november
        Month.DECEMBER -> Res.string.date_formatting_december
    }

private fun DayOfWeek.nameResource(): StringResource =
    when (this) {
        DayOfWeek.MONDAY -> Res.string.date_formatting_monday
        DayOfWeek.TUESDAY -> Res.string.date_formatting_tuesday
        DayOfWeek.WEDNESDAY -> Res.string.date_formatting_wednesday
        DayOfWeek.THURSDAY -> Res.string.date_formatting_thursday
        DayOfWeek.FRIDAY -> Res.string.date_formatting_friday
        DayOfWeek.SATURDAY -> Res.string.date_formatting_saturday
        DayOfWeek.SUNDAY -> Res.string.date_formatting_sunday
    }
