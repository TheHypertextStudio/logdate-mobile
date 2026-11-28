package app.logdate.client.domain.timeline

import app.logdate.client.domain.location.history.LocationHistorySnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TimelineDayHistoryTest {
    private var now = Instant.parse("2026-11-28T08:00:00Z")
    private val reads = mutableListOf<LocalDate>()

    private fun history(read: suspend (LocalDate) -> LocationHistorySnapshot = { snapshot() }) =
        TimelineDayHistory(
            readDay = { date ->
                reads += date
                read(date)
            },
            freshFor = 5.minutes,
            now = { now },
        )

    @Test
    fun `rebuilding the timeline reuses each day's history instead of reconstructing it again`() =
        runTest {
            val history = history()

            val first = history.snapshotFor(MONDAY)
            repeat(20) { history.snapshotFor(MONDAY) }

            assertSame(first, history.snapshotFor(MONDAY))
            assertEquals(listOf(MONDAY), reads)
        }

    @Test
    fun `a day's history is read again once it is older than the freshness window`() =
        runTest {
            val history = history()

            history.snapshotFor(MONDAY)
            now += 4.minutes
            history.snapshotFor(MONDAY)
            now += 2.minutes
            history.snapshotFor(MONDAY)

            assertEquals(listOf(MONDAY, MONDAY), reads)
        }

    @Test
    fun `each day keeps its own history`() =
        runTest {
            val history = history()

            history.snapshotFor(MONDAY)
            history.snapshotFor(TUESDAY)
            history.snapshotFor(MONDAY)

            assertEquals(listOf(MONDAY, TUESDAY), reads)
        }

    @Test
    fun `a failed read gives no history and is not retried until the window passes`() =
        runTest {
            val history = history { error("database closed") }

            assertNull(history.snapshotFor(MONDAY))
            assertNull(history.snapshotFor(MONDAY))

            assertEquals(listOf(MONDAY), reads)
        }

    @Test
    fun `without location history there is nothing to read`() =
        runTest {
            assertNull(TimelineDayHistory(readDay = null).snapshotFor(MONDAY))
        }

    private fun snapshot() =
        LocationHistorySnapshot(
            items = emptyList(),
            places = emptyList(),
            notes = emptyList(),
            recordingDevices = emptyList(),
            selectedDeviceId = "phone",
            conflicts = emptyList(),
        )

    private companion object {
        val MONDAY = LocalDate(2026, 11, 23)
        val TUESDAY = LocalDate(2026, 11, 24)
    }
}
