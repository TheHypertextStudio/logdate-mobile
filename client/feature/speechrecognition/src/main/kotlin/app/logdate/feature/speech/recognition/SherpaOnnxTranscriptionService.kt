package app.logdate.feature.speech.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import androidx.core.content.ContextCompat
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.transcription.TranscriptAccumulator
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionSessionTerminalizer
import app.logdate.client.media.audio.transcription.TranscriptionStartResult
import com.k2fsa.sherpa.onnx.OnlineStream
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/**
 * On-device transcription service using Sherpa-ONNX speech recognition with
 * online punctuation.
 *
 * Uses [AudioRecord] to capture raw PCM audio without requesting audio focus,
 * so music playback continues uninterrupted. The PCM stream is fed
 * to a Sherpa-ONNX recognizer (via [SherpaOnnxRecognizerProvider]) for streaming
 * speech-to-text.
 *
 * Finalized segments are run through the punctuation model to add
 * capitalization and punctuation before being appended to accumulated text.
 */
class SherpaOnnxTranscriptionService(
    private val context: Context,
    private val recognizerProvider: SherpaOnnxRecognizerProvider,
    private val vadProvider: SherpaOnnxVadProvider,
    private val offlineRecognizerProvider: SherpaOnnxOfflineRecognizerProvider,
    private val scope: CoroutineScope,
    private val accumulator: TranscriptAccumulator,
) : TranscriptionService {
    private val _transcriptionFlow = MutableSharedFlow<TranscriptionResult>(replay = 1)
    private val terminalizer = TranscriptionSessionTerminalizer(_transcriptionFlow::emit)
    private val audioCapture = TranscriptionAudioCapture(context)
    private val transcriptProcessor =
        SherpaOnnxTranscriptProcessor(context, recognizerProvider, offlineRecognizerProvider, terminalizer)
    private val liveDecoder = SherpaOnnxLiveDecoder(recognizerProvider, vadProvider, accumulator, terminalizer)

    private var stream: OnlineStream? = null

    override fun updatePreferredInputDevice(deviceId: String?): Boolean = audioCapture.updatePreferredInputDevice(deviceId)

    private var recognitionJob: Job? = null
    private var refinementJob: Job? = null

    @Volatile
    private var isListening = false

    private val floatBuffer = FloatArray(BUFFER_SIZE_SHORTS)

    override suspend fun warmUp() {
        recognizerProvider.ensureInitialized()
        vadProvider.ensureInitialized()
        // Refinement is optional. If the Whisper model hasn't been downloaded
        // yet, ensureInitialized() returns false instead of throwing — the app
        // falls back to streaming-only transcription without the user noticing.
        offlineRecognizerProvider.ensureInitialized()
    }

    override fun getTranscriptionFlow(): SharedFlow<TranscriptionResult> = _transcriptionFlow.asSharedFlow()

    override suspend fun startLiveTranscription(): TranscriptionStartResult {
        if (isListening) return TranscriptionStartResult.AlreadyRunning

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Napier.e("RECORD_AUDIO permission not granted for transcription")
            _transcriptionFlow.emit(TranscriptionResult.Error(TranscriptionFailure.PermissionDenied))
            return TranscriptionStartResult.Failed(TranscriptionFailure.PermissionDenied)
        }

        // The user is starting a new session — any in-flight refinement from
        // the previous one is no longer relevant.
        refinementJob?.cancelAndJoin()
        refinementJob = null
        terminalizer.cancel()
        liveDecoder.clearRefinementBuffer()

        var sessionAccepted = false
        return try {
            // Start capturing audio immediately so no speech is lost during model init
            val ar = audioCapture.start(BUFFER_SIZE_BYTES)
            val acknowledgement = terminalizer.begin()
            if (acknowledgement != TranscriptionStartResult.Started) {
                audioCapture.stop()
                return acknowledgement
            }
            sessionAccepted = true
            isListening = true

            // Pre-warm Whisper while the user records. Runs on Default (CPU-bound
            // model load), not IO, so it doesn't serialize behind the audio capture
            // loop which also lives on Dispatchers.IO.
            scope.launch(Dispatchers.Default) {
                try {
                    offlineRecognizerProvider.ensureInitialized()
                } catch (e: Exception) {
                    Napier.w("Whisper pre-warm failed; refinement will be unavailable", e)
                }
            }

            // Buffer audio samples while models load
            val preBuffer = ArrayDeque<FloatArray>()
            recognitionJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        val shortBuffer = ShortArray(BUFFER_SIZE_SHORTS)
                        var consecutiveEmptyReads = 0

                        suspend fun readSamples(): Int {
                            val count = ar.read(shortBuffer, 0, shortBuffer.size)
                            when {
                                count > 0 -> {
                                    consecutiveEmptyReads = 0
                                }

                                count < 0 -> {
                                    throw AudioCaptureException("AudioRecord.read failed with code $count")
                                }

                                else -> {
                                    consecutiveEmptyReads += 1
                                    if (consecutiveEmptyReads >= MAX_CONSECUTIVE_EMPTY_READS) {
                                        throw AudioCaptureException("AudioRecord produced no samples")
                                    }
                                    delay(EMPTY_READ_RETRY_DELAY_MS)
                                }
                            }
                            return count
                        }

                        // Phase 1: buffer audio while models initialize. A
                        // supervisor keeps initialization failure observable via
                        // await() instead of cancelling this parent silently.
                        withTimeout(MODEL_INITIALIZATION_TIMEOUT_MS) {
                            supervisorScope {
                                val initialization =
                                    async {
                                        recognizerProvider.ensureInitialized()
                                        vadProvider.ensureInitialized()
                                    }

                                while (isActive && isListening && initialization.isActive) {
                                    val shortsRead = readSamples()
                                    if (shortsRead > 0) {
                                        preBuffer.addLast(shortsToFloats(shortBuffer, shortsRead))
                                    }
                                }
                                initialization.await()
                            }
                        }

                        currentCoroutineContext().ensureActive()

                        // Phase 2: models ready — create stream and drain buffer through VAD
                        val s = recognizerProvider.createStream()
                        stream = s

                        for (samples in preBuffer) {
                            liveDecoder.processSamples(s, samples)
                        }
                        preBuffer.clear()

                        // Phase 3: live decode loop
                        while (isActive && isListening) {
                            val shortsRead = readSamples()
                            if (shortsRead <= 0) continue

                            liveDecoder.processSamples(s, shortsToFloats(shortBuffer, shortsRead))
                        }
                    } catch (e: TimeoutCancellationException) {
                        isListening = false
                        audioCapture.stop()
                        Napier.e("Live transcription model initialization timed out", e)
                        terminalizer.fail(TranscriptionFailure.NotAvailable)
                        vadProvider.reset()
                        releaseStream()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isListening && e is AudioCaptureException) return@launch
                        isListening = false
                        audioCapture.stop()
                        val reason =
                            if (e is AudioCaptureException) {
                                TranscriptionFailure.AudioError
                            } else {
                                TranscriptionFailure.NotAvailable
                            }
                        Napier.e("Live transcription failed after start", e)
                        terminalizer.fail(reason)
                        vadProvider.reset()
                        releaseStream()
                    }
                }

            Napier.d("Sherpa-ONNX recognition started (${SherpaOnnxRecognizerProvider.SAMPLE_RATE}Hz, mono, PCM 16-bit)")
            TranscriptionStartResult.Started
        } catch (e: Exception) {
            isListening = false
            audioCapture.stop()
            Napier.e("Failed to start Sherpa-ONNX transcription", e)
            val reason = TranscriptionFailure.AudioError
            if (sessionAccepted) {
                terminalizer.fail(reason)
            } else {
                _transcriptionFlow.emit(TranscriptionResult.Error(reason))
            }
            TranscriptionStartResult.Failed(reason)
        }
    }

    override suspend fun stopLiveTranscription(): TranscriptionResult {
        if (!isListening) {
            return _transcriptionFlow.replayCache.lastOrNull() ?: TranscriptionResult.Cancelled
        }
        isListening = false

        // Stop audio first so the recognition loop exits naturally
        audioCapture.stop()

        // Wait for the recognition coroutine to finish before touching the stream
        recognitionJob?.join()
        recognitionJob = null

        // Drain any speech the VAD was still mid-window on when audio capture
        // stopped — without flush() these trailing samples would be silently
        // dropped and the user's last few words would never reach the recognizer
        // (or the refinement buffer).
        try {
            val s = stream
            if (s != null) {
                vadProvider.flush()
                while (!vadProvider.isEmpty()) {
                    val segment = vadProvider.front()
                    vadProvider.pop()
                    liveDecoder.bufferUtteranceForRefinement(segment.samples)
                    liveDecoder.acceptWaveform(s, segment.samples)
                }
            }
        } catch (e: Exception) {
            Napier.e("Error flushing VAD on stop", e)
            terminalizer.fail(TranscriptionFailure.AudioError)
            vadProvider.reset()
            releaseStream()
            return TranscriptionResult.Error(TranscriptionFailure.AudioError)
        }

        // Now safe to get final result from the stream
        try {
            val s = stream
            if (s != null) {
                while (recognizerProvider.isReady(s)) {
                    recognizerProvider.decode(s)
                }
                val result = recognizerProvider.getResult(s)
                if (result.text.isNotBlank()) {
                    val punctuated = recognizerProvider.addPunctuation(result.text)
                    val utterance = liveDecoder.buildTimedUtterance(result, punctuated)
                    accumulator.addSegment(punctuated, utterance)
                }
            }
        } catch (e: Exception) {
            Napier.e("Error getting final transcription result", e)
            terminalizer.fail(TranscriptionFailure.Unknown)
            vadProvider.reset()
            releaseStream()
            return TranscriptionResult.Error(TranscriptionFailure.Unknown)
        }

        // Decide whether to refine. If Whisper is loaded and the buffer fits,
        // emit the streaming result with isRefining=true and start the
        // background rewrite. Otherwise emit a final-only Success.
        val canRefine = liveDecoder.hasRefinementUtterances && offlineRecognizerProvider.isAvailable
        val streamingText = accumulator.build()
        val streamingResult =
            TranscriptionResult.Success(
                text = streamingText,
                timedTranscript = accumulator.buildTimedTranscript(),
                isFinal = true,
                isRefining = canRefine,
            )
        val stopResult =
            if (streamingText.isBlank()) {
                TranscriptionResult.Error(TranscriptionFailure.NoSpeechDetected)
            } else {
                streamingResult
            }
        if (streamingText.isBlank()) {
            terminalizer.fail(TranscriptionFailure.NoSpeechDetected)
        } else if (canRefine) {
            terminalizer.progress(streamingResult)
        } else {
            terminalizer.complete(streamingResult)
        }

        liveDecoder.advanceStreamTiming()

        vadProvider.reset()
        releaseStream()

        if (canRefine && streamingText.isNotBlank()) {
            val utterances = liveDecoder.takeRefinementUtterances()
            refinementJob =
                scope.launch(Dispatchers.Default) {
                    transcriptProcessor.refine(
                        utterances = utterances,
                        streamingFallback = streamingResult.copy(isRefining = false),
                    )
                }
        }
        return stopResult
    }

    override suspend fun transcribeAudioFile(audioUri: String): TranscriptionResult = transcriptProcessor.transcribeAudioFile(audioUri)

    override suspend fun cancelTranscription() {
        isListening = false
        // Stop capture first so the recognition loop exits, then wait for it: the loop
        // touches the native stream and VAD, and releasing them underneath it is a
        // use-after-free in the JNI layer rather than a Kotlin exception.
        audioCapture.stop()
        recognitionJob?.cancelAndJoin()
        recognitionJob = null
        refinementJob?.cancelAndJoin()
        refinementJob = null
        liveDecoder.clearRefinementBuffer()
        vadProvider.reset()
        releaseStream()
        terminalizer.cancel()
    }

    override fun getSupportedLanguages(): List<String> = listOf("en-US")

    override fun setLanguage(languageCode: String) {
        Napier.d("Sherpa-ONNX language set request: $languageCode (only en-US supported)")
    }

    override val supportsLiveTranscription: Boolean = true

    override val supportsFileTranscription: Boolean = true

    override val isOfflineModelAvailable: Boolean
        get() = offlineRecognizerProvider.isAvailable

    private val modelManager by lazy { SherpaOnnxModelManager(context) }

    private val _offlineModelDownloadStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)

    override val offlineModelDownloadStatus: StateFlow<ModelDownloadStatus> = _offlineModelDownloadStatus.asStateFlow()

    private var offlineDownloadJob: Job? = null

    override fun startOfflineModelDownload() {
        if (offlineDownloadJob?.isActive == true) return
        if (isOfflineModelAvailable) {
            _offlineModelDownloadStatus.value = ModelDownloadStatus.Completed
            return
        }
        offlineDownloadJob =
            scope.launch(Dispatchers.IO) {
                try {
                    modelManager.downloadWhisperModel().collect { status ->
                        _offlineModelDownloadStatus.value = status
                    }
                } catch (e: Exception) {
                    Napier.e("Whisper download crashed", e)
                    _offlineModelDownloadStatus.value = ModelDownloadStatus.UnknownError
                }
            }
    }

    override suspend fun resetTranscription() {
        accumulator.reset()
        liveDecoder.resetTiming()

        if (isListening) {
            stopLiveTranscription()
            startLiveTranscription()
        }
    }

    override fun release() {
        isListening = false
        cancelJobs()
        liveDecoder.clearRefinementBuffer()
        audioCapture.stop()
        releaseStream()
        vadProvider.release()
        offlineRecognizerProvider.release()
        accumulator.reset()
        liveDecoder.resetTiming()
    }

    private fun cancelJobs() {
        recognitionJob?.cancel()
        recognitionJob = null
        refinementJob?.cancel()
        refinementJob = null
    }

    private fun shortsToFloats(
        shorts: ShortArray,
        count: Int,
    ): FloatArray {
        for (i in 0 until count) floatBuffer[i] = shorts[i] / 32768.0f
        return floatBuffer.copyOf(count)
    }

    private fun releaseStream() {
        try {
            stream?.release()
        } catch (e: Exception) {
            Napier.e("Error releasing Sherpa-ONNX stream", e)
        }
        stream = null
    }

    companion object {
        private const val BUFFER_SIZE_SHORTS = 2048
        private const val BUFFER_SIZE_BYTES = BUFFER_SIZE_SHORTS * 2
        private const val MAX_CONSECUTIVE_EMPTY_READS = 50
        private const val EMPTY_READ_RETRY_DELAY_MS = 10L
        private const val MODEL_INITIALIZATION_TIMEOUT_MS = 30_000L
    }
}
