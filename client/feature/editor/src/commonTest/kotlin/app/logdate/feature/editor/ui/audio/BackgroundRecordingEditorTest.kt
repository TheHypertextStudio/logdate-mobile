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
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionStartResult
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRecordingEditorTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `disposing the editor leaves a durable session running but stops an unprotected one`() =
        runTest(dispatcher) {
            for (durable in listOf(true, false)) {
                val manager = BackgroundRecordingManager("file:///test/audio.m4a", 12.seconds, 0.5f)
                manager.continuesWithoutEditor = durable
                val model =
                    AudioViewModel(
                        manager,
                        BackgroundPlaybackManager(),
                        BackgroundDurationResolver(),
                        BackgroundTranscriptionService(),
                        NoopAudioTaggingService,
                    )
                model.startRecording(Uuid.random())
                advanceUntilIdle()
                val store = ViewModelStore()
                ViewModelProvider.create(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(
                            modelClass: KClass<T>,
                            extras: CreationExtras,
                        ): T = model as T
                    },
                )["audio", AudioViewModel::class]
                store.clear()
                assertEquals(if (durable) 0 else 1, manager.requestStopCalls)
            }
        }
}

private class BackgroundRecordingManager(
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
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set

    var stopFailure: Exception? = null
    var resetGate: CompletableDeferred<Unit>? = null
    var startFailureAfterRecording: Exception? = null

    val currentTranscription: TranscriptionResult?
        get() = structuredFlow.replayCache.lastOrNull()

    var requestStopCalls = 0
        private set

    override var currentRecordingTargetNoteId: Uuid? = null
        private set

    override val currentRecordingPath: String?
        get() = if (currentRecordingTargetNoteId != null) "/test/audio.m4a" else null

    override fun requestStopRecording() {
        requestStopCalls++
    }

    override var continuesWithoutEditor: Boolean = true

    override var isRecording: Boolean = false
        private set

    override val isStartingRecording: Boolean
        get() = startingFlow.value

    override fun getRecordingStartingFlow(): Flow<Boolean> = startingFlow

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
        isRecording = true
        recordingStateFlow.value = true
        startResult?.let { structuredFlow.emit(it) }
        startFailureAfterRecording?.let { throw it }
        return true
    }

    override suspend fun startRecording(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean {
        if (!onTargetReady("/test/audio.m4a")) return false
        return startRecording(targetNoteId)
    }

    override suspend fun stopRecording(): String? {
        stopCalls++
        stopFailure?.let { throw it }
        isRecording = false
        recordingStateFlow.value = false
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

private class BackgroundPlaybackManager : AudioPlaybackManager {
    var lastSeekRatio: Float? = null
    var lastSeekPositionMs: Long? = null
    var lastSeekDurationMs: Long? = null

    override fun startPlayback(
        uri: String,
        metadata: AudioPlaybackMetadata?,
        onProgressUpdated: (Float) -> Unit,
        onPlaybackCompleted: () -> Unit,
    ) {
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

private class BackgroundDurationResolver : AudioDurationResolver {
    override suspend fun resolveDurationMs(uri: String): Long? = null
}

private class BackgroundTranscriptionService : TranscriptionService {
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
