package app.logdate.wear.v2.memories

import app.cash.turbine.test
import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.v2.today.DayClock
import app.logdate.wear.v2.today.TodayWindow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesViewModelTest {
    private val zone = TimeZone.UTC
    private val today =
        TodayWindow(
            date = LocalDate(2026, 10, 10),
            timeZone = zone,
            start = LocalDate(2026, 10, 10).atStartOfDayIn(zone),
            end = LocalDate(2026, 10, 11).atStartOfDayIn(zone),
        )
    private val dayClock = mockk<DayClock> { every { observe() } returns flowOf(today) }
    private val finder = mockk<OnThisDayFinder>()
    private val rewindSource = mockk<WeeklyRewindSource>()
    private val at = Instant.fromEpochMilliseconds(1_760_000_000_000)
    private val note = JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "Beach day")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { finder.observe(any()) } answers { flow { emit(finder.find(firstArg())) } }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shared memory choices hide existing cards immediately`() =
        runTest {
            val onThisDay = OnThisDay(LocalDate(2025, 10, 10), 1, note, 1, "Beach day")
            coEvery { finder.find(today.date) } returns onThisDay
            every { rewindSource.observe(today) } returns flowOf(WeeklyRewind("A week", note))
            val selection =
                MutableStateFlow(
                    app.logdate.client.domain.watch
                        .WatchWindowPreferences(),
                )
            MemoriesViewModel(dayClock, finder, rewindSource, selection).cards.test {
                assertEquals(note, expectMostRecentItem().rewind?.leadMoment)
                selection.value = selection.value.copy(onThisDay = false, rewind = false)
                assertEquals(MemoryCards(), awaitItem())
            }
        }

    @Test
    fun `open cards follow edited deleted and newly arrived recalled moments`() =
        runTest {
            val notes = MutableStateFlow<List<JournalNote>>(listOf(note))
            val repository = mockk<app.logdate.client.repository.journals.JournalNotesRepository>()
            every { repository.observeEntryTimestamps() } returns notes.map { current -> current.map(JournalNote::creationTimestamp) }
            every { repository.observeNotesForDay(any()) } returns notes
            val lastYear = LocalDate(2025, 10, 10)
            val liveFinder =
                OnThisDayFinder(recall = {
                    notes.value.firstOrNull()?.let {
                        app.logdate.client.domain.recommendation
                            .MemoryRecallData(lastYear, (it as JournalNote.Text).content)
                    }
                }, notesRepository = repository)
            every { rewindSource.observe(today) } returns flowOf(null)
            MemoriesViewModel(dayClock, liveFinder, rewindSource).cards.test {
                assertEquals(note, expectMostRecentItem().onThisDay?.leadMoment)
                val edited = note.copy(content = "Edited beach day")
                notes.value = listOf(edited)
                assertEquals(edited, awaitItem().onThisDay?.leadMoment)
                notes.value = emptyList()
                assertEquals(null, awaitItem().onThisDay)
                notes.value = listOf(note)
                assertEquals(note, awaitItem().onThisDay?.leadMoment)
            }
        }

    @Test
    fun `both cards come from today's date`() =
        runTest {
            val onThisDay = OnThisDay(LocalDate(2025, 10, 10), yearsAgo = 1, leadMoment = note, momentCount = 1, summary = "Beach day")
            val rewind = WeeklyRewind("A quiet week", note)
            coEvery { finder.find(today.date) } returns onThisDay
            every { rewindSource.observe(today) } returns flowOf(rewind)

            MemoriesViewModel(dayClock, finder, rewindSource).cards.test {
                assertEquals(MemoryCards(onThisDay, rewind), expectMostRecentItem())
            }
        }

    @Test
    fun `a failure shows no cards`() =
        runTest {
            coEvery { finder.find(any()) } throws IOException("database closed")
            every { rewindSource.observe(any()) } returns flowOf(null)

            MemoriesViewModel(dayClock, finder, rewindSource).cards.test {
                assertEquals(MemoryCards(), expectMostRecentItem())
            }
        }
}
