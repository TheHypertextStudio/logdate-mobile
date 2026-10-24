@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package app.logdate.client.media.audio

import app.logdate.client.media.audio.iosbackground.AudioSessionEvent
import app.logdate.client.media.audio.iosbackground.IosAudioSessionController
import app.logdate.client.media.audio.transcription.TranscriptionService
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioRecorder
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSinceDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * iOS implementation of AudioRecordingManager that supports background audio recording
 * using AVFoundation and proper audio session configuration.
 */
class IosAudioRecordingManager(
    private val audioStorage: AudioStorage,
) : AudioRecordingManager {
    private var recorder: AVAudioRecorder? = null
    private var recordingURL: NSURL? = null
    private var recordingStartTime: NSDate? = null
    private val audioSessionController = IosAudioSessionController()
    private var recordingTarget: AudioRecordingTarget? = null

    private val audioLevelFlow = MutableStateFlow(0f)
    private val recordingDurationFlow = MutableStateFlow(Duration.ZERO)
    private val transcriptionFlow = MutableStateFlow<String?>(null)
    private var transcriptionService: TranscriptionService? = null

    private var recordingActive = false
    private var recordingPaused = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val isRecording: Boolean
        get() = recordingActive

    init {
        Napier.d("IosAudioRecordingManager initialized")
        scope.launch {
            audioSessionController.events.collect { event ->
                handleSessionEvent(event)
            }
        }
    }

    private suspend fun handleSessionEvent(event: AudioSessionEvent) {
        when (event) {
            AudioSessionEvent.InterruptionBegan -> {
                // iOS forcibly suspends the recorder when an interruption arrives; mirror that
                // in our own state so the UI doesn't keep showing "recording" while the input
                // is silenced.
                if (recordingActive && !recordingPaused) {
                    pauseRecording()
                }
            }

            is AudioSessionEvent.InterruptionEnded -> {
                if (event.shouldResume && recordingActive && recordingPaused) {
                    resumeRecording()
                }
            }

            AudioSessionEvent.OutputRouteRemoved -> {
                // Headphones unplugged or AirPlay disconnected — pause so the user notices
                // before audio routes through an unexpected speaker.
                if (recordingActive && !recordingPaused) {
                    pauseRecording()
                }
            }
        }
    }

    override suspend fun startRecording(targetNoteId: Uuid?): Boolean = startRecording(targetNoteId) { true }

    override suspend fun startRecording(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean {
        try {
            Napier.d("Starting iOS recording")

            val target = audioStorage.createRecordingTarget()
            if (!onTargetReady(target.path)) return false
            recordingTarget = target
            recordingURL = NSURL.fileURLWithPath(target.path)

            if (!audioSessionController.setupAudioSessionForRecording()) {
                Napier.e("Failed to configure audio session for recording")
                return false
            }

            val settings = mutableMapOf<Any?, Any?>()
            settings[platform.AVFAudio.AVFormatIDKey] = platform.CoreAudioTypes.kAudioFormatMPEG4AAC
            settings[platform.AVFAudio.AVSampleRateKey] = 44100.0
            settings[platform.AVFAudio.AVNumberOfChannelsKey] = 2
            settings[platform.AVFAudio.AVEncoderAudioQualityKey] = platform.AVFAudio.AVAudioQualityHigh

            var recorderError: NSError? = null
            recordingURL?.let { url ->
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    recorder =
                        AVAudioRecorder(
                            uRL = url,
                            settings = settings,
                            error = errorPtr.ptr,
                        )
                    recorderError = errorPtr.value
                }
            }

            if (recorderError != null) {
                Napier.e("Failed to create audio recorder: ${recorderError.localizedDescription}")
                return false
            }

            recorder?.let { rec ->
                rec.prepareToRecord()
                val success = rec.record()
                if (!success) {
                    Napier.e("Failed to start recording")
                    return false
                }

                recordingActive = true
                recordingPaused = false
                recordingStartTime = NSDate()
                startMonitoring()
                Napier.d("iOS recording started successfully")
                return true
            }

            Napier.e("Recorder not initialized")
            return false
        } catch (e: Exception) {
            Napier.e("Error starting iOS recording: ${e.message}", e)
            audioSessionController.endAudioSession()
            return false
        }
    }

    override suspend fun stopRecording(): String? {
        if (!recordingActive) {
            return null
        }

        return try {
            Napier.d("Stopping iOS recording")
            recorder?.stop()
            audioSessionController.endAudioSession()

            recordingActive = false
            recordingPaused = false
            recordingDurationFlow.value = Duration.ZERO
            audioLevelFlow.value = 0f

            val path = recordingURL?.path
            recorder = null
            Napier.d("iOS recording stopped, path: $path")
            path
        } catch (e: Exception) {
            Napier.e("Error stopping iOS recording: ${e.message}", e)
            audioSessionController.endAudioSession()
            recorder = null
            recordingActive = false
            null
        }
    }

    override suspend fun pauseRecording(): Boolean {
        if (!recordingActive || recordingPaused) {
            return false
        }

        return try {
            recorder?.pause()
            recordingPaused = true
            Napier.d("iOS recording paused")
            true
        } catch (e: Exception) {
            Napier.e("Error pausing iOS recording: ${e.message}", e)
            false
        }
    }

    override suspend fun resumeRecording(): Boolean {
        if (!recordingActive || !recordingPaused) {
            return false
        }

        return try {
            recorder?.record()
            recordingPaused = false
            Napier.d("iOS recording resumed")
            true
        } catch (e: Exception) {
            Napier.e("Error resuming iOS recording: ${e.message}", e)
            false
        }
    }

    override fun getAudioLevelFlow(): Flow<Float> = audioLevelFlow

    override fun getRecordingDurationFlow(): Flow<Duration> = recordingDurationFlow

    override fun getTranscriptionFlow(): Flow<String?> = transcriptionFlow

    override fun setTranscriptionService(service: TranscriptionService) {
        transcriptionService = service
    }

    override fun release() {
        try {
            Napier.d("Clearing iOS recording resources")
            if (recordingActive) {
                recorder?.stop()
            }

            audioSessionController.endAudioSession()
            recorder = null
            recordingActive = false
            recordingPaused = false
            audioLevelFlow.value = 0f
            recordingDurationFlow.value = Duration.ZERO
            recordingTarget = null
            Napier.d("iOS recording resources cleared")
        } catch (e: Exception) {
            Napier.e("Error clearing iOS recording resources: ${e.message}", e)
        }
    }

    private fun startMonitoring() {
        scope.launch {
            while (recordingActive) {
                if (!recordingPaused) {
                    recordingStartTime?.let { startTime ->
                        val duration = (NSDate().timeIntervalSinceDate(startTime) * 1000).toLong()
                        recordingDurationFlow.update { duration.milliseconds }
                    }

                    recorder?.let { rec ->
                        rec.updateMeters()
                        val level = (rec.averagePowerForChannel(0uL) + 160.0f) / 160.0f
                        audioLevelFlow.value = level.coerceIn(0.0f, 1.0f)
                    }
                }

                delay(100)
            }
        }
    }
}
