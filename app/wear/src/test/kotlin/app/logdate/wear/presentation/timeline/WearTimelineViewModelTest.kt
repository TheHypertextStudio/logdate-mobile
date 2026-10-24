package app.logdate.wear.presentation.timeline

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.capture.Feeling
import app.logdate.wear.playback.AudioOutputState
import app.logdate.wear.playback.FakeEngine
import app.logdate.wear.playback.FakeOutputs
import app.logdate.wear.playback.FakeResolver
import app.logdate.wear.playback.WearVoiceNotePlayer
import app.logdate.wear.sync.WearDataLayerClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Comprehensive business logic tests for the Wear OS Timeline experience.
 *
 * This suite validates the [WearTimelineViewModel], covering the end-to-end lifecycle
 * of journal note presentation on Wear. Key responsibilities tested include:
 * - Grouping and sorting notes into a chronological day-based hierarchy.
 * - Extracting and summarizing mood data and preview text for the timeline list.
 * - Toggling playback of a day's voice notes through the shared [WearVoiceNotePlayer], which owns
 *   the output check, phone fetch, and suppression handling.
 * - Triggering phone-side sync requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearTimelineViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val engine = FakeEngine()
    private val outputs = FakeOutputs()
    private val resolver = FakeResolver()
    private lateinit var mockDataLayerClient: WearDataLayerClient

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockDataLayerClient = mockk(relaxed = true)
        coEvery { mockDataLayerClient.sendMessage(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fixedTime = Instant.fromEpochMilliseconds(1_710_000_000_000) // 2024-03-09

    private fun createNote(
        type: String = "text",
        content: String = "test",
        hoursOffset: Int = 0,
    ): JournalNote {
        val timestamp =
            Instant.fromEpochMilliseconds(
                fixedTime.toEpochMilliseconds() + hoursOffset * 3_600_000L,
            )
        return when (type) {
            "audio" -> {
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = timestamp,
                    lastUpdated = timestamp,
                    mediaRef = "/recording.aac",
                    durationMs = 4200,
                )
            }

            else -> {
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = timestamp,
                    lastUpdated = timestamp,
                    content = content,
                )
            }
        }
    }

    private fun createViewModel(notes: List<JournalNote> = emptyList()): WearTimelineViewModel {
        val repository = mockk<JournalNotesRepository>()
        every { repository.observeRecentNotes(any()) } returns flowOf(notes)
        every { repository.observeNotesForDay(any()) } returns flowOf(emptyList())
        return createViewModelFor(repository)
    }

    private fun createViewModelFor(repository: JournalNotesRepository): WearTimelineViewModel =
        WearTimelineViewModel(
            notesRepository = repository,
            playerFactory = { scope -> WearVoiceNotePlayer(scope, engine, outputs, resolver) },
            dataLayerClient = mockDataLayerClient,
        )

    // =======================================================================
    // Initial state
    // =======================================================================

    @Test
    fun `initial state shows loading`() =
        runTest {
            val repository = mockk<JournalNotesRepository>()
            every { repository.observeRecentNotes(any()) } returns MutableStateFlow(emptyList())
            val vm =
                createViewModelFor(repository)

            val state = vm.uiState.first()
            assertTrue(state.days.isEmpty())
        }

    // =======================================================================
    // Day grouping
    // =======================================================================

    @Test
    fun `groups notes by day`() =
        runTest {
            val today = createNote(content = "Today's note", hoursOffset = 0)
            val yesterday = createNote(content = "Yesterday's note", hoursOffset = -24)

            val vm = createViewModel(listOf(today, yesterday))
            val state = vm.uiState.first()

            assertEquals(2, state.days.size)
        }

    @Test
    fun `multiple notes on same day grouped together`() =
        runTest {
            val note1 = createNote(content = "Morning", hoursOffset = 0)
            val note2 = createNote(content = "Afternoon", hoursOffset = 3)
            val note3 = createNote(content = "Evening", hoursOffset = 6)

            val vm = createViewModel(listOf(note1, note2, note3))
            val state = vm.uiState.first()

            assertEquals(1, state.days.size)
            assertEquals(3, state.days[0].entryCount)
        }

    @Test
    fun `days are sorted most recent first`() =
        runTest {
            val olderNote = createNote(content = "Old", hoursOffset = -48)
            val newerNote = createNote(content = "New", hoursOffset = 0)

            val vm = createViewModel(listOf(olderNote, newerNote))
            val state = vm.uiState.first()

            assertEquals(2, state.days.size)
            assertTrue(state.days[0].date > state.days[1].date)
        }

    // =======================================================================
    // Entry counts
    // =======================================================================

    @Test
    fun `entry count reflects note count per day`() =
        runTest {
            val notes =
                (0..4).map { i ->
                    createNote(content = "Note $i", hoursOffset = i)
                }

            val vm = createViewModel(notes)
            val state = vm.uiState.first()

            assertEquals(1, state.days.size)
            assertEquals(5, state.days[0].entryCount)
        }

    // =======================================================================
    // Mood extraction
    // =======================================================================

    @Test
    fun `extracts mood from mood-tagged note`() =
        runTest {
            val moodNote = createNote(content = "#mood:good Feeling great")

            val vm = createViewModel(listOf(moodNote))
            val state = vm.uiState.first()

            assertEquals(Feeling.GOOD, state.days[0].feeling)
        }

    @Test
    fun `the day's mood comes from its newest check-in`() =
        runTest {
            val older = createNote(content = "#mood:sad Feeling sad", hoursOffset = 0)
            val newer = createNote(content = "#mood:great Feeling great", hoursOffset = 1)

            val vm = createViewModel(listOf(older, newer))
            val state = vm.uiState.first()

            assertEquals(Feeling.GREAT, state.days[0].feeling)
        }

    @Test
    fun `null mood when no mood notes exist`() =
        runTest {
            val plainNote = createNote(content = "Just a regular note")

            val vm = createViewModel(listOf(plainNote))
            val state = vm.uiState.first()

            assertNull(state.days[0].feeling)
        }

    @Test
    fun `a mood check-in is never the day's preview text`() =
        runTest {
            val checkIn = createNote(content = "#mood:good Feeling good", hoursOffset = 1)
            val note = createNote(content = "Lunch by the water", hoursOffset = 0)

            val vm = createViewModel(listOf(checkIn, note))
            val state = vm.uiState.first()

            assertEquals("Lunch by the water", state.days[0].previewText)
        }

    // =======================================================================
    // Preview text
    // =======================================================================

    @Test
    fun `preview text from first text note`() =
        runTest {
            val note = createNote(content = "Had a great day today")

            val vm = createViewModel(listOf(note))
            val state = vm.uiState.first()

            assertEquals("Had a great day today", state.days[0].previewText)
        }

    @Test
    fun `preview text truncated to 50 chars`() =
        runTest {
            val longContent = "A".repeat(100)
            val note = createNote(content = longContent)

            val vm = createViewModel(listOf(note))
            val state = vm.uiState.first()

            assertTrue(state.days[0].previewText!!.length <= 50)
        }

    @Test
    fun `no preview text for audio-only day`() =
        runTest {
            val audioNote = createNote(type = "audio")

            val vm = createViewModel(listOf(audioNote))
            val state = vm.uiState.first()

            assertNull(state.days[0].previewText)
        }

    // =======================================================================
    // Empty state
    // =======================================================================

    @Test
    fun `empty state when no notes exist`() =
        runTest {
            val vm = createViewModel(emptyList())
            val state = vm.uiState.first()

            assertTrue(state.days.isEmpty())
            assertFalse(state.isLoading)
        }

    // =======================================================================
    // Day selection for detail view
    // =======================================================================

    @Test
    fun `selectDay loads notes for that day`() =
        runTest {
            val repository = mockk<JournalNotesRepository>()
            val targetDate = LocalDate(2024, 3, 9)
            val dayNotes =
                listOf(
                    createNote(content = "Morning entry"),
                    createNote(type = "audio"),
                )
            every { repository.observeRecentNotes(any()) } returns
                flowOf(
                    listOf(createNote(content = "test")),
                )
            every { repository.observeNotesForDay(targetDate) } returns flowOf(dayNotes)

            val vm =
                createViewModelFor(repository)
            vm.selectDay(targetDate)

            val detail = vm.selectedDayState.first()
            assertEquals(targetDate, detail?.date)
            assertEquals(2, detail?.entries?.size)
        }

    @Test
    fun `clearSelection resets selected day`() =
        runTest {
            val repository = mockk<JournalNotesRepository>()
            val targetDate = LocalDate(2024, 3, 9)
            every { repository.observeRecentNotes(any()) } returns flowOf(emptyList())
            every { repository.observeNotesForDay(targetDate) } returns flowOf(emptyList())

            val vm =
                createViewModelFor(repository)
            vm.selectDay(targetDate)
            vm.clearSelection()

            val detail = vm.selectedDayState.first()
            assertNull(detail)
        }

    // =======================================================================
    // Audio playback
    // =======================================================================

    @Test
    fun `toggleNote starts playback for audio note`() =
        runTest {
            val audioNote = createNote(type = "audio") as JournalNote.Audio
            val vm = createViewModel()

            vm.toggleNote(audioNote)

            val state = vm.playbackState.first()
            assertTrue(state is WearPlaybackUiState.Active)
            assertEquals(audioNote.uid, state.noteId)
            assertEquals(listOf("/watch/${audioNote.uid}.m4a"), engine.started)
        }

    @Test
    fun `toggleNote stops playback when same note is active`() =
        runTest {
            val audioNote = createNote(type = "audio") as JournalNote.Audio
            val vm = createViewModel()

            vm.toggleNote(audioNote)
            vm.toggleNote(audioNote)

            assertEquals(WearPlaybackUiState.Idle, vm.playbackState.first())
            assertEquals(1, engine.stops)
        }

    @Test
    fun `toggleNote does not start playback when output unavailable`() =
        runTest {
            outputs.state.value = AudioOutputState.Unavailable
            val audioNote = createNote(type = "audio") as JournalNote.Audio
            val vm = createViewModel()

            vm.toggleNote(audioNote)

            assertEquals(WearPlaybackUiState.BlockedOutput(audioNote.uid), vm.playbackState.first())
            assertTrue(engine.started.isEmpty())
        }

    @Test
    fun `toggleNote switches to new note when different note is active`() =
        runTest {
            val note1 = createNote(type = "audio") as JournalNote.Audio
            val note2 = createNote(type = "audio") as JournalNote.Audio
            val vm = createViewModel()

            vm.toggleNote(note1)
            vm.toggleNote(note2)

            val state = vm.playbackState.first()
            assertTrue(state is WearPlaybackUiState.Active)
            assertEquals(note2.uid, state.noteId)
            assertEquals(2, engine.started.size)
            assertEquals(1, engine.stops)
        }

    @Test
    fun `stopPlayback resets to idle`() =
        runTest {
            val audioNote = createNote(type = "audio") as JournalNote.Audio
            val vm = createViewModel()

            vm.toggleNote(audioNote)
            vm.stopPlayback()

            assertEquals(WearPlaybackUiState.Idle, vm.playbackState.first())
            assertEquals(1, engine.stops)
        }

    @Test
    fun `initial playback state is Idle`() =
        runTest {
            val vm = createViewModel()
            val state = vm.playbackState.first()
            assertEquals(WearPlaybackUiState.Idle, state)
        }

    @Test
    fun `toggleNote shows error when synced audio cannot be resolved`() =
        runTest {
            val audioNote = createNote(type = "audio") as JournalNote.Audio
            resolver.failure = IllegalStateException("missing")
            val vm = createViewModel()

            vm.toggleNote(audioNote)

            assertEquals(WearPlaybackUiState.Error(audioNote.uid), vm.playbackState.first())
            assertTrue(engine.started.isEmpty())
        }

    @Test
    fun `playback suppression moves state to blocked output`() =
        runTest {
            val audioNote = createNote(type = "audio") as JournalNote.Audio
            val vm = createViewModel()

            vm.toggleNote(audioNote)
            engine.suppressed.value = true

            assertEquals(WearPlaybackUiState.BlockedOutput(audioNote.uid), vm.playbackState.first())
            assertEquals(1, engine.stops)
        }

    @Test
    fun `the output state is the player's`() =
        runTest {
            val vm = createViewModel()

            outputs.state.value = AudioOutputState.SpeakerAndBluetooth
            assertEquals(AudioOutputState.SpeakerAndBluetooth, vm.audioOutputState.first())
        }

    @Test
    fun `openBluetoothSettings opens the system settings`() =
        runTest {
            val vm = createViewModel()

            vm.openBluetoothSettings()

            assertEquals(1, outputs.bluetoothSettingsOpened)
        }

    @Test
    fun `timeline requests phone sync on init`() =
        runTest {
            createViewModel()

            coVerify { mockDataLayerClient.sendMessage("/logdate/sync/request", any()) }
        }
}
