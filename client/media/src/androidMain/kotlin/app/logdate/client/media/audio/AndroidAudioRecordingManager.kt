package app.logdate.client.media.audio

import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.repository.audio.AudioTagRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Implementation of AudioRecordingManager for Android.
 *
 * All session transitions run under one mutex on [workDispatcher], off the main thread. A
 * start only reports success once the foreground service confirms the recorder is running,
 * and a stop finalizes the file through the service binder before the service is torn down,
 * so the path handed back always points at a complete recording. The singleton stays usable
 * after any failure: nothing here cancels its scope or releases the shared transcription
 * service.
 */
class AndroidAudioRecordingManager(
    internal val audioStorage: AudioStorage,
    internal val transcriptionRepository: TranscriptionRepository,
    internal val audioTaggingService: AudioTaggingService,
    internal val audioTagRepository: AudioTagRepository,
    internal val audioRouteRepository: AudioRouteRepository,
    internal val serviceController: RecordingServiceController,
    internal val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    internal val startTimeout: Duration = DEFAULT_START_TIMEOUT,
    internal val draftSession: RecordingDraftLifecycle? = null,
    internal val quietSaveEnabled: suspend () -> Boolean = { false },
    internal val onQuietSave: suspend (Uuid) -> Unit = {},
    internal val finalizedFileAvailable: (String) -> Boolean = { path -> java.io.File(path).let { it.isFile && it.length() > 0 } },
) : AudioRecordingManager {
    internal val scope = CoroutineScope(SupervisorJob() + workDispatcher)
    internal val sessionMutex = Mutex()
    internal val recordingStateFlow = MutableStateFlow(false)
    internal val recordingPausedFlow = MutableStateFlow(false)
    internal val audioLevelFlow = MutableStateFlow(0f)
    internal val durationFlow = MutableStateFlow(0L)
    internal val transcriptionFlow = MutableStateFlow<String?>(null)
    internal val structuredTranscriptionFlow = MutableStateFlow<TranscriptionResult?>(null)
    internal val recordingDurationFlow: Flow<Duration> = durationFlow.map { it.milliseconds }

    @Volatile
    internal var transcriptionService: TranscriptionService? = null

    @Volatile
    internal var recordingTarget: AudioRecordingTarget? = null

    /**
     * The eventual saved-note UUID for the current recording session, captured
     * at [startRecording] time. Refined transcription emissions are written to
     * the repository under this id so the polished transcript persists across
     * the editor view-model lifecycle and shows up in the saved note later.
     */
    @Volatile
    internal var sessionTargetNoteId: Uuid? = null

    @Volatile
    internal var sessionDraftId: Uuid? = null

    /**
     * File the service reported when it ended the session on its own (notification action,
     * recorder failure). Handed back by the next [stopRecording] so the editor still gets
     * the recording it was making.
     */
    @Volatile
    internal var externallyRecordedPath: String? = null

    /**
     * True while [startSessionLocked] is waiting on the service. A stop requested in that
     * window (the editor closing mid-start) must queue behind the start instead of being
     * dropped, or the recorder it is about to confirm would run with nobody to stop it.
     */
    @Volatile
    internal var startInFlight = false
    internal val recordingStartingFlow = MutableStateFlow(false)

    @Volatile
    internal var sessionGeneration = 0L

    @Volatile
    internal var liveTranscriptionStarted = false

    /**
     * The in-flight (or most recently finished) call to [startLiveTranscription]. Recording
     * is confirmed to the caller as soon as the platform recorder is running; transcription
     * setup -- a second, separate microphone acquisition -- runs alongside it instead of
     * serially after it, since the app treats transcription as best-effort and recording
     * must not wait on it. [stopSessionLocked] and [pauseRecording] join this job first so
     * they never race a still-initializing transcription session.
     */
    internal var transcriptionStartJob: Job? = null
    internal var transcriptionSessionInitialized = false
    internal var serviceStateJob: Job? = null
    internal var quietSaveTriggered = false

    @Volatile
    internal var serviceEnded = false
    internal var recordingDraftSaved = false
    internal var lastCheckpointSeconds = 0
    internal var lastCheckpointPaused = false
    internal var routeSyncJob: Job? = null
    internal val transcriptPersistenceLock = Any()
    internal val transcriptionInputRouter =
        TranscriptionInputRouter {
            structuredTranscriptionFlow.value = TranscriptionResult.Error(TranscriptionFailure.AudioError)
        }
    internal val transcriptPersistenceJobs = mutableMapOf<Uuid, Job>()
    internal var transcriptionCollectorJob: Job? = null
    internal var transcriptionWarmUpJob: Job? = null
    internal val transcriptWriter = RecordingTranscriptWriter(transcriptionRepository)
    internal var recordingTranscriptSession: RecordingTranscriptSession? = null

    internal var quietSaveForSession = false

    override val currentRecordingDraftId: Uuid?
        get() = recordingTarget?.let { sessionDraftId }

    override val continuesWithoutEditor: Boolean
        get() = quietSaveForSession && isRecording

    override val isRecording: Boolean
        get() = recordingStateFlow.value

    override val isStartingRecording: Boolean
        get() = startInFlight

    override fun getRecordingStartingFlow(): StateFlow<Boolean> = recordingStartingFlow.asStateFlow()

    override val currentRecordingPath: String?
        get() = recordingTarget?.path

    override val currentRecordingTargetNoteId: Uuid?
        get() = recordingTarget?.let { sessionTargetNoteId }

    override val currentRecordingPaused: Boolean
        get() = recordingPausedFlow.value

    override fun getRecordingPausedFlow(): StateFlow<Boolean> = recordingPausedFlow.asStateFlow()

    override fun getRecordingStateFlow(): StateFlow<Boolean> = recordingStateFlow.asStateFlow()

    override fun setTranscriptionService(service: TranscriptionService) = bindTranscriptionService(service)

    override suspend fun startRecording(targetNoteId: Uuid?): Boolean = startRecording(targetNoteId) { true }

    override suspend fun startRecording(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean =
        withContext(workDispatcher) {
            sessionMutex.withLock {
                startInFlight = true
                recordingStartingFlow.value = true
                try {
                    startSessionLocked(targetNoteId, onTargetReady)
                } finally {
                    startInFlight = false
                    recordingStartingFlow.value = false
                }
            }
        }

    private suspend fun startSessionLocked(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean {
        if (recordingStateFlow.value || (externallyRecordedPath != null && !recordingDraftSaved)) {
            Napier.w("Attempted to start recording while a session is active or awaiting handoff")
            return false
        }
        sessionGeneration++
        sessionDraftId = null
        val target = prepareRecordingTarget(audioStorage, onTargetReady) ?: return false
        recordingTarget = target
        recordingDraftSaved = false
        sessionTargetNoteId = targetNoteId
        recordingTranscriptSession = beginTranscriptSession(targetNoteId, target.path)
        externallyRecordedPath = null
        liveTranscriptionStarted = false
        transcriptionInputRouter.reset()
        transcriptionFlow.value = null
        structuredTranscriptionFlow.value = null

        var confirmed = false
        try {
            if (targetNoteId != null) {
                draftSession?.prepare(targetNoteId, target.path)
                sessionDraftId = draftSession?.draftId
            }
            quietSaveTriggered = false
            serviceEnded = false
            lastCheckpointSeconds = 0
            lastCheckpointPaused = false
            quietSaveForSession = targetNoteId != null && draftSession != null && quietSaveEnabled()
            serviceController.configureQuietSave(quietSaveForSession)
            val started = serviceController.start(target.path, audioRouteRepository.requestedInputDeviceIds.first())
            confirmed = started && awaitServiceStarted() != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Could not start the recording service", e)
        } finally {
            if (!confirmed) {
                try {
                    serviceController.shutdown()
                } finally {
                    recordingTarget = null
                    sessionTargetNoteId = null
                }
            }
        }
        if (!confirmed) return false
        recordingStateFlow.value = true
        observeService()
        transcriptionStartJob = scope.launch { startLiveTranscription() }
        return true
    }

    /**
     * Waits for the service to report that the recorder is running. Returns null when the
     * service reports an error or never connects within [startTimeout], which covers a
     * foreground start the platform refused and a recorder that failed to prepare.
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
        serviceStateJob =
            scope.launch {
                serviceController.serviceState.collect { state -> onServiceState(state) }
            }
        routeSyncJob?.cancel()
        val generation = sessionGeneration
        routeSyncJob =
            observeRecordingInputRoutes(
                scope,
                audioRouteRepository,
                serviceController,
                sessionMutex,
                isCurrentSession = { generation == sessionGeneration && recordingStateFlow.value },
                onInputChanged = { updateTranscriptionInput(it) },
            )
    }

    private fun onServiceState(state: RecordingServiceState?) {
        audioRouteRepository.updateRecordingInputDevice(state?.routedInputDeviceId, state?.isRecording == true, state?.inputRoutingError)
        if (state?.isRecording == true && state.routedInputDeviceId != null) updateTranscriptionInput(state.routedInputDeviceId)
        if (state == null) {
            recordingPausedFlow.value = false
            if (recordingStateFlow.value) {
                Napier.w("Recording service went away mid-session")
                recordingStateFlow.value = false
            }
            return
        }
        recordingPausedFlow.value = state.isRecording && state.isPaused
        audioLevelFlow.value = state.audioLevel
        durationFlow.value = state.durationSeconds.toLong() * 1000
        if (state.isRecording && (state.durationSeconds - lastCheckpointSeconds >= 30 || state.isPaused != lastCheckpointPaused)) {
            lastCheckpointSeconds = state.durationSeconds
            lastCheckpointPaused = state.isPaused
            val generation = sessionGeneration
            scope.launch {
                sessionMutex.withLock {
                    if (generation == sessionGeneration && recordingStateFlow.value) {
                        try {
                            recordingTarget?.path?.let { draftSession?.checkpoint(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Napier.e("Recording checkpoint failed; retaining the original audio", e)
                            requestStopRecording()
                        }
                    }
                }
            }
        }
        if (!state.isRecording && recordingStateFlow.value) {
            // The service ended the session on its own (notification action or recorder
            // failure). Keep the file it produced for the editor's stop call.
            Napier.d("Recording service stopped on its own; file=${state.recordedFilePath} error=${state.failureMessage}")
            externallyRecordedPath = state.recordedFilePath
            serviceEnded = true
            quietSaveTriggered = state.quietSaveTriggered
            recordingStateFlow.value = false
            if (state.quietSaveTriggered || quietSaveForSession) requestStopRecording()
        }
    }

    override suspend fun stopRecording(): String? =
        sessionMutex.withLock {
            val filePath = withContext(workDispatcher) { stopSessionLocked() }
            recordingTarget = null
            externallyRecordedPath = null
            filePath
        }

    override suspend fun pauseRecording(): Boolean =
        withContext(workDispatcher) {
            sessionMutex.withLock {
                if (!recordingStateFlow.value) return@withLock false
                try {
                    // Same ordering guard as stop: don't ask a still-initializing
                    // transcription session to stop before it has finished starting.
                    transcriptionStartJob?.join()
                    val paused = serviceController.pause()
                    if (paused) transcriptionService?.stopLiveTranscription()
                    paused
                } catch (e: Exception) {
                    Napier.e("Error pausing recording", e)
                    false
                }
            }
        }

    override suspend fun resumeRecording(): Boolean =
        withContext(workDispatcher) {
            sessionMutex.withLock {
                if (!recordingStateFlow.value) return@withLock false
                try {
                    val resumed = serviceController.resume()
                    if (resumed) {
                        applyRecordingInputRoute(
                            audioRouteRepository,
                            serviceController,
                            audioRouteRepository.requestedInputDeviceIds.first(),
                        )
                    }
                    val service = transcriptionService
                    if (resumed && service != null && service.supportsLiveTranscription) {
                        updateTranscriptionInput(
                            serviceController.serviceState.value?.routedInputDeviceId
                                ?: audioRouteRepository.requestedInputDeviceIds.first(),
                        )
                        applyLiveStart(service.startLiveTranscription())
                        updateTranscriptionInput(
                            serviceController.serviceState.value?.routedInputDeviceId
                                ?: audioRouteRepository.requestedInputDeviceIds.first(),
                            verifyActiveInput = true,
                        )
                    }
                    resumed
                } catch (e: Exception) {
                    Napier.e("Error resuming recording", e)
                    false
                }
            }
        }

    override suspend fun resetTranscription() {
        transcriptionService?.resetTranscription()
        transcriptionFlow.value = null
        structuredTranscriptionFlow.value = null
    }

    override fun getAudioLevelFlow(): Flow<Float> = audioLevelFlow

    override fun getRecordingDurationFlow(): Flow<Duration> = recordingDurationFlow

    override fun getTranscriptionFlow(): Flow<String?> = transcriptionFlow

    override fun getStructuredTranscriptionFlow(): Flow<TranscriptionResult> = structuredTranscriptionFlow.filterNotNull()

    override fun requestStopRecording() {
        // Caller is in a context with no usable coroutine scope (typically
        // ViewModel.onCleared after viewModelScope has died). Run the stop on
        // the singleton's own scope so the foreground service is released and
        // the Whisper refinement coroutine — already launched on the long-lived
        // transcription service scope — keeps running to completion.
        if (!recordingStateFlow.value && !startInFlight && externallyRecordedPath == null && !serviceEnded) return
        val requestedSession = sessionGeneration
        scope.launch {
            try {
                sessionMutex.withLock {
                    if (sessionGeneration != requestedSession) return@withLock
                    transcriptionStartJob?.cancel()
                    stopSessionLocked()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Error stopping recording from background request", e)
            }
        }
    }

    /**
     * Ends any session in flight and drops per-session work. The manager stays usable
     * afterwards: it is an application-scoped singleton shared by every editor, and so is
     * the transcription service it was handed, so neither is torn down here.
     */
    override fun release() {
        val pendingWrite = synchronized(transcriptPersistenceLock) { transcriptPersistenceJobs.remove(sessionTargetNoteId) }
        pendingWrite?.cancel()
        requestStopRecording()
    }

    private companion object {
        /**
         * Upper bound on the wait for the foreground service to confirm the recorder is
         * running. Preparing MediaRecorder takes well under a second; the bound only has to
         * catch a service that never connects.
         */
        val DEFAULT_START_TIMEOUT: Duration = 10.seconds
    }
}

private suspend fun prepareRecordingTarget(
    audioStorage: AudioStorage,
    onTargetReady: suspend (String) -> Boolean,
): AudioRecordingTarget? {
    val target =
        try {
            audioStorage.createRecordingTarget()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not create a recording target", error)
            return null
        }
    val accepted =
        try {
            onTargetReady(target.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not persist the recording target", error)
            false
        }
    if (accepted) return target
    Napier.w("Recording was not started because its draft target could not be saved")
    return null
}
