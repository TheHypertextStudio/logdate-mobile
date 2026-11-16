package app.logdate.wear.v2.flow

import app.cash.turbine.test
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.v2.today.DayClock
import app.logdate.wear.v2.today.TodayWindow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FlowViewModelTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private val saturday =
        TodayWindow(
            date = LocalDate(2026, 10, 3),
            timeZone = zone,
            start = LocalDate(2026, 10, 3).atStartOfDayIn(zone),
            end = LocalDate(2026, 10, 4).atStartOfDayIn(zone),
        )
    private val dayClock = mockk<DayClock> { every { observe() } returns flowOf(saturday) }
    private val notesRepository = mockk<JournalNotesRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the list shows the last 7 days and counts all retained older moments`() =
        runTest {
            val older = text("Lake walk", at(LocalDateTime(2026, 9, 21, 9, 0)))
            val monday = text("Monday walk", at(LocalDateTime(2026, 9, 28, 9, 0)))
            val friday = voice(at(LocalDateTime(2026, 10, 2, 20, 0)))
            stubWindow(older, monday, friday)

            FlowViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(FlowUiState.Ready(timeZone = zone, moments = listOf(friday, monday), earlierCount = 1), expectMostRecentItem())
            }
        }

    @Test
    fun `check-ins and photos are left out of both`() =
        runTest {
            val checkIn = text("#mood:ok Feeling ok", at(LocalDateTime(2026, 10, 3, 8, 0)))
            val photoTime = at(LocalDateTime(2026, 9, 22, 8, 0))
            val photo = JournalNote.Image(mediaRef = "p.jpg", creationTimestamp = photoTime, lastUpdated = photoTime)
            val lunch = text("Lunch", at(LocalDateTime(2026, 10, 3, 12, 0)))
            stubWindow(checkIn, photo, lunch)

            FlowViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(FlowUiState.Ready(timeZone = zone, moments = listOf(lunch), earlierCount = 0), expectMostRecentItem())
            }
        }

    @Test
    fun `retained older sources remain available through Earlier`() =
        runTest {
            val tooOld = text("Too old", at(LocalDateTime(2026, 9, 19, 23, 0)))
            val sunday = text("Sunday", at(LocalDateTime(2026, 9, 27, 10, 0)))
            stubWindow(tooOld, sunday)

            FlowViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(FlowUiState.Ready(timeZone = zone, moments = listOf(sunday), earlierCount = 1), expectMostRecentItem())
            }
        }

    @Test
    fun `a failure reading notes shows the failed state`() =
        runTest {
            every { notesRepository.observeNotesInRange(any(), any()) } returns flow { throw IOException("database closed") }

            FlowViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(FlowUiState.Failed, expectMostRecentItem())
            }
        }

    private fun stubWindow(vararg notes: JournalNote) {
        every { notesRepository.observeNotesInRange(any(), any()) } answers {
            val start = firstArg<Instant>()
            val end = secondArg<Instant>()
            flowOf(notes.filter { it.creationTimestamp >= start && it.creationTimestamp < end })
        }
    }

    private fun at(local: LocalDateTime): Instant = local.toInstant(zone)

    private fun text(
        content: String,
        at: Instant,
    ) = JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = content)

    private fun voice(at: Instant) = JournalNote.Audio(mediaRef = "v.m4a", durationMs = 30_000L, creationTimestamp = at, lastUpdated = at)
}
