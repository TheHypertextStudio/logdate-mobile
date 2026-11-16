package app.logdate.wear.v2.archive

import app.cash.turbine.test
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.v2.today.DayClock
import app.logdate.wear.v2.today.TodayWindow
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
class ArchiveViewModelTest {
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
    fun `earlier moments come a day at a time with the newest day first`() =
        runTest {
            val morning = voice(at(LocalDateTime(2026, 9, 21, 8, 0)))
            val evening = text("Dinner on the roof", at(LocalDateTime(2026, 9, 21, 20, 0)))
            val thursday = text("Long call", at(LocalDateTime(2026, 9, 24, 21, 0)))
            val checkIn = text("#mood:ok Fine", at(LocalDateTime(2026, 9, 24, 7, 0)))
            stubRange(listOf(morning, checkIn, evening, thursday))

            ArchiveViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(
                    ArchiveUiState.Ready(
                        timeZone = zone,
                        days =
                            listOf(
                                ArchiveDay(LocalDate(2026, 9, 24), listOf(thursday)),
                                ArchiveDay(LocalDate(2026, 9, 21), listOf(evening, morning)),
                            ),
                    ),
                    expectMostRecentItem(),
                )
            }
        }

    @Test
    fun `nothing earlier is an empty archive`() =
        runTest {
            stubRange(emptyList())

            ArchiveViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(ArchiveUiState.Ready(timeZone = zone, days = emptyList()), expectMostRecentItem())
            }
        }

    @Test
    fun `starred older moments remain accessible offline and follow favorite changes`() =
        runTest {
            val favorite = voice(at(LocalDateTime(2025, 9, 21, 8, 0)))
            val ordinary = text("Long call", at(LocalDateTime(2026, 9, 24, 21, 0)))
            val changes = MutableStateFlow(0)
            var starred = setOf(favorite.uid)
            stubRange(listOf(favorite, ordinary))

            ArchiveViewModel(dayClock, notesRepository, starred = { starred }, windowChanges = changes.map { }).uiState.test {
                val first = expectMostRecentItem() as ArchiveUiState.Ready
                assertEquals(listOf(favorite), first.starredMoments)
                assertEquals(listOf(ArchiveDay(LocalDate(2026, 9, 24), listOf(ordinary))), first.days)

                starred = emptySet()
                changes.value++
                val changed = awaitItem() as ArchiveUiState.Ready
                assertEquals(emptyList(), changed.starredMoments)
                assertEquals(2, changed.days.size)
            }
        }

    @Test
    fun `a failure reading notes shows the failed state`() =
        runTest {
            every { notesRepository.observeNotesInRange(any(), any()) } returns flow { throw IOException("database closed") }

            ArchiveViewModel(dayClock, notesRepository).uiState.test {
                assertEquals(ArchiveUiState.Failed, expectMostRecentItem())
            }
        }

    private fun stubRange(notes: List<JournalNote>) {
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
