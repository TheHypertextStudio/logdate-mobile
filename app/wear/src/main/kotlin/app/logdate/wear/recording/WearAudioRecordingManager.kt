package app.logdate.wear.recording

import app.logdate.client.media.audio.AudioRecordingTarget
import app.logdate.client.media.audio.AudioStorage
import app.logdate.client.media.audio.CrashSafeRecording
import app.logdate.client.media.audio.RecordingServiceController
import app.logdate.client.media.audio.RecordingServiceState
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.wear.data.storage.StorageSpaceChecker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Whether the app may use the microphone right now. Checked at the moment recording starts. */
fun interface MicrophonePermissionChecker {
    fun isGranted(): Boolean
}

/**
 * Wear OS [WearRecorder] over the shared foreground recording service.
 *
 * All session transitions run under one mutex. A start reports success only once the service
 * confirms the recorder is running, and a stop finalizes the file through the service before the
 * service is torn down, so the path handed back always points at a complete recording. A session
 * the service ends on its own (the length limit) is handed back by the next [stop].
 */
class WearAudioRecordingManager(
    private val storageChecker: StorageSpaceChecker,
    private val audioStorage: AudioStorage,
    private val audioRouteRepository: AudioRouteRepository,
    private val serviceController: RecordingServiceController,
    private val microphonePermission: MicrophonePermissionChecker,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val startTimeout: Duration = DEFAULT_START_TIMEOUT,
    private val canCapture: suspend () -> Boolean = { true },
) : WearRecorder {
    companion object {
        /** Longest recording. About 29 MB of AAC, under the phone's 31 MiB upload ceiling. */
        val MAX_RECORDING_DURATION: Duration = 30.minutes

        private val DEFAULT_START_TIMEOUT = 10.seconds
        private const val BYTES_PER_SECOND = 128_000L / 8
        private const val STORAGE_HEADROOM_BYTES = 8L * 1024 * 1024

        /** The raw recording and the m4a it is wrapped into exist side by side until the note is saved. */
        val REQUIRED_STORAGE_BYTES: Long = 2 * MAX_RECORDING_DURATION.inWholeSeconds * BYTES_PER_SECOND + STORAGE_HEADROOM_BYTES
    }

    private val scope = CoroutineScope(SupervisorJob() + workDispatcher)
    private val sessionMutex = Mutex()
    private val recordingStateFlow = MutableStateFlow(false)
    private val pausedFlow = MutableStateFlow(false)
    private val interruptedFlow = MutableStateFlow(false)
    private val audioLevelFlow = MutableStateFlow(0f)
    private val elapsedFlow = MutableStateFlow(Duration.ZERO)

    @Volatile
    private var recordingTarget: AudioRecordingTarget? = null

    /** File the service reported when it ended the session on its own; handed back by the next stop. */
    @Volatile
    private var externallyRecordedPath: String? = null

    @Volatile
    private var startInFlight = false
    private var serviceStateJob: Job? = null
    private var routeSyncJob: Job? = null

    @Volatile
    override var lastStartFailure: RecordingStartFailure? = null
        private set

    override val isRecording: StateFlow<Boolean> = recordingStateFlow.asStateFlow()
    override val isPaused: StateFlow<Boolean> = pausedFlow.asStateFlow()
    override val pausedByInterruption: StateFlow<Boolean> = interruptedFlow.asStateFlow()
    override val audioLevel: StateFlow<Float> = audioLevelFlow.asStateFlow()
    override val elapsed: StateFlow<Duration> = elapsedFlow.asStateFlow()

    override suspend fun start(): Boolean =
        withContext(workDispatcher) {
            sessionMutex.withLock {
                startInFlight = true
                try {
                    startSessionLocked()
                } finally {
                    startInFlight = false
                }
            }
        }

    private suspend fun startSessionLocked(): Boolean {
        if (!canCapture()) return failStart(RecordingStartFailure.RECORDER_UNAVAILABLE)
        if (recordingStateFlow.value) {
            Napier.w("Attempted to start recording while already recording")
            return false
        }
        lastStartFailure = null
        if (!microphonePermission.isGranted()) return failStart(RecordingStartFailure.MICROPHONE_PERMISSION_DENIED)
        if (storageChecker.getAvailableStorageSpace() < REQUIRED_STORAGE_BYTES) {
            return failStart(RecordingStartFailure.NOT_ENOUGH_STORAGE)
        }
        val target =
            try {
                audioStorage.createRecordingTarget()
            } catch (e: Exception) {
                Napier.e("Could not create a recording target", e)
                return failStart(RecordingStartFailure.RECORDER_UNAVAILABLE)
            }

        recordingTarget = target
        externallyRecordedPath = null
        resetLiveState()

        val started = serviceController.start(target.path, audioRouteRepository.inputDevices.value.selectedDeviceId)
        if (!started || awaitServiceStarted() == null) {
            serviceController.shutdown()
            deleteQuietly(target.path)
            recordingTarget = null
            return failStart(RecordingStartFailure.RECORDER_UNAVAILABLE)
        }
        recordingStateFlow.value = true
        observeService()
        return true
    }

    private fun failStart(reason: RecordingStartFailure): Boolean {
        lastStartFailure = reason
        return false
    }

    /**
     * Waits for the service to report the recorder is running. Returns null when the service
     * reports an error or never confirms within [startTimeout], which covers a foreground start
     * the platform refused and a recorder that failed to prepare.
     */
    private suspend fun awaitServiceStarted(): RecordingServiceState? {
        val state =
            withTimeoutOrNull(startTimeout) {
                serviceController.serviceState
                    .filterNotNull()
                    .first { it.isRecording || it.failureMessage != null }
            }
        return when {
            state == null -> {
                Napier.e("Recording service did not confirm a start within $startTimeout")
                null
            }

            !state.isRecording -> {
                Napier.e("Recording service failed to start: ${state.failureMessage}")
                null
            }

            else -> {
                state
            }
        }
    }

    private fun observeService() {
        serviceStateJob?.cancel()
        serviceStateJob = scope.launch { serviceController.serviceState.collect(::onServiceState) }
        routeSyncJob?.cancel()
        routeSyncJob =
            scope.launch {
                audioRouteRepository.inputDevices.collect { selection ->
                    if (recordingStateFlow.value) serviceController.updatePreferredInputDevice(selection.selectedDeviceId)
                }
            }
    }

    private fun onServiceState(state: RecordingServiceState?) {
        if (state == null) {
            if (recordingStateFlow.value) {
                Napier.w("Recording service went away mid-session")
                recordingStateFlow.value = false
                pausedFlow.value = false
            }
            return
        }
        audioLevelFlow.value = state.audioLevel
        elapsedFlow.value = (state.durationSeconds * 1000L).milliseconds
        interruptedFlow.value = state.pausedByInterruption
        pausedFlow.value = state.isPaused
        if (!state.isRecording && recordingStateFlow.value) {
            Napier.d("Recording service stopped on its own; file=${state.recordedFilePath} error=${state.failureMessage}")
            externallyRecordedPath = state.recordedFilePath
            recordingStateFlow.value = false
            pausedFlow.value = false
        }
    }

    override suspend fun stop(): String? =
        withContext(workDispatcher) {
            sessionMutex.withLock { stopSessionLocked() }
        }

    override suspend fun discard() {
        withContext(workDispatcher) {
            sessionMutex.withLock {
                val target = recordingTarget?.path
                val finalized = stopSessionLocked()
                (finalized ?: target)?.let(::deleteQuietly)
            }
        }
    }

    private fun stopSessionLocked(): String? {
        if (!recordingStateFlow.value && externallyRecordedPath == null) {
            Napier.w("Attempted to stop recording while not recording")
            return null
        }
        serviceStateJob?.cancel()
        serviceStateJob = null
        routeSyncJob?.cancel()
        routeSyncJob = null

        return try {
            finalizeServiceRecording()
        } catch (e: Exception) {
            Napier.e("Error finalizing the recording", e)
            null
        } finally {
            serviceController.shutdown()
            recordingStateFlow.value = false
            pausedFlow.value = false
            interruptedFlow.value = false
            recordingTarget = null
            externallyRecordedPath = null
        }
    }

    /**
     * Asks the bound service to finalize the file. When the service already ended the session on
     * its own, the path it reported then is used instead.
     */
    private fun finalizeServiceRecording(): String? {
        val stopped = serviceController.stopRecordingNow()
        if (stopped != null) return stopped
        val snapshot = serviceController.serviceState.value
        val reported = snapshot?.recordedFilePath ?: externallyRecordedPath
        if (reported == null) Napier.e("Recording produced no file: ${snapshot?.failureMessage ?: "service not bound"}")
        return reported
    }

    override suspend fun pause(): Boolean =
        withContext(workDispatcher) {
            sessionMutex.withLock {
                if (!recordingStateFlow.value) return@withLock false
                try {
                    serviceController.pause()
                } catch (e: Exception) {
                    Napier.e("Error pausing recording", e)
                    false
                }
            }
        }

    override suspend fun resume(): Boolean =
        withContext(workDispatcher) {
            sessionMutex.withLock {
                if (!recordingStateFlow.value) return@withLock false
                try {
                    serviceController.resume()
                } catch (e: Exception) {
                    Napier.e("Error resuming recording", e)
                    false
                }
            }
        }

    /** The stopped file is left on disk for startup recovery to find. */
    override fun requestStop() {
        if (!recordingStateFlow.value && !startInFlight && externallyRecordedPath == null) return
        scope.launch {
            try {
                stop()
            } catch (e: Exception) {
                Napier.e("Error stopping recording from a background request", e)
            }
        }
    }

    private fun resetLiveState() {
        audioLevelFlow.value = 0f
        elapsedFlow.value = Duration.ZERO
        pausedFlow.value = false
        interruptedFlow.value = false
    }

    /**
     * Deletes the recording and the raw file a crash-safe session writes before it is wrapped, which
     * remains when wrapping failed. Left behind, that raw file would be recovered as a note at the
     * next launch.
     */
    private fun deleteQuietly(path: String) {
        val file = File(path)
        listOf(file, CrashSafeRecording.inFlightFile(file)).forEach { target ->
            runCatching { target.delete() }
                .onFailure { error -> Napier.w("Could not delete ${target.path}", error) }
        }
    }
}
