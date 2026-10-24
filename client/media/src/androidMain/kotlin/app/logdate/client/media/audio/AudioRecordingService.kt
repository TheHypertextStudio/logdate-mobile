package app.logdate.client.media.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.logdate.client.media.device.AndroidAudioRouteDevices
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logdate.client.media.generated.resources.Res
import logdate.client.media.generated.resources.media_device_microphone_switch_failed
import java.io.File

/**
 * Extension function to start the recording service
 */
fun Context.startAudioRecordingService(
    outputFilePath: String? = null,
    inputDeviceId: String? = null,
    options: RecordingSessionOptions = RecordingSessionOptions(),
) {
    val intent =
        Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.SERVICE_ACTION_START
            if (outputFilePath != null) {
                putExtra(AudioRecordingService.EXTRA_OUTPUT_PATH, outputFilePath)
            }
            if (inputDeviceId != null) {
                putExtra(AudioRecordingService.EXTRA_INPUT_DEVICE_ID, inputDeviceId)
            }
            putExtra(AudioRecordingService.EXTRA_MAX_DURATION_MS, options.maxDurationMs)
            putExtra(AudioRecordingService.EXTRA_PAUSE_ON_INTERRUPTION, options.pauseOnInterruption)
            putExtra(AudioRecordingService.EXTRA_HOLD_WAKE_LOCK, options.holdWakeLock)
            putExtra(AudioRecordingService.EXTRA_CRASH_SAFE, options.crashSafe)
            putExtra(AudioRecordingService.EXTRA_EXCLUDE_INCOMPLETE_FROM_BACKUP, options.excludeIncompleteFromBackup)
            putExtra(AudioRecordingService.EXTRA_QUIET_SAVE, options.quietSave)
        }
    startForegroundService(intent)
}

/**
 * Extension function to stop the recording service
 */
fun Context.stopAudioRecordingService() {
    val intent =
        Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.SERVICE_ACTION_STOP
        }
    stopService(intent)
}

/**
 * Android foreground service for audio recording.
 *
 * Handles recording in the background with a persistent notification.
 * Provides binding for clients to interact with the recording.
 *
 * The recorder is prepared and started off the main thread; [recordingState] reports the
 * outcome so a bound client can wait for a confirmed start (or an error) instead of
 * assuming one. Every recorder transition is serialized on [recorderLock] because the
 * bound client, the notification actions, and [onDestroy] reach it from different threads.
 */
class AudioRecordingService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val SERVICE_ACTION_START = "app.logdate.action.START_RECORDING"
        const val SERVICE_ACTION_STOP = "app.logdate.action.STOP_RECORDING"
        const val SERVICE_ACTION_PAUSE = AndroidAudioNotificationHandler.ACTION_PAUSE
        const val SERVICE_ACTION_RESUME = AndroidAudioNotificationHandler.ACTION_RESUME
        const val EXTRA_OUTPUT_PATH = "app.logdate.extra.OUTPUT_PATH"
        const val EXTRA_INPUT_DEVICE_ID = "app.logdate.extra.INPUT_DEVICE_ID"
        const val EXTRA_MAX_DURATION_MS = "app.logdate.extra.MAX_DURATION_MS"
        const val EXTRA_PAUSE_ON_INTERRUPTION = "app.logdate.extra.PAUSE_ON_INTERRUPTION"
        const val EXTRA_HOLD_WAKE_LOCK = "app.logdate.extra.HOLD_WAKE_LOCK"
        const val EXTRA_CRASH_SAFE = "app.logdate.extra.CRASH_SAFE"
        const val EXTRA_EXCLUDE_INCOMPLETE_FROM_BACKUP = "app.logdate.extra.EXCLUDE_INCOMPLETE_FROM_BACKUP"
        const val EXTRA_QUIET_SAVE = "app.logdate.extra.QUIET_SAVE"
        internal const val WAKE_LOCK_TAG = "LogDate:AudioRecordingWakeLock"
        internal const val WAKE_LOCK_HEADROOM_MS = 60_000L
        internal const val DEFAULT_WAKE_LOCK_TIMEOUT_MS = 35 * 60_000L
        internal const val LEVEL_POLL_INTERVAL_MS = 100L
        internal const val DURATION_TICK_MS = 1000L
        internal const val MAX_AMPLITUDE = 32768f
    }

    // Service binder for clients
    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    internal val binder = AudioServiceBinder()

    // Coroutine scope for service operations
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Notification handler
    internal lateinit var notificationHandler: AndroidAudioNotificationHandler

    // Recording state, guarded by recorderLock
    internal val recorderLock = Any()
    internal var mediaRecorder: MediaRecorder? = null
    internal var outputFile: File? = null
    internal var recordingStartTime: Long = 0

    @Volatile
    internal var isPaused: Boolean = false

    @Volatile
    internal var destroyed: Boolean = false

    /** Counts start requests, so a stop that finishes late can tell a newer session now owns the service. */
    @Volatile
    internal var sessionGeneration: Int = 0

    internal var wakeLock: PowerManager.WakeLock? = null
    internal var wakeLockAcquiredAt = 0L
    internal var focusRequest: AudioFocusRequest? = null
    internal var options = RecordingSessionOptions()
    internal val quietSaveDetector = QuietSaveDetector()

    internal val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Napier.d("Audio focus lost ($change); pausing the recording")
                pauseRecording(byInterruption = true)
            }
        }

    // State flow for UI updates
    internal val mutableRecordingState = MutableStateFlow(RecordingServiceState())
    val recordingState = mutableRecordingState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        notificationHandler = AndroidAudioNotificationHandler(this)
        Napier.d("Audio recording service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            SERVICE_ACTION_START -> {
                Napier.d("Starting audio recording service")
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                val inputDeviceId = intent.getStringExtra(EXTRA_INPUT_DEVICE_ID)
                options =
                    RecordingSessionOptions(
                        maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 0L),
                        pauseOnInterruption = intent.getBooleanExtra(EXTRA_PAUSE_ON_INTERRUPTION, false),
                        holdWakeLock = intent.getBooleanExtra(EXTRA_HOLD_WAKE_LOCK, false),
                        crashSafe = intent.getBooleanExtra(EXTRA_CRASH_SAFE, false),
                        excludeIncompleteFromBackup = intent.getBooleanExtra(EXTRA_EXCLUDE_INCOMPLETE_FROM_BACKUP, false),
                        quietSave = intent.getBooleanExtra(EXTRA_QUIET_SAVE, false),
                    )
                sessionGeneration++
                startForegroundRecording(outputPath, inputDeviceId)
            }

            SERVICE_ACTION_STOP -> {
                Napier.d("Stopping audio recording service")
                val session = sessionGeneration
                // Finishing a crash-safe recording copies the whole file, which must not block the main thread.
                serviceScope.launch {
                    if (sessionGeneration != session) return@launch
                    stopRecording()
                    // A start that arrived while the file was being finished owns the service now.
                    if (sessionGeneration != session) return@launch
                    releaseSessionResources()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }

            SERVICE_ACTION_PAUSE -> {
                Napier.d("Pausing audio recording")
                pauseRecording()
            }

            SERVICE_ACTION_RESUME -> {
                Napier.d("Resuming audio recording")
                resumeRecording()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        Napier.d("Audio recording service destroyed")
        destroyed = true
        if (options.crashSafe && mutableRecordingState.value.isRecording) {
            // Wrapping a long recording must not block the main thread. The thread outlives the service
            // and drops the wake lock only once the file is finished.
            Thread({
                stopRecording()
                releaseSessionResources()
            }, "recording-finalizer").start()
        } else {
            stopRecording()
            releaseSessionResources()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel() // Cancel all coroutines
        super.onDestroy()
    }

    /**
     * Enters the foreground with the recording notification, then prepares the recorder off
     * the main thread. Any failure lands in [recordingState] as an error so the bound client
     * can report it instead of waiting on a recorder that never starts.
     */
    internal fun startForegroundRecording(
        outputPath: String?,
        inputDeviceId: String?,
    ) {
        try {
            val notification = notificationHandler.createRecordingNotification(true, System.currentTimeMillis())

            // Start as a foreground service with the microphone type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Napier.e("Error starting foreground service", e)
            mutableRecordingState.update {
                it.copy(isRecording = false, failureMessage = "Recording is not allowed right now: ${e.message}")
            }
            stopSelf()
            return
        }

        serviceScope.launch { startRecording(outputPath, inputDeviceId) }
    }

    /**
     * Pauses the current recording
     */
    internal fun pauseRecording(byInterruption: Boolean = false) {
        if (!mutableRecordingState.value.isRecording || isPaused) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                synchronized(recorderLock) { mediaRecorder?.pause() }
                isPaused = true
                synchronized(recorderLock) { quietSaveDetector.reset() }
                releaseWakeLock()
                mutableRecordingState.update { it.copy(isPaused = true, pausedByInterruption = byInterruption) }
            } else {
                Napier.w("Pause recording not supported below Android N")
                return
            }

            // Update notification to show paused state
            notificationHandler.updateRecordingNotification(
                isRecording = false,
                startTimeMillis = recordingStartTime,
                recordedMillis = recordedMillis(),
            )

            Napier.d("Recording paused")
        } catch (e: Exception) {
            Napier.e("Error pausing recording", e)
        }
    }

    /**
     * Resumes a paused recording
     */
    internal fun resumeRecording() {
        if (!mutableRecordingState.value.isRecording || !isPaused) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                synchronized(recorderLock) { mediaRecorder?.resume() }
                isPaused = false
                synchronized(recorderLock) { quietSaveDetector.reset() }
                acquireWakeLock()
                mutableRecordingState.update { it.copy(isPaused = false, pausedByInterruption = false) }
            } else {
                Napier.w("Resume recording not supported below Android N")
                return
            }

            // Update notification to show recording state
            notificationHandler.updateRecordingNotification(
                isRecording = true,
                startTimeMillis = recordingStartTime,
                recordedMillis = recordedMillis(),
            )

            Napier.d("Recording resumed")
        } catch (e: Exception) {
            Napier.e("Error resuming recording", e)
        }
    }

    /**
     * Prepares and starts the recorder. A start that arrives while a recording is already
     * running finalizes that recording first so its file is playable and the old recorder
     * does not leak the microphone.
     */
    internal fun startRecording(
        outputPath: String?,
        inputDeviceId: String?,
    ) {
        synchronized(recorderLock) {
            if (destroyed) {
                Napier.w("Ignoring a start that arrived after the service was destroyed")
                return
            }
            if (mutableRecordingState.value.isRecording) {
                Napier.w("A start arrived while recording; finalizing the previous recording first")
                stopRecordingLocked()
            }
            var recorder: MediaRecorder? = null
            var recordingFile: File? = null
            try {
                mutableRecordingState.update { it.copy(routedInputDeviceId = null, inputRoutingError = null) }
                val file = resolveOutputFile(outputPath)
                outputFile = file
                recordingFile = recordingFileFor(file)
                recorder = createRecorder(requireNotNull(recordingFile), inputDeviceId)
                recorder.prepare()
                recorder.start()
                mediaRecorder = recorder
                recordingStartTime = System.currentTimeMillis()
                isPaused = false
                synchronized(recorderLock) { quietSaveDetector.reset() }
                acquireSessionResources()
                mutableRecordingState.update {
                    it.copy(
                        isRecording = true,
                        isPaused = false,
                        pausedByInterruption = false,
                        startTime = recordingStartTime,
                        recordedFilePath = null,
                        failureMessage = null,
                        quietSaveTriggered = false,
                    )
                }
                publishInputRoute(recorder)
                monitorRecording(requireNotNull(recordingFile))
                Napier.d("Recording started successfully")
            } catch (e: Exception) {
                Napier.e("Failed to start recording", e)
                releaseQuietly(recorder)
                recordingFile?.delete()
                mediaRecorder = null
                mutableRecordingState.update {
                    it.copy(isRecording = false, failureMessage = "Failed to start recording: ${e.message}")
                }
            }
        }
    }

    /**
     * Stops the current recording and finalizes its file.
     * @return The path to the recorded file, or null if nothing was recording or the file
     *   could not be finalized
     */
    fun stopRecording(): String? = synchronized(recorderLock) { stopRecordingLocked() }

    internal fun stopRecordingLocked(): String? {
        if (!mutableRecordingState.value.isRecording) {
            return null
        }
        val recorder = mediaRecorder
        mediaRecorder = null
        return try {
            recorder?.stop()
            if (recorder != null && options.excludeIncompleteFromBackup) {
                outputFile?.let { finalFile ->
                    val stagedFile =
                        if (options.crashSafe) {
                            CrashSafeRecording.inFlightFile(finalFile, isolateForBackup = true)
                        } else {
                            CrashSafeRecording.backupExcludedFile(finalFile)
                        }
                    CrashSafeRecording.markCompletedForRetry(stagedFile)
                }
            }
            recorder?.release()
            val path = finalizeOutputFile()
            mutableRecordingState.update {
                it.copy(isRecording = false, isPaused = false, pausedByInterruption = false, recordedFilePath = path)
            }
            path
        } catch (e: Exception) {
            Napier.e("Error stopping recording", e)
            releaseQuietly(recorder)
            mutableRecordingState.update {
                it.copy(
                    isRecording = false,
                    isPaused = false,
                    pausedByInterruption = false,
                    recordedFilePath = null,
                    failureMessage = "Error stopping recording: ${e.message}",
                )
            }
            null
        }
    }

    /**
     * Gets the recorded file path
     */
    fun getRecordedFilePath(): String? = outputFile?.absolutePath

    /**
     * Checks if recording is currently paused
     */
    fun isRecordingPaused(): Boolean = isPaused && mutableRecordingState.value.isRecording

    /** How much has been recorded so far, without pauses, to the second the duration ticker counts. */
    internal fun recordedMillis(): Long = mutableRecordingState.value.durationSeconds * 1_000L

    /**
     * Re-applies the preferred microphone route while recording is active.
     *
     * This gives the service a chance to follow route changes or device hot-plugs
     * after the recording session has already started.
     */
    fun updatePreferredInputDevice(inputDeviceId: String?) {
        if (!mutableRecordingState.value.isRecording) return
        synchronized(recorderLock) { quietSaveDetector.reset() }
        synchronized(recorderLock) { mediaRecorder?.applyPreferredInputDevice(inputDeviceId) }
    }

    internal fun MediaRecorder.applyPreferredInputDevice(inputDeviceId: String?) {
        val preferredDevice = AndroidAudioRouteDevices.findPreferredInputDevice(this@AudioRecordingService, inputDeviceId)
        try {
            val applied = setPreferredDevice(preferredDevice)
            mutableRecordingState.update {
                it.copy(inputRoutingError = if (applied) null else Res.string.media_device_microphone_switch_failed.toUiText())
            }
            if (this === mediaRecorder) publishInputRoute(this)
            Napier.d("Preferred recording microphone route applied=$applied")
        } catch (e: Exception) {
            Napier.e("Could not apply the recording microphone route", e)
            mutableRecordingState.update { it.copy(inputRoutingError = Res.string.media_device_microphone_switch_failed.toUiText()) }
        }
    }
}

/**
 * Represents the current state of the recording service
 */
data class RecordingServiceState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    /** True when the pause came from losing audio focus (a call, another app) rather than the user. */
    val pausedByInterruption: Boolean = false,
    val startTime: Long = 0,
    val durationSeconds: Int = 0,
    val audioLevel: Float = 0f,
    val routedInputDeviceId: String? = null,
    /** Why the recorder could not use the requested microphone, shown beside the microphone picker. */
    val inputRoutingError: UiText? = null,
    val recordedFilePath: String? = null,
    /** What went wrong when the recorder failed, for logs. The person sees the editor's own message. */
    val failureMessage: String? = null,
    val quietSaveTriggered: Boolean = false,
)
