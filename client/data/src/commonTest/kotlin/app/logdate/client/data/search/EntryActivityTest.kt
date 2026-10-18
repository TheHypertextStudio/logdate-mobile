package app.logdate.client.data.search

import app.logdate.client.repository.search.SearchEntryActivity
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class EntryActivityTest {
    private val utc = TimeZone.UTC

    @Test
    fun `no entries is no activity`() {
        assertEquals(SearchEntryActivity(0, 0, null, null, emptyMap()), summarizeEntryTimes(emptyList(), utc))
    }

    @Test
    fun `activity counts entries and days and finds the first and latest entry and entries by year`() {
        val times =
            listOf(
                Instant.parse("2025-07-19T15:00:00Z"),
                Instant.parse("2025-07-19T18:00:00Z"),
                Instant.parse("2024-06-01T12:00:00Z"),
                Instant.parse("2025-01-10T09:00:00Z"),
            )

        assertEquals(
            SearchEntryActivity(
                entryCount = 4,
                dayCount = 3,
                first = Instant.parse("2024-06-01T12:00:00Z"),
                latest = Instant.parse("2025-07-19T18:00:00Z"),
                byYear = mapOf(2024 to 1, 2025 to 3),
            ),
            summarizeEntryTimes(times, utc),
        )
    }

    @Test
    fun `days and years follow the local time zone`() {
        val times = listOf(Instant.parse("2025-01-01T03:00:00Z"), Instant.parse("2025-01-01T20:00:00Z"))

        val activity = summarizeEntryTimes(times, TimeZone.of("America/Los_Angeles"))

        assertEquals(2, activity.dayCount)
        assertEquals(mapOf(2024 to 1, 2025 to 1), activity.byYear)
    }
}
