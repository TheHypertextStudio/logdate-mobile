package app.logdate.wear.v2.today

import app.cash.turbine.test
import app.logdate.client.domain.streak.FirePhase
import app.logdate.client.domain.streak.ObserveCampfireUseCase
import app.logdate.client.repository.journals.JournalNotesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Runs the shared campfire use case with the watch's adapters: an always-on streak switch, and the
 * phone's day-start hour, or 4 AM when the phone has not shared one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchCampfireTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private val noon = at(hour = 12)
    private val notes = mockk<JournalNotesRepository>()

    @Test
    fun `something saved today lights the fire`() =
        runTest {
            every { notes.observeEntryTimestamps() } returns flowOf(listOf(at(hour = 10)))

            campfire().invoke().test {
                val fire = awaitItem()
                assertEquals(FirePhase.BURNING, fire?.phase)
                assertTrue(fire!!.loggedToday)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `without a day start from the phone, 3 AM counts toward yesterday`() =
        runTest {
            every { notes.observeEntryTimestamps() } returns flowOf(listOf(at(hour = 3)))

            campfire(phoneDayStartHour = null).invoke().test {
                assertFalse(awaitItem()!!.loggedToday)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the phone's day start moves the boundary`() =
        runTest {
            every { notes.observeEntryTimestamps() } returns flowOf(listOf(at(hour = 3)))

            campfire(phoneDayStartHour = 2).invoke().test {
                assertTrue(awaitItem()!!.loggedToday)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the watch never changes the streak setting`() =
        runTest {
            assertFailsWith<UnsupportedOperationException> { WatchStreakSettings().setStreakEnabled(false) }
        }

    private fun TestScope.campfire(phoneDayStartHour: Int? = null) =
        ObserveCampfireUseCase(
            notesRepository = notes,
            streakSettingsRepository = WatchStreakSettings(),
            dayBoundaryPreferences = WatchDayBoundaryPreferences { phoneDayStartHour },
            clock = FixedClock(noon),
            timeZone = { zone },
            computeDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private fun at(hour: Int): Instant = LocalDateTime(2026, 10, 3, hour, 0).toInstant(zone)

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }
}
