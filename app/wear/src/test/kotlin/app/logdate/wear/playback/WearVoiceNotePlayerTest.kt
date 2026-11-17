package app.logdate.wear.playback

import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.presentation.timeline.WearPlaybackUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [WearVoiceNotePlayer]: resolving a voice note to something playable, starting it, and the
 * pause, resume and skip controls that the memory player screen offers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearVoiceNotePlayerTest {
    private val engine = FakeEngine()
    private val outputs = FakeOutputs()
    private val resolver = FakeResolver()
    private val note = audioNote(durationMs = 40_000)

    private fun TestScope.player() = WearVoiceNotePlayer(scope = backgroundScope, engine = engine, outputs = outputs, resolver = resolver)

    @Test
    fun `playing a note resolves it and starts the engine`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()

            player.play(note)

            assertEquals(listOf(note.uid), resolver.resolved)
            assertEquals(listOf("/watch/${note.uid}.m4a"), engine.started)
            val state = assertIs<WearPlaybackUiState.Active>(player.state.value)
            assertEquals(note.uid, state.noteId)
            assertEquals(40_000L, state.durationMs)
            assertEquals(false, state.isPaused)
        }

    @Test
    fun `state is preparing while the audio is being resolved`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Result<String>>()
            resolver.gate = gate
            val player = player()

            player.play(note)

            assertEquals(WearPlaybackUiState.Preparing(note.uid), player.state.value)
            gate.complete(Result.success("/watch/late.m4a"))
            runCurrent()
            assertIs<WearPlaybackUiState.Active>(player.state.value)
        }

    @Test
    fun `an audio file that cannot be resolved is reported for that note`() =
        runTest(UnconfinedTestDispatcher()) {
            resolver.failure = IllegalStateException("phone is away")
            val player = player()

            player.play(note)

            assertEquals(WearPlaybackUiState.Error(note.uid), player.state.value)
            assertTrue(engine.started.isEmpty())
        }

    @Test
    fun `playing with no audio output is blocked and does not start`() =
        runTest(UnconfinedTestDispatcher()) {
            outputs.state.value = AudioOutputState.Unavailable
            val player = player()

            player.play(note)

            assertEquals(WearPlaybackUiState.BlockedOutput(note.uid), player.state.value)
            assertTrue(resolver.resolved.isEmpty())
        }

    @Test
    fun `progress and completion follow the engine`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)

            engine.reportProgress(0.25f)
            assertEquals(0.25f, assertIs<WearPlaybackUiState.Active>(player.state.value).progress)

            engine.complete()
            assertEquals(WearPlaybackUiState.Idle, player.state.value)
        }

    @Test
    fun `pausing holds the position and resuming continues it`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)
            engine.reportProgress(0.5f)

            player.pauseOrResume()
            val paused = assertIs<WearPlaybackUiState.Active>(player.state.value)
            assertTrue(paused.isPaused)
            assertEquals(0.5f, paused.progress)
            assertEquals(1, engine.pauses)

            player.pauseOrResume()
            assertEquals(false, assertIs<WearPlaybackUiState.Active>(player.state.value).isPaused)
            assertEquals(1, engine.resumes)
        }

    @Test
    fun `progress reports while paused do not move the position`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)
            engine.reportProgress(0.5f)
            player.pauseOrResume()

            engine.reportProgress(0.9f)

            assertEquals(0.5f, assertIs<WearPlaybackUiState.Active>(player.state.value).progress)
        }

    @Test
    fun `skipping back moves the position by the requested time`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)
            engine.reportProgress(0.5f)

            player.seekBy(-10_000)

            assertEquals(0.25f, assertIs<WearPlaybackUiState.Active>(player.state.value).progress)
            assertEquals(listOf(0.25f), engine.seeks)
        }

    @Test
    fun `skipping is clamped to the start and end of the recording`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)
            engine.reportProgress(0.1f)

            player.seekBy(-10_000)
            assertEquals(0f, assertIs<WearPlaybackUiState.Active>(player.state.value).progress)

            engine.reportProgress(0.9f)
            player.seekBy(10_000)
            assertEquals(1f, assertIs<WearPlaybackUiState.Active>(player.state.value).progress)
        }

    @Test
    fun `skipping does nothing when the length is unknown`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(audioNote(durationMs = 0))

            player.seekBy(-10_000)

            assertTrue(engine.seeks.isEmpty())
        }

    @Test
    fun `controls do nothing when nothing is playing`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()

            player.pauseOrResume()
            player.seekBy(10_000)

            assertEquals(WearPlaybackUiState.Idle, player.state.value)
            assertEquals(0, engine.pauses)
            assertTrue(engine.seeks.isEmpty())
        }

    @Test
    fun `stopping ends playback and returns to idle`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)

            player.stop()

            assertEquals(WearPlaybackUiState.Idle, player.state.value)
            assertEquals(1, engine.stops)
        }

    @Test
    fun `stopping while preparing cancels the pending start`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Result<String>>()
            resolver.gate = gate
            val player = player()
            player.play(note)

            player.stop()
            gate.complete(Result.success("/watch/late.m4a"))
            runCurrent()

            assertEquals(WearPlaybackUiState.Idle, player.state.value)
            assertTrue(engine.started.isEmpty())
        }

    @Test
    fun `playing another note replaces the first`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)
            val other = audioNote(durationMs = 10_000)

            player.play(other)

            assertEquals(1, engine.stops)
            assertEquals(other.uid, assertIs<WearPlaybackUiState.Active>(player.state.value).noteId)
        }

    @Test
    fun `playback that the output suppresses is stopped and blocked`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)

            engine.suppressed.value = true

            assertEquals(WearPlaybackUiState.BlockedOutput(note.uid), player.state.value)
            assertEquals(1, engine.stops)
        }

    @Test
    fun `suppression while paused does not block`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()
            player.play(note)
            player.pauseOrResume()

            engine.suppressed.value = true

            assertIs<WearPlaybackUiState.Active>(player.state.value)
        }

    @Test
    fun `the output state is exposed for the screen`() =
        runTest(UnconfinedTestDispatcher()) {
            val player = player()

            outputs.state.value = AudioOutputState.BluetoothOnly

            assertEquals(AudioOutputState.BluetoothOnly, player.outputState.value)
        }

    private fun audioNote(durationMs: Long) =
        JournalNote.Audio(
            uid = Uuid.random(),
            creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_000_000),
            lastUpdated = Instant.fromEpochMilliseconds(1_710_000_000_000),
            mediaRef = "/phone/audio.m4a",
            durationMs = durationMs,
        )
}
