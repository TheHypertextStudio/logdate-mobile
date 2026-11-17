package app.logdate.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests for the date and time formatting utilities.
 *
 * These tests verify that all formatting functions work correctly across platforms,
 * handling different locales, time zones, and edge cases appropriately.
 */
class DateTimeFormattingTest {
    /**
     * Tests [formatDateLocalized] for correct localized date formatting.
     *
     * Verifies that:
     * - The formatted string is not empty or null
     * - The year appears in the formatted output
     * - The month appears either as a number or name
     *
     * Since actual formatting depends on platform locale settings,
     * we can't test for exact output format.
     */
    @Test
    fun `format date localized`() {
        val testDate = LocalDate(2025, 6, 5)
        val formattedDate = formatDateLocalized(testDate)

        assertNotNull(formattedDate)
        assertTrue(formattedDate.isNotEmpty(), "Formatted date should not be empty")

        // The date should contain the year
        assertTrue(formattedDate.contains("2025"), "Formatted date should contain the year 2025")

        // The date should contain the month (either as number or name)
        assertTrue(
            formattedDate.contains("6") ||
                formattedDate.lowercase().contains("june") ||
                formattedDate.lowercase().contains("jun"),
            "Formatted date should contain the month (June/Jun or 6)",
        )
    }

    /**
     * Tests the [Instant.asTime] extension property.
     *
     * Verifies that:
     * - The formatted time string is not empty or null
     * - It contains a time separator (usually ":")
     *
     * The exact formatting depends on platform locale settings.
     */
    @Test
    fun `instant as time`() {
        val now = Clock.System.now()
        val timeString = now.asTime

        assertNotNull(timeString)
        assertTrue(timeString.isNotEmpty(), "Time string should not be empty")

        // Should contain either colon (for 12-hour format) or some time separator
        assertTrue(timeString.contains(":"), "Time string should contain a time separator")
    }

    /**
     * Tests [LocalDate.now] extension function.
     *
     * Verifies that the date returned by LocalDate.now() matches
     * the current system date in terms of year, month, and day.
     */
    @Test
    fun `local date now`() {
        val today = LocalDate.now()
        val systemNow =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

        assertEquals(systemNow.year, today.year, "Year should match system time")
        assertEquals(systemNow.month, today.month, "Month should match system time")
        assertEquals(systemNow.day, today.day, "Day should match system time")
    }

    /**
     * Tests [LocalDate.toReadableDateShort] extension function.
     *
     * Verifies that:
     * - The formatted date contains the month name
     * - The formatted date contains the day number
     * - The year is only included if it's different from the current year
     *
     * This ensures consistent human-readable date formatting.
     */
    @Test
    fun `to readable date short`() {
        val date = LocalDate(2025, 3, 15)
        val result = date.toReadableDateShort()

        assertTrue(result.contains("March"), "Should contain month name")
        assertTrue(result.contains("15"), "Should contain day number")

        // Current year shouldn't include the year number
        val currentYear =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .year
        if (date.year == currentYear) {
            assertTrue(!result.contains("2025"), "Current year dates shouldn't show year")
        } else {
            assertTrue(result.contains("2025"), "Non-current year dates should show year")
        }
    }

    /**
     * Tests [Instant.toReadableDateAllDay].
     *
     * An all-day event is anchored to UTC midnight of its date. This reads the date back in UTC
     * and renders it date-only — no clock time. This is the regression guard for the bug where an
     * all-day event for a given date showed up as "5 p.m. the previous day": that happened because
     * a UTC-midnight instant was formatted in a behind-UTC local zone. Reading in UTC keeps the
     * date stable and drops the meaningless time.
     */
    @Test
    fun `to readable date all day renders utc date without time`() {
        val allDayInstant = LocalDate(2026, 6, 17).atStartOfDayIn(TimeZone.UTC)

        val result = allDayInstant.toReadableDateAllDay()

        // Same date the calendar shows — not the previous day — and identical to formatting the
        // bare date, so year-omission stays consistent with the rest of the app.
        assertEquals(LocalDate(2026, 6, 17).toReadableDateShort(), result)
        assertTrue(result.contains("17"), "Should keep the event's own day, not roll back to the 16th")
        assertFalse(result.contains(":"), "All-day output must not include a time of day")
    }

    /**
     * Tests [Instant.toReadableDateTimeRangeShort] with `isAllDay = true`.
     *
     * The all-day path collapses to the start date alone and ignores the end instant (calendar
     * sources disagree on whether an all-day end is inclusive or exclusive), while the timed path
     * still renders a time.
     */
    @Test
    fun `to readable date time range short all day collapses to date`() {
        val start = LocalDate(2026, 6, 17).atStartOfDayIn(TimeZone.UTC)
        val end = LocalDate(2026, 6, 19).atStartOfDayIn(TimeZone.UTC)

        val allDay = start.toReadableDateTimeRangeShort(end = end, isAllDay = true)
        assertEquals(start.toReadableDateAllDay(), allDay)
        assertFalse(allDay.contains(":"), "All-day range must not include a time of day")

        val timed = start.toReadableDateTimeRangeShort(end = end, isAllDay = false)
        assertTrue(timed.contains(":"), "Timed range should still render a time of day")
    }
}
