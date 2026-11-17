package app.logdate.util

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Returns the current date in the system's default time zone.
 *
 * @param timeZone The time zone to use for the current date. Defaults to the system's default time zone.
 */
fun LocalDate.Companion.now(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Clock.System
        .now()
        .toLocalDateTime(timeZone)
        .date

fun LocalDateTime.toReadableDateShort(): String =
    format(
        LocalDateTime.Format {
            monthName(MonthNames.ENGLISH_FULL)
            char(' ')
            day(Padding.SPACE)
            if (year !=
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .year
            ) {
                // TODO: Add support for multiple locales
                char(',')
                char(' ')
                year()
            }
        },
    )

fun LocalDate.toReadableDateShort(): String =
    format(
        LocalDate.Format {
            monthName(MonthNames.ENGLISH_FULL)
            char(' ')
            day(Padding.SPACE)
            if (year !=
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .year
            ) {
                // TODO: Add support for multiple locales
                char(',')
                char(' ')
                year()
            }
        },
    )

/**
 * Converts an [Instant] into a short readable form
 *
 * For example, "March 13".
 */
fun Instant.toReadableDateShort(): String {
    val localDatetime = toLocalDateTime(TimeZone.currentSystemDefault())
    return localDatetime.toReadableDateShort()
}

/**
 * Converts an [Instant] into a short readable form with time
 *
 * For example, "March 13, 3:30 p.m.".
 */
fun Instant.toReadableDateTimeShort(): String {
    val localDatetime = toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDatetime.toReadableDateShort()}, $localTime"
}

/**
 * Converts an all-day [Instant] into a short readable **date** with no time of day.
 *
 * All-day events are anchored to UTC midnight of their date (see `Event.isAllDay`), so the date
 * is read back in [TimeZone.UTC]. Reading it in the local zone would shift the date in any
 * behind-UTC zone — the "5 p.m. the previous day" bug — and add a meaningless clock time.
 *
 * For example, "March 13".
 */
fun Instant.toReadableDateAllDay(): String = toLocalDateTime(TimeZone.UTC).date.toReadableDateShort()

/**
 * Formats a time range as a single short string. Returns the start timestamp alone when
 * the range collapses to a single moment (no end, or end equal to start). Otherwise returns
 * `"start – end"` separated by an en-dash.
 *
 * Both bounds are converted to the device's local time zone via [toReadableDateTimeShort].
 *
 * When [isAllDay] is true the event has no time of day: the result is the start date alone via
 * [toReadableDateAllDay]. The end is intentionally dropped — calendar sources disagree on whether
 * an all-day end instant is inclusive or exclusive, so showing the start date keeps the output
 * unambiguous and stable across time zones.
 */
fun Instant.toReadableDateTimeRangeShort(
    end: Instant?,
    isAllDay: Boolean = false,
): String {
    if (isAllDay) {
        return toReadableDateAllDay()
    }
    val startText = toReadableDateTimeShort()
    return if (end == null || end == this) {
        startText
    } else {
        "$startText – ${end.toReadableDateTimeShort()}"
    }
}

val Instant.localTime: String
    get() {
        val localDateTime = toLocalDateTime(TimeZone.currentSystemDefault()).time
        return localDateTime.format(
            LocalTime.Format {
                // TODO: Support 24-hour times
                amPmHour(padding = Padding.ZERO)
                char(':')
                minute()
                char(' ')
                amPmMarker("a.m.", "p.m.")
            },
        )
    }

/**
 * Returns a formatted time string for this [Instant] based on the current system time zone and locale.
 *
 * For example, "3:30 p.m."
 */
expect val Instant.asTime: String

/**
 * Formats a LocalDate according to the current locale's full date format.
 * For example: "June 23, 2025" in US locale or "23 juin 2025" in French locale.
 *
 * @param date The date to format
 * @return A localized date string in long format
 */
expect fun formatDateLocalized(date: LocalDate): String

/**
 * Returns the first day of the week according to the device's current locale.
 *
 * For example, Sunday in the US, Monday in most of Europe.
 */
expect fun getLocaleFirstDayOfWeek(): DayOfWeek
