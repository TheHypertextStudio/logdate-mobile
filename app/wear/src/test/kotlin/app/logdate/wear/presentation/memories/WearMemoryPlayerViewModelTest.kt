package app.logdate.wear.presentation.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.playback.AudioOutputState
import app.logdate.wear.playback.FakeEngine
import app.logdate.wear.playback.FakeOutputs
import app.logdate.wear.playback.FakeResolver
import app.logdate.wear.playback.WearVoiceNotePlayer
import app.logdate.wear.presentation.timeline.WearPlaybackUiState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [WearMemoryPlayerViewModel], which opens one voice memory, starts playing it, and offers
 * play or pause and 10 second skips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearMemoryPlayerViewModelTest {
    private val engine = FakeEngine()
    private val outputs = FakeOutputs()
    private val resolver = FakeResolver()
    private val notesRepository = mockk<JournalNotesRepository>()
    private val viewModelStore = ViewModelStore()
    private val note =
        JournalNote.Audio(
            uid = Uuid.random(),
            creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_000_000),
            lastUpdated = Instant.fromEpochMilliseconds(1_710_000_000_000),
            mediaRef = "/phone/memory.m4a",
            durationMs = 60_000,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { notesRepository.getNoteById(note.uid) } returns note
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    /** Created through a [ViewModelProvider] so clearing [viewModelStore] runs the real `onCleared`. */
    private fun viewModel(): WearMemoryPlayerViewModel {
        val viewModel =
            WearMemoryPlayerViewModel(
                notesRepository = notesRepository,
                playerFactory = { scope -> WearVoiceNotePlayer(scope, engine, outputs, resolver) },
            )
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
            }
        return ViewModelProvider(viewModelStore, factory)["player-${Uuid.random()}", WearMemoryPlayerViewModel::class.java]
    }

    private fun openedViewModel(noteId: Uuid = note.uid): WearMemoryPlayerViewModel = viewModel().also { it.open(noteId) }

    @Test
    fun `nothing is shown or played until a memory is opened`() =
        runTest {
            val viewModel = viewModel()

            assertFalse(viewModel.uiState.value.isLoaded)
            assertTrue(engine.started.isEmpty())
        }

    @Test
    fun `opening a memory shows it and starts playing`() =
        runTest {
            val viewModel = openedViewModel()

            val state = viewModel.uiState.value
            assertTrue(state.isLoaded)
            assertEquals(note.uid, state.memory?.noteId)
            assertEquals(60_000L, state.memory?.durationMs)
            assertIs<WearPlaybackUiState.Active>(state.playback)
            assertEquals(listOf("/watch/${note.uid}.m4a"), engine.started)
        }

    @Test
    fun `a memory that no longer exists is reported and nothing plays`() =
        runTest {
            val missing = Uuid.random()
            coEvery { notesRepository.getNoteById(missing) } returns null

            val state = openedViewModel(missing).uiState.value

            assertTrue(state.isLoaded)
            assertNull(state.memory)
            assertTrue(engine.started.isEmpty())
        }

    @Test
    fun `a note that is not a recording is treated as missing`() =
        runTest {
            val text = Uuid.random()
            coEvery { notesRepository.getNoteById(text) } returns
                JournalNote.Text(
                    uid = text,
                    creationTimestamp = note.creationTimestamp,
                    lastUpdated = note.lastUpdated,
                    content = "not audio",
                )

            assertNull(openedViewModel(text).uiState.value.memory)
        }

    @Test
    fun `play pause pauses a playing memory and resumes it`() =
        runTest {
            val viewModel = openedViewModel()

            viewModel.onPlayPause()
            assertTrue(assertIs<WearPlaybackUiState.Active>(viewModel.uiState.value.playback).isPaused)

            viewModel.onPlayPause()
            assertFalse(assertIs<WearPlaybackUiState.Active>(viewModel.uiState.value.playback).isPaused)
        }

    @Test
    fun `play pause replays a memory that finished`() =
        runTest {
            val viewModel = openedViewModel()
            engine.complete()
            assertEquals(WearPlaybackUiState.Idle, viewModel.uiState.value.playback)

            viewModel.onPlayPause()

            assertEquals(2, engine.started.size)
            assertIs<WearPlaybackUiState.Active>(viewModel.uiState.value.playback)
        }

    @Test
    fun `play pause tries again after a failure`() =
        runTest {
            resolver.failure = IllegalStateException("phone is away")
            val viewModel = openedViewModel()
            assertEquals(WearPlaybackUiState.Error(note.uid), viewModel.uiState.value.playback)

            resolver.failure = null
            viewModel.onPlayPause()

            assertIs<WearPlaybackUiState.Active>(viewModel.uiState.value.playback)
        }

    @Test
    fun `skipping moves the position ten seconds`() =
        runTest {
            val viewModel = openedViewModel()
            engine.reportProgress(0.5f)

            viewModel.onSkipBack()
            assertEquals(0.5f - 10_000f / 60_000f, assertIs<WearPlaybackUiState.Active>(viewModel.uiState.value.playback).progress)

            viewModel.onSkipForward()
            assertEquals(0.5f, assertIs<WearPlaybackUiState.Active>(viewModel.uiState.value.playback).progress, absoluteTolerance = 0.0001f)
        }

    @Test
    fun `the output state is shown so a missing speaker can be explained`() =
        runTest {
            val viewModel = openedViewModel()

            outputs.state.value = AudioOutputState.BluetoothOnly

            assertEquals(AudioOutputState.BluetoothOnly, viewModel.uiState.value.output)
        }

    @Test
    fun `closing the player stops playback and clears the screen`() =
        runTest {
            val viewModel = openedViewModel()

            viewModel.close()

            assertEquals(1, engine.stops)
            assertFalse(viewModel.uiState.value.isLoaded)
            assertEquals(WearPlaybackUiState.Idle, viewModel.uiState.value.playback)
        }

    @Test
    fun `opening another memory stops the first`() =
        runTest {
            val other =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = note.creationTimestamp,
                    lastUpdated = note.lastUpdated,
                    mediaRef = "/phone/other.m4a",
                    durationMs = 5_000,
                )
            coEvery { notesRepository.getNoteById(other.uid) } returns other
            val viewModel = openedViewModel()

            viewModel.open(other.uid)

            assertEquals(1, engine.stops)
            assertEquals(
                other.uid,
                viewModel.uiState.value.memory
                    ?.noteId,
            )
            assertEquals(2, engine.started.size)
        }

    @Test
    fun `clearing the screen stops playback`() =
        runTest {
            openedViewModel()

            viewModelStore.clear()

            assertEquals(1, engine.stops)
        }
}
