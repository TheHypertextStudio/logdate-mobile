package app.logdate.feature.editor.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.media.audio.tagging.NoopAudioTaggingService
import app.logdate.client.media.audio.transcription.TimedTranscript
import app.logdate.client.media.audio.transcription.TimedUtterance
import app.logdate.client.media.audio.transcription.TimedWord
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionStartResult
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.EditorAudioCaptureStartGate
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.startReservedAudioCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Unit tests for [AudioViewModel].
 *
 * This test suite validates the audio recording and playback state management,
 * ensuring that UI states correctly reflect the recording progress, transcription
 * updates, and playback control actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cancellation while validating a finalized recording retains its recovery path`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val model = buildViewModel(manager, AudioDurationResolver { throw CancellationException("Duration cancelled") })
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            model.stopRecording()
            advanceUntilIdle()

            assertFalse(manager.isRecording)
            assertFalse(model.uiState.value.isRecording)
            assertNull(model.uiState.value.recordedAudioUri)
            val pendingPath =
                model.uiState.value.pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/audio.m4a"))
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `duration lookup failure keeps the finalized recording recoverable`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val model = buildViewModel(manager, AudioDurationResolver { error("Metadata unavailable") })
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            model.stopRecording()
            advanceUntilIdle()

            assertNull(model.uiState.value.recordedAudioUri)
            val pendingPath =
                model.uiState.value.pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/audio.m4a"))
            assertEquals(12.seconds, model.uiState.value.duration)
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `recording target persistence completes before manager starts capture`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val model = buildViewModel(manager)
            var pathAtPersistence: String? = null
            var startCallsAtPersistence = -1

            model.startRecording(Uuid.random()) { path, _ ->
                pathAtPersistence = path
                startCallsAtPersistence = manager.startCalls
                true
            }
            advanceUntilIdle()

            assertEquals("/test/audio.m4a", pathAtPersistence)
            assertEquals(0, startCallsAtPersistence)
            assertEquals(1, manager.startCalls)
            assertTrue(model.uiState.value.isRecording)
        }

    @Test
    fun `a gated manager keeps synchronous start reservation until refusal completes`() =
        runTest(dispatcher) {
            val block = AudioBlockUiState()
            val state = MutableStateFlow(EditorState(blocks = listOf(block)))
            val gate = EditorAudioCaptureStartGate(state)
            val ready = CompletableDeferred<Unit>()
            val manager = FakeAudioRecordingManager(null, Duration.ZERO, 0f, startSucceeds = false).apply { targetGate = ready }
            val model = buildViewModel(manager)
            val job = assertNotNull(startReservedAudioCapture(gate, block.id) { model.startRecording(block.id) })
            assertNotNull(state.value.audioCaptureStartReservations[block.id])
            assertFalse(model.uiState.value.isStartingRecording, "Reservation must precede the scheduled AudioVM admission")
            dispatcher.scheduler.runCurrent()
            assertTrue(model.uiState.value.isStartingRecording)
            assertEquals(0, manager.startCalls)
            assertNull(startReservedAudioCapture(gate, block.id) { model.startRecording(block.id) })
            assertEquals(AudioCaptureState.Empty, (state.value.blocks.single() as AudioBlockUiState).captureState)
            ready.complete(Unit)
            advanceUntilIdle()
            assertTrue(job.isCompleted)
            assertEquals(1, manager.startCalls)
            assertTrue(state.value.audioCaptureStartReservations.isEmpty())
            assertFalse(manager.isRecording)
            assertEquals(block.id, model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `cancelling a start before its target releases reservation without a phantom take`() =
        runTest(dispatcher) {
            val block = AudioBlockUiState()
            val state = MutableStateFlow(EditorState(blocks = listOf(block)))
            val gate = EditorAudioCaptureStartGate(state)
            val ready = CompletableDeferred<Unit>()
            val manager = FakeAudioRecordingManager(null, Duration.ZERO, 0f).apply { targetGate = ready }
            val model = buildViewModel(manager)
            val job = assertNotNull(startReservedAudioCapture(gate, block.id) { model.startRecording(block.id) })
            dispatcher.scheduler.runCurrent()
            job.cancel()
            advanceUntilIdle()
            assertTrue(state.value.audioCaptureStartReservations.isEmpty())
            assertEquals(AudioCaptureState.Empty, (state.value.blocks.single() as AudioBlockUiState).captureState)
            assertFalse(model.uiState.value.isStartingRecording)
            assertNull(model.uiState.value.failedRecordingTargetNoteId, "Cleanup cannot depend on failed-target emission")
            assertEquals(0, manager.startCalls)
            assertNotNull(gate.claim(block.id), "The same empty block must allow a deliberate retry")
        }

    @Test
    fun `a successful start transfers reservation to durable target before completion`() =
        runTest(dispatcher) {
            val block = AudioBlockUiState()
            val state = MutableStateFlow(EditorState(blocks = listOf(block)))
            val gate = EditorAudioCaptureStartGate(state)
            val ready = CompletableDeferred<Unit>()
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", Duration.ZERO, 0f).apply { startGate = ready }
            val model = buildViewModel(manager)
            val job =
                assertNotNull(
                    startReservedAudioCapture(gate, block.id) {
                        model.startRecording(block.id) { path, fallback ->
                            val target = block.copy(captureState = AudioCaptureState.Recording(path, fallback))
                            state.value = state.value.copy(blocks = listOf(target))
                            true
                        }
                    },
                )
            dispatcher.scheduler.runCurrent()
            assertNotNull(state.value.audioCaptureStartReservations[block.id])
            val target = assertIs<AudioCaptureState.Recording>((state.value.blocks.single() as AudioBlockUiState).captureState)
            assertEquals("/test/audio.m4a", target.filePath)
            assertFalse(manager.isRecording)
            ready.complete(Unit)
            advanceUntilIdle()
            assertTrue(job.isCompleted)
            assertTrue(manager.isRecording)
            assertTrue(state.value.audioCaptureStartReservations.isEmpty())
            assertIs<AudioCaptureState.Recording>((state.value.blocks.single() as AudioBlockUiState).captureState)
            model.stopRecording()
            advanceUntilIdle()
        }

    @Test
    fun `rejected AudioVM admission releases reservation without replacing earlier recovery`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager(null, Duration.ZERO, 0f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            model.stopRecording()
            advanceUntilIdle()
            val recovery = assertNotNull(model.uiState.value.pendingRecoveryFilePath)
            val block = AudioBlockUiState()
            val state = MutableStateFlow(EditorState(blocks = listOf(block)))
            val gate = EditorAudioCaptureStartGate(state)
            val job = assertNotNull(startReservedAudioCapture(gate, block.id) { model.startRecording(block.id) })
            advanceUntilIdle()
            assertTrue(job.isCompleted)
            assertTrue(state.value.audioCaptureStartReservations.isEmpty())
            assertEquals(1, manager.startCalls)
            assertEquals(recovery, model.uiState.value.pendingRecoveryFilePath)
            assertEquals(AudioCaptureState.Empty, (state.value.blocks.single() as AudioBlockUiState).captureState)
        }

    @Test
    fun `cancellation during target persistence clears starting state for retry`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val model = buildViewModel(manager)

            model.startRecording(Uuid.random()) { _, _ -> throw CancellationException("Draft write cancelled") }
            advanceUntilIdle()

            assertFalse(model.uiState.value.isStartingRecording)
            assertFalse(model.uiState.value.isRecording)
            assertEquals(0, manager.startCalls)

            model.startRecording(Uuid.random())
            advanceUntilIdle()

            assertTrue(model.uiState.value.isRecording)
        }

    @Test
    fun `cancellation after recorder ownership attaches to the active session`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            manager.startFailureAfterRecording = CancellationException("UI owner cancelled after start")

            model.startRecording(blockId)
            advanceUntilIdle()

            assertTrue(model.uiState.value.isRecording)
            assertFalse(model.uiState.value.isStartingRecording)
            assertEquals(blockId, model.uiState.value.recordingTargetNoteId)
            assertEquals(manager.currentRecordingPath, model.uiState.value.recordingFilePath)
        }

    @Test
    fun `restart interrupted during duration lookup keeps the recording recoverable`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val model = buildViewModel(manager, AudioDurationResolver { throw CancellationException("Duration cancelled") })
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            model.restartRecording()
            advanceUntilIdle()

            assertFalse(manager.isRecording)
            assertEquals(1, manager.startCalls)
            assertNull(model.uiState.value.recordedAudioUri)
            val pendingPath =
                model.uiState.value.pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/audio.m4a"))
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `a recreated block attaches to a pending start without stopping it`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val blockId = Uuid.random()
            manager.beginPendingStart(blockId)
            val model = buildViewModel(manager)

            model.attachToRecording(blockId)
            dispatcher.scheduler.runCurrent()

            assertTrue(model.uiState.value.isStartingRecording)
            assertFalse(model.uiState.value.isRecording)
            assertEquals(blockId, model.uiState.value.recordingTargetNoteId)
            assertEquals(manager.currentRecordingPath, model.uiState.value.recordingFilePath)
            assertEquals(0, manager.stopCalls)
            assertNull(model.uiState.value.recordedAudioUri)
        }

    @Test
    fun `an attached pending start enables recording controls after confirmation`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val blockId = Uuid.random()
            manager.beginPendingStart(blockId)
            val model = buildViewModel(manager)
            model.attachToRecording(blockId)
            dispatcher.scheduler.runCurrent()

            manager.confirmPendingStart()
            dispatcher.scheduler.runCurrent()

            assertTrue(model.uiState.value.isRecording)
            assertFalse(model.uiState.value.isStartingRecording)
            assertEquals(0, manager.stopCalls)
            model.pauseRecording()
            advanceUntilIdle()
            assertTrue(model.uiState.value.isPaused)
        }

    @Test
    fun `failed confirmation releases an attached pending start`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val blockId = Uuid.random()
            manager.beginPendingStart(blockId)
            val model = buildViewModel(manager)
            model.attachToRecording(blockId)
            dispatcher.scheduler.runCurrent()

            manager.failPendingStart()
            advanceUntilIdle()

            assertFalse(model.uiState.value.isStartingRecording)
            assertFalse(model.uiState.value.isRecording)
            assertNull(model.uiState.value.recordingTargetNoteId)
            assertNull(model.uiState.value.recordingFilePath)
            assertEquals(blockId, model.uiState.value.failedRecordingTargetNoteId)
            assertEquals(0, manager.stopCalls)
            assertNotNull(model.uiState.value.error)
        }

    @Test
    fun `a recreated block reattaches to its live recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val blockId = Uuid.random()
            manager.startRecording(blockId)
            val model = buildViewModel(manager)
            model.attachToRecording(blockId)
            advanceUntilIdle()
            assertTrue(model.uiState.value.isRecording)
            assertEquals(blockId, model.uiState.value.recordingTargetNoteId)
            assertEquals(12.seconds, model.uiState.value.duration)
            assertEquals(1, manager.startCalls)
            assertNotNull(model.resolvePending(blockId))
        }

    @Test
    fun `a different block cannot adopt or stop a live recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            manager.startRecording(Uuid.random())
            val other = buildViewModel(manager)
            other.attachToRecording(Uuid.random())
            advanceUntilIdle()
            assertFalse(other.uiState.value.isRecording)
            val store = ViewModelStore()
            ViewModelProvider.create(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(
                        modelClass: KClass<T>,
                        extras: CreationExtras,
                    ): T = other as T
                },
            )["other", AudioViewModel::class]
            store.clear()
            assertEquals(0, manager.requestStopCalls)
            assertTrue(manager.isRecording)
        }

    @Test
    fun `start in flight exposes its block ownership`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val gate = CompletableDeferred<Unit>()
            manager.startGate = gate
            val blockId = Uuid.random()
            val model = buildViewModel(manager)
            model.startRecording(blockId)
            dispatcher.scheduler.runCurrent()
            assertEquals(blockId, model.uiState.value.recordingTargetNoteId)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `a refused start clears the pending capture for its own block`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f, startSucceeds = false)
            val blockId = Uuid.random()
            val model = buildViewModel(manager)
            model.startRecording(blockId)
            advanceUntilIdle()
            assertEquals(blockId, model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `notification pause updates the owning block`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            manager.emitPaused(true)
            advanceUntilIdle()
            assertTrue(model.uiState.value.isPaused)
            manager.emitPaused(false)
            advanceUntilIdle()
            assertFalse(model.uiState.value.isPaused)
        }

    @Test
    fun `a recreated block absorbs its already completed owner recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
            val id = Uuid.random()
            manager.startRecording(id)
            manager.endRecordingExternally()
            val model = buildViewModel(manager)
            model.attachToRecording(id)
            advanceUntilIdle()
            assertFalse(model.uiState.value.isRecording)
            assertEquals("file:///test/audio.m4a", model.uiState.value.recordedAudioUri)
        }

    @Test
    fun `a stale owner cannot stop a newer recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            manager.startRecording(Uuid.random())
            model.stopRecording()
            advanceUntilIdle()
            assertEquals(0, manager.stopCalls)
            assertTrue(manager.isRecording)
        }

    @Test
    fun `restart does not hand off its discarded recording while transcription reset is pending`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            val resetGate = CompletableDeferred<Unit>()
            manager.resetGate = resetGate

            model.restartRecording()
            dispatcher.scheduler.runCurrent()

            assertFalse(manager.isRecording)
            assertNull(model.uiState.value.recordedAudioUri)
            assertEquals(blockId, model.uiState.value.recordingTargetNoteId)
            resetGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(model.uiState.value.isRecording)
            assertEquals(2, manager.startCalls)
            assertNull(model.uiState.value.recordedAudioUri)
        }

    @Test
    fun `a cancelled restart reset restores the previous recording for immediate save`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            val resetGate = CompletableDeferred<Unit>()
            manager.resetGate = resetGate
            model.restartRecording()
            dispatcher.scheduler.runCurrent()
            assertNull(model.uiState.value.recordedAudioUri)

            resetGate.completeExceptionally(CancellationException("Restart cancelled during reset"))
            advanceUntilIdle()

            val recovered = assertIs<AudioCaptureState.Ready>(model.resolvePending(blockId))
            assertEquals("file:///test/audio.m4a", recovered.uri)
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `a cancelled replacement start restores the previous recording for immediate save`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            val startGate = CompletableDeferred<Unit>()
            manager.startGate = startGate
            model.restartRecording()
            dispatcher.scheduler.runCurrent()
            assertNull(model.uiState.value.recordedAudioUri)

            startGate.completeExceptionally(CancellationException("Replacement start cancelled"))
            advanceUntilIdle()

            val recovered = assertIs<AudioCaptureState.Ready>(model.resolvePending(blockId))
            assertEquals("file:///test/audio.m4a", recovered.uri)
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
        }

    @Test
    fun `a cancelled replacement reattaches its newly active recording without publishing the old file`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            manager.startFailureAfterRecording = CancellationException("Caller cancelled after recorder confirmation")

            model.restartRecording()
            advanceUntilIdle()

            assertTrue(manager.isRecording)
            assertTrue(model.uiState.value.isRecording)
            assertFalse(model.uiState.value.isStartingRecording)
            assertEquals(blockId, model.uiState.value.recordingTargetNoteId)
            assertEquals(manager.currentRecordingPath, model.uiState.value.recordingFilePath)
            assertNull(model.uiState.value.recordedAudioUri)
        }

    @Test
    fun `replacement admission does not expose the previous file as the new recording target`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            val startGate = CompletableDeferred<Unit>()
            manager.startGate = startGate

            model.restartRecording()
            dispatcher.scheduler.runCurrent()

            assertTrue(model.uiState.value.isStartingRecording)
            assertNull(model.uiState.value.recordingFilePath)
            assertNull(model.uiState.value.recordedAudioUri)
            startGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `restart retains an unfinalized recording for later recovery`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager(null, 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()

            model.restartRecording()
            advanceUntilIdle()

            assertEquals(1, manager.startCalls)
            assertFalse(model.uiState.value.isRecording)
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
            val pendingPath =
                model.uiState.value
                    .pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/audio.m4a"))
        }

    @Test
    fun `restart keeps the previous recording if a replacement cannot start`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            manager.startSucceeds = false
            var durableFallback: String? = null

            model.restartRecording { _, fallback ->
                durableFallback = fallback
                true
            }
            advanceUntilIdle()

            assertFalse(model.uiState.value.isRecording)
            assertEquals("file:///test/audio.m4a", model.uiState.value.recordedAudioUri)
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
            assertEquals("file:///test/audio.m4a", durableFallback)
        }

    @Test
    fun `cancellation after replacement ownership retains the previous take fallback`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            manager.startFailureAfterRecording = CancellationException("Replacement owner cancelled")

            model.restartRecording()
            advanceUntilIdle()

            assertTrue(model.uiState.value.isRecording)
            assertEquals("file:///test/audio.m4a", model.uiState.value.recordingFallbackFilePath)
        }

    @Test
    fun `failed replacement stop restores the previous completed take`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model =
                buildViewModel(
                    manager,
                    AudioDurationResolver { path -> if (path.endsWith("/test/audio.m4a")) 1_000L else null },
                )
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            manager.nextRecordingPath = "/test/replacement.m4a"
            model.restartRecording()
            advanceUntilIdle()
            manager.stopFailure = IllegalStateException("Replacement could not be finalized")

            model.restartRecording()
            advanceUntilIdle()

            assertFalse(model.uiState.value.isRecording)
            val pendingPath =
                model.uiState.value.pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/replacement.m4a"))
            assertEquals("file:///test/audio.m4a", model.uiState.value.recordingFallbackFilePath)
            assertNull(model.uiState.value.recordedAudioUri)
            assertNull(model.uiState.value.failedRecordingTargetNoteId)
            manager.stopFailure = null
            val recovered = assertIs<AudioCaptureState.Ready>(model.resolvePending(blockId))
            assertEquals("file:///test/audio.m4a", recovered.uri)
        }

    @Test
    fun `failed first stop keeps its durable draft target for startup recovery`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            var canResolveDuration = false
            val model =
                buildViewModel(
                    manager,
                    AudioDurationResolver { if (canResolveDuration) 1_000L else null },
                )
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            manager.stopFailure = IllegalStateException("Recorder could not finalize")

            model.stopRecording()
            advanceUntilIdle()

            val state = model.uiState.value
            assertFalse(state.isRecording)
            assertTrue(state.pendingRecoveryFilePath.orEmpty().endsWith("/test/audio.m4a"))
            assertNull(state.recordedAudioUri)
            assertNull(state.failedRecordingTargetNoteId)
            assertNull(state.error)
            manager.stopFailure = null
            assertIs<AudioCaptureState.Failed>(model.resolvePending(blockId))
            val retainedPendingPath =
                model.uiState.value.pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(retainedPendingPath.endsWith("/test/audio.m4a"))
            assertNull(model.uiState.value.recordedAudioUri)
            canResolveDuration = true
            val recovered = assertIs<AudioCaptureState.Ready>(model.resolvePending(blockId))
            assertEquals("/test/audio.m4a", recovered.uri)
            assertNull(model.uiState.value.pendingRecoveryFilePath)
        }

    @Test
    fun `cancellation while validating stopped recovery preserves its retry anchor`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            var cancelValidation = true
            val model =
                buildViewModel(
                    manager,
                    AudioDurationResolver {
                        if (cancelValidation) throw CancellationException("Validation cancelled")
                        1_000L
                    },
                )
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            manager.stopFailure = IllegalStateException("Recorder could not finalize")
            model.stopRecording()
            advanceUntilIdle()

            manager.stopFailure = null
            assertFailsWith<CancellationException> { model.resolvePending(blockId) }
            assertNull(model.uiState.value.recordedAudioUri)
            val pendingPath =
                model.uiState.value.pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/audio.m4a"))

            cancelValidation = false
            val recovered = assertIs<AudioCaptureState.Ready>(model.resolvePending(blockId))
            assertEquals("/test/audio.m4a", recovered.uri)
        }

    @Test
    fun `cancelled replacement does not attach to another untargeted recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording()
            advanceUntilIdle()
            manager.activePathOverride = "/test/other-session.m4a"
            manager.startFailureAfterRecording = CancellationException("Replacement start cancelled")

            model.restartRecording()
            advanceUntilIdle()

            assertFalse(model.uiState.value.isRecording)
            assertEquals("file:///test/audio.m4a", model.uiState.value.recordedAudioUri)
            assertTrue(manager.isRecording, "The editor must not claim or stop another owner's recording")
        }

    @Test
    fun `a cancelled stop preserves the pending recording anchor`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            val blockId = Uuid.random()
            model.startRecording(blockId)
            advanceUntilIdle()
            manager.stopFailure = CancellationException("Editor closed while finalizing")

            model.stopRecording()
            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(blockId, state.recordingTargetNoteId)
            assertEquals("/test/audio.m4a", state.recordingFilePath)
            assertNull(state.failedRecordingTargetNoteId)
            assertNull(state.error)
            manager.stopFailure = null
            assertNotNull(model.resolvePending(blockId))
        }

    @Test
    fun `a stale view model cannot pause a newer owner's recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            val newOwner = Uuid.random()
            manager.startRecording(newOwner)

            model.pauseRecording()
            advanceUntilIdle()

            assertFalse(manager.currentRecordingPaused)
            assertEquals(newOwner, manager.currentRecordingTargetNoteId)
        }

    @Test
    fun `a stale view model cannot resume a newer owner's paused recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            val newOwner = Uuid.random()
            manager.startRecording(newOwner)
            manager.emitPaused(true)

            model.resumeRecording()
            advanceUntilIdle()

            assertTrue(manager.currentRecordingPaused)
            assertEquals(newOwner, manager.currentRecordingTargetNoteId)
        }

    @Test
    fun `a stale view model cannot reset transcription or restart a newer owner's recording`() =
        runTest(dispatcher) {
            val manager = FakeAudioRecordingManager("file:///test/audio.m4a", 1.seconds, 0.5f)
            val model = buildViewModel(manager)
            model.startRecording(Uuid.random())
            advanceUntilIdle()
            val newOwner = Uuid.random()
            manager.startRecording(newOwner)
            val transcript = TranscriptionResult.Success("New owner's transcript", isFinal = true)
            manager.emitStructuredTranscription(transcript)

            model.restartRecording()
            advanceUntilIdle()

            assertEquals(newOwner, manager.currentRecordingTargetNoteId)
            assertEquals(2, manager.startCalls)
            assertEquals(transcript, manager.currentTranscription)
        }

    @Test
    fun `start recording updates ui state`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = 5.seconds,
                    initialLevel = 0.5f,
                )
            val viewModel =
                AudioViewModel(
                    audioRecordingManager = recordingManager,
                    audioPlaybackManager = FakeAudioPlaybackManager(),
                    audioDurationResolver = FakeAudioDurationResolver(),
                    transcriptionService = FakeTranscriptionService(),
                    audioTaggingService = NoopAudioTaggingService,
                )

            viewModel.startRecording()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isRecording, "Should be recording")
            assertEquals(5.seconds, state.duration, "Should reflect emitted duration")
            assertTrue(state.audioLevels.isNotEmpty(), "Should collect audio levels")
        }

    @Test
    fun `stop recording stores recorded uri`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = 3.seconds,
                    initialLevel = 0.2f,
                )
            val viewModel =
                AudioViewModel(
                    audioRecordingManager = recordingManager,
                    audioPlaybackManager = FakeAudioPlaybackManager(),
                    audioDurationResolver = FakeAudioDurationResolver(),
                    transcriptionService = FakeTranscriptionService(),
                    audioTaggingService = NoopAudioTaggingService,
                )

            viewModel.startRecording()
            advanceUntilIdle()
            viewModel.stopRecording()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isRecording, "Should stop recording")
            assertEquals("file:///test/audio.m4a", state.recordedAudioUri, "Should store recorded URI")
        }

    @Test
    fun `transcription flow updates ui state`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = 1.seconds,
                    initialLevel = 0.1f,
                )
            val viewModel =
                AudioViewModel(
                    audioRecordingManager = recordingManager,
                    audioPlaybackManager = FakeAudioPlaybackManager(),
                    audioDurationResolver = FakeAudioDurationResolver(),
                    transcriptionService = FakeTranscriptionService(),
                    audioTaggingService = NoopAudioTaggingService,
                )

            viewModel.startRecording()
            advanceUntilIdle()

            recordingManager.emitStructuredTranscription(
                TranscriptionResult.Success(
                    text = "Hello world",
                    timedTranscript = null,
                    isFinal = false,
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val transcriptionState = state.transcriptionState
            assertTrue(
                transcriptionState is AudioUiState.TranscriptionState.Success,
                "Should update transcription state on new text",
            )
            val successState = transcriptionState
            assertEquals("Hello world", successState.text)
        }

    @Test
    fun `a transcription start failure is not overwritten by optimistic progress`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = 1.seconds,
                    initialLevel = 0.1f,
                    startResult = TranscriptionResult.Error(TranscriptionFailure.NotAvailable),
                )
            val viewModel =
                AudioViewModel(
                    audioRecordingManager = recordingManager,
                    audioPlaybackManager = FakeAudioPlaybackManager(),
                    audioDurationResolver = FakeAudioDurationResolver(),
                    transcriptionService = FakeTranscriptionService(),
                    audioTaggingService = NoopAudioTaggingService,
                )

            viewModel.startRecording()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isRecording)
            assertEquals(
                AudioUiState.TranscriptionState.Error(TranscriptionFailure.NotAvailable),
                viewModel.uiState.value.transcriptionState,
            )
        }

    @Test
    fun `structured transcription preserves timed transcript`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = 1.seconds,
                    initialLevel = 0.1f,
                )
            val viewModel =
                AudioViewModel(
                    audioRecordingManager = recordingManager,
                    audioPlaybackManager = FakeAudioPlaybackManager(),
                    audioDurationResolver = FakeAudioDurationResolver(),
                    transcriptionService = FakeTranscriptionService(),
                    audioTaggingService = NoopAudioTaggingService,
                )

            viewModel.startRecording()
            advanceUntilIdle()

            val timedTranscript =
                TimedTranscript(
                    utterances =
                        listOf(
                            TimedUtterance(
                                text = "Hello world.",
                                startMs = 0,
                                endMs = 1000,
                                words =
                                    listOf(
                                        TimedWord("Hello", "hello", 0, 500),
                                        TimedWord("world", "world", 500, 1000),
                                    ),
                            ),
                        ),
                )

            recordingManager.emitStructuredTranscription(
                TranscriptionResult.Success(
                    text = timedTranscript.plainText,
                    timedTranscript = timedTranscript,
                    isFinal = true,
                ),
            )
            advanceUntilIdle()

            val transcriptionState = viewModel.uiState.value.transcriptionState
            val successState = assertNotNull(transcriptionState as? AudioUiState.TranscriptionState.Success)
            assertEquals(timedTranscript, successState.timedTranscript)
            assertTrue(successState.isFinal)
        }

    private fun buildViewModel(
        recordingManager: FakeAudioRecordingManager,
        durationResolver: AudioDurationResolver = FakeAudioDurationResolver(),
        playbackManager: AudioPlaybackManager = FakeAudioPlaybackManager(),
    ): AudioViewModel =
        AudioViewModel(
            audioRecordingManager = recordingManager,
            audioPlaybackManager = playbackManager,
            audioDurationResolver = durationResolver,
            transcriptionService = FakeTranscriptionService(),
            audioTaggingService = NoopAudioTaggingService,
        )

    @Test
    fun `retry playback clears the previous failure without starting capture`() =
        runTest(dispatcher) {
            val recorder = FakeAudioRecordingManager("retained.m4a", 1.seconds, 0f)
            val player = FakeAudioPlaybackManager().apply { startFailure = IllegalStateException("unavailable") }
            val model = buildViewModel(recorder, playbackManager = player)
            model.startPlayback("retained.m4a")
            advanceUntilIdle()
            assertNotNull(model.uiState.value.error)
            player.startFailure = null
            model.startPlayback("retained.m4a")
            advanceUntilIdle()
            assertNull(model.uiState.value.error)
            assertTrue(model.uiState.value.isPlaying)
            assertEquals(0, recorder.startCalls)
        }

    @Test
    fun `tapping record twice starts a single recording`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = Duration.ZERO,
                    initialLevel = 0f,
                )
            val gate = CompletableDeferred<Unit>()
            recordingManager.startGate = gate
            val viewModel = buildViewModel(recordingManager)
            val blockId = Uuid.random()

            viewModel.startRecording(blockId)
            viewModel.startRecording(blockId)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isStartingRecording, "Should report the start in flight")
            assertFalse(viewModel.uiState.value.isRecording)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, recordingManager.startCalls, "Second tap must not start a second session")
            assertTrue(viewModel.uiState.value.isRecording)
            assertFalse(viewModel.uiState.value.isStartingRecording)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `a start the platform rejects returns to idle with an error`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = Duration.ZERO,
                    initialLevel = 0f,
                    startSucceeds = false,
                )
            val viewModel = buildViewModel(recordingManager)

            viewModel.startRecording(Uuid.random())
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isRecording)
            assertFalse(state.isStartingRecording)
            assertNotNull(state.error, "The user must be told the recording did not start")
            assertNull(state.recordingTargetNoteId)
        }

    @Test
    fun `stop while idle does not reach the platform`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = Duration.ZERO,
                    initialLevel = 0f,
                )
            val viewModel = buildViewModel(recordingManager)

            viewModel.stopRecording()
            advanceUntilIdle()

            assertEquals(0, recordingManager.stopCalls)
            assertFalse(viewModel.uiState.value.isRecording)
        }

    @Test
    fun `a stop that yields no file preserves the draft target for recovery`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = null,
                    initialDuration = Duration.ZERO,
                    initialLevel = 0f,
                )
            val viewModel = buildViewModel(recordingManager)
            val blockId = Uuid.random()

            viewModel.startRecording(blockId)
            advanceUntilIdle()
            viewModel.stopRecording()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isRecording)
            assertNull(state.recordedAudioUri)
            assertNull(state.failedRecordingTargetNoteId)
            assertTrue(state.pendingRecoveryFilePath.orEmpty().endsWith("/test/audio.m4a"))
            assertNull(state.error)

            viewModel.startRecording(Uuid.random())
            advanceUntilIdle()

            assertEquals(1, recordingManager.startCalls)
            val pendingPath =
                viewModel.uiState.value
                    .pendingRecoveryFilePath
                    .orEmpty()
            assertTrue(pendingPath.endsWith("/test/audio.m4a"))
        }

    @Test
    fun `a recording ended outside the editor is finalized`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = 2.seconds,
                    initialLevel = 0f,
                )
            val viewModel = buildViewModel(recordingManager)
            val blockId = Uuid.random()

            viewModel.startRecording(blockId)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRecording)

            recordingManager.endRecordingExternally()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isRecording, "The editor must follow a stop it did not initiate")
            assertEquals("file:///test/audio.m4a", state.recordedAudioUri)
            assertEquals(1, recordingManager.stopCalls, "Finalizing collects the file through the manager")
        }

    @Test
    fun `a new view model does not adopt a previous session's transcript`() =
        runTest(dispatcher) {
            val recordingManager =
                FakeAudioRecordingManager(
                    outputUri = "file:///test/audio.m4a",
                    initialDuration = Duration.ZERO,
                    initialLevel = 0f,
                )
            recordingManager.emitStructuredTranscription(
                TranscriptionResult.Success(text = "Words from another block", isFinal = true),
            )

            val viewModel = buildViewModel(recordingManager)
            advanceUntilIdle()

            assertEquals(
                AudioUiState.TranscriptionState.NotRequested,
                viewModel.uiState.value.transcriptionState,
            )
        }

    @Test
    fun `seek to position ms uses absolute seek and updates playback progress`() =
        runTest(dispatcher) {
            val playbackManager = FakeAudioPlaybackManager()
            val viewModel =
                AudioViewModel(
                    audioRecordingManager =
                        FakeAudioRecordingManager(
                            outputUri = "file:///test/audio.m4a",
                            initialDuration = 4.seconds,
                            initialLevel = 0.1f,
                        ),
                    audioPlaybackManager = playbackManager,
                    audioDurationResolver = FakeAudioDurationResolver(),
                    transcriptionService = FakeTranscriptionService(),
                    audioTaggingService = NoopAudioTaggingService,
                )

            viewModel.seekToPositionMs(positionMs = 2_500L, durationMs = 10_000L)
            advanceUntilIdle()

            assertEquals(2_500L, playbackManager.lastSeekPositionMs)
            assertEquals(10_000L, playbackManager.lastSeekDurationMs)
            assertEquals(0.25f, viewModel.uiState.value.playbackProgress)
        }
}

private class FakeAudioRecordingManager(
    private val outputUri: String?,
    initialDuration: Duration,
    initialLevel: Float,
    private val startResult: TranscriptionResult? = null,
    var startSucceeds: Boolean = true,
) : AudioRecordingManager {
    private val audioLevelFlow = MutableStateFlow(initialLevel)
    private val durationFlow = MutableStateFlow(initialDuration)
    private val transcriptionFlow = MutableStateFlow<String?>(null)
    private val structuredFlow = MutableSharedFlow<TranscriptionResult>(replay = 1)
    private val recordingStateFlow = MutableStateFlow(false)
    private val startingFlow = MutableStateFlow(false)
    private val pausedFlow = MutableStateFlow(false)

    /** When set, [startRecording] suspends until it completes so tests can observe the in-flight state. */
    var startGate: CompletableDeferred<Unit>? = null
    var targetGate: CompletableDeferred<Unit>? = null
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set

    var stopFailure: Exception? = null
    var resetGate: CompletableDeferred<Unit>? = null
    var startFailureAfterRecording: Exception? = null
    var nextRecordingPath = "/test/audio.m4a"
    var activePathOverride: String? = null
    private var activeRecordingPath: String? = null

    val currentTranscription: TranscriptionResult?
        get() = structuredFlow.replayCache.lastOrNull()

    var requestStopCalls = 0
        private set

    override var currentRecordingTargetNoteId: Uuid? = null
        private set

    override val currentRecordingPath: String?
        get() = activeRecordingPath

    override fun requestStopRecording() {
        requestStopCalls++
    }

    override var isRecording: Boolean = false
        private set

    override val isStartingRecording: Boolean
        get() = startingFlow.value

    override fun getRecordingStartingFlow(): Flow<Boolean> = startingFlow

    override suspend fun startRecording(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean {
        targetGate?.await()
        if (!onTargetReady(nextRecordingPath)) return false
        return startRecording(targetNoteId)
    }

    fun beginPendingStart(blockId: Uuid) {
        currentRecordingTargetNoteId = blockId
        startingFlow.value = true
    }

    fun confirmPendingStart() {
        startingFlow.value = false
        isRecording = true
        recordingStateFlow.value = true
    }

    fun failPendingStart() {
        currentRecordingTargetNoteId = null
        startingFlow.value = false
    }

    override suspend fun startRecording(targetNoteId: kotlin.uuid.Uuid?): Boolean {
        startCalls++
        startGate?.await()
        if (!startSucceeds) return false
        currentRecordingTargetNoteId = targetNoteId
        activeRecordingPath = activePathOverride ?: nextRecordingPath
        isRecording = true
        recordingStateFlow.value = true
        startResult?.let { structuredFlow.emit(it) }
        startFailureAfterRecording?.let { throw it }
        return true
    }

    override suspend fun stopRecording(): String? {
        stopCalls++
        stopFailure?.let { throw it }
        isRecording = false
        recordingStateFlow.value = false
        activeRecordingPath = null
        return outputUri
    }

    override fun getRecordingStateFlow(): Flow<Boolean> = recordingStateFlow

    override val currentRecordingPaused: Boolean
        get() = pausedFlow.value

    override fun getRecordingPausedFlow(): Flow<Boolean> = pausedFlow

    fun emitPaused(paused: Boolean) {
        pausedFlow.value = paused
    }

    override suspend fun pauseRecording(): Boolean {
        pausedFlow.value = true
        return true
    }

    override suspend fun resumeRecording(): Boolean {
        pausedFlow.value = false
        return true
    }

    override suspend fun resetTranscription() {
        resetGate?.await()
        transcriptionFlow.value = null
        structuredFlow.emit(TranscriptionResult.Cancelled)
    }

    /** Simulates the platform ending the recording outside the editor (for example a notification action). */
    fun endRecordingExternally() {
        isRecording = false
        recordingStateFlow.value = false
    }

    override fun getAudioLevelFlow(): Flow<Float> = audioLevelFlow

    override fun getRecordingDurationFlow(): Flow<Duration> = durationFlow

    override fun getTranscriptionFlow(): Flow<String?> = transcriptionFlow

    override fun getStructuredTranscriptionFlow(): Flow<TranscriptionResult> = structuredFlow.asSharedFlow()

    fun emitTranscription(text: String?) {
        transcriptionFlow.value = text
    }

    suspend fun emitStructuredTranscription(result: TranscriptionResult) {
        structuredFlow.emit(result)
    }

    override fun setTranscriptionService(service: TranscriptionService) {
        // No-op for tests.
    }

    override fun release() {
        isRecording = false
    }
}

private class FakeAudioPlaybackManager : AudioPlaybackManager {
    var startFailure: Exception? = null
    var lastSeekRatio: Float? = null
    var lastSeekPositionMs: Long? = null
    var lastSeekDurationMs: Long? = null

    override fun startPlayback(
        uri: String,
        metadata: AudioPlaybackMetadata?,
        onProgressUpdated: (Float) -> Unit,
        onPlaybackCompleted: () -> Unit,
    ) {
        startFailure?.let { throw it }
        onProgressUpdated(0f)
    }

    override fun pausePlayback() = Unit

    override fun stopPlayback() = Unit

    override fun seekTo(position: Float) {
        lastSeekRatio = position
    }

    override fun seekTo(
        positionMs: Long,
        durationMs: Long,
    ) {
        lastSeekPositionMs = positionMs
        lastSeekDurationMs = durationMs
    }

    override fun release() = Unit
}

private class FakeAudioDurationResolver : AudioDurationResolver {
    override suspend fun resolveDurationMs(uri: String): Long? = 1_000L
}

private class FakeTranscriptionService : TranscriptionService {
    private val transcriptionFlow = MutableSharedFlow<TranscriptionResult>(replay = 1)

    override fun getTranscriptionFlow(): SharedFlow<TranscriptionResult> = transcriptionFlow

    override suspend fun startLiveTranscription(): TranscriptionStartResult = TranscriptionStartResult.Started

    override suspend fun stopLiveTranscription(): TranscriptionResult = TranscriptionResult.Cancelled

    override suspend fun transcribeAudioFile(audioUri: String): TranscriptionResult = TranscriptionResult.Success("Test transcription")

    override suspend fun cancelTranscription() = Unit

    override fun getSupportedLanguages(): List<String> = emptyList()

    override fun setLanguage(languageCode: String) = Unit

    override val supportsLiveTranscription: Boolean = true

    override val supportsFileTranscription: Boolean = true

    override suspend fun resetTranscription() = Unit

    override fun release() = Unit
}
