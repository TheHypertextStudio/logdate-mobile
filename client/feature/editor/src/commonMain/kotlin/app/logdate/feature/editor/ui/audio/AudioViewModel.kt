package app.logdate.feature.editor.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.delegate.DefaultPendingAudioRecoverer
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioRecoverer
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.capture_state_recording_could_not_start
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration
import kotlin.uuid.Uuid
import app.logdate.client.media.audio.AudioRecordingManager as MediaAudioRecordingManager

/**
 * Unified ViewModel for managing both audio recording and playback functionality.
 * Handles recording state, playback, audio levels, duration, and transcription.
 * Now supports pause/resume functionality for recording.
 */
class AudioViewModel(
    internal val audioRecordingManager: MediaAudioRecordingManager,
    internal val audioPlaybackManager: AudioPlaybackManager,
    internal val audioDurationResolver: AudioDurationResolver,
    internal val transcriptionService: TranscriptionService,
    internal val audioTaggingService: AudioTaggingService,
    internal val pendingAudioRecoverer: PendingAudioRecoverer =
        DefaultPendingAudioRecoverer(audioDurationResolver, audioRecordingManager),
) : ViewModel(),
    PendingAudioResolver {
    internal val playbackStatusProvider = audioPlaybackManager as? AudioPlaybackStatusProvider

    // StateFlow to expose immutable UI state
    internal val mutableUiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = mutableUiState.asStateFlow()

    /**
     * Serializes every recording transition so a tap that lands while a start or stop is
     * still in flight waits its turn and then sees the settled state. Without this, two quick
     * taps on Record raced each other: the second was refused by the platform and overwrote
     * the first's "recording" state with "idle" while the microphone stayed live.
     */
    internal val recordingMutex = Mutex()
    internal val recordingStateObserver =
        AudioRecordingStateObserver(
            viewModelScope,
            audioRecordingManager,
            mutableUiState,
            { lastTargetNoteId },
            { stopRecording() },
            { failStart(Res.string.capture_state_recording_could_not_start) },
        )
    internal val pendingResolution =
        PendingAudioResolutionCoordinator(mutableUiState, recordingMutex, pendingAudioRecoverer) { publishRecording ->
            stopRecordingInternal(publishRecording)
        }

    /**
     * Held across stop/restart so a [restartRecording] call still persists the
     * refined transcript under the same eventual note id.
     */
    internal var lastTargetNoteId: Uuid? = null
    internal var preparedRecordingPath: String? = null
    internal val playbackController =
        AudioPlaybackController(mutableUiState, audioPlaybackManager, playbackStatusProvider, viewModelScope) {
            recordingMutex.withLock { stopRecordingInternal() }
        }

    init {
        audioRecordingManager.setTranscriptionService(transcriptionService)
        observeEnhancedModelDownloads()
    }

    /** Rebinds this block to a recording that survived its previous UI owner. */
    fun attachToRecording(
        blockId: Uuid,
        fallbackFilePath: String? = null,
    ) {
        if (audioRecordingManager.currentRecordingTargetNoteId != blockId) return
        val starting = audioRecordingManager.isStartingRecording && !audioRecordingManager.isRecording
        if (!starting && !audioRecordingManager.isRecording && audioRecordingManager.currentRecordingPath == null) return
        if ((mutableUiState.value.isRecording || mutableUiState.value.isStartingRecording) && lastTargetNoteId == blockId) return
        lastTargetNoteId = blockId
        mutableUiState.update {
            it.withAttachedRecording(
                targetNoteId = blockId,
                filePath = audioRecordingManager.currentRecordingPath,
                paused = audioRecordingManager.currentRecordingPaused,
                starting = starting,
                fallbackFilePath = fallbackFilePath,
            )
        }
        startRecordingCollectors()
        if (!starting && !audioRecordingManager.isRecording) stopRecording()
    }

    /**
     * Starts audio recording and updates the UI state.
     *
     * @param targetNoteId The UUID that the eventual saved audio note will use.
     *   When supplied, the recording manager persists refined transcription
     *   results to the database under this id, so the polished transcript
     *   shows up the next time the note is loaded — even if this view model
     *   has long since been cleared.
     */
    fun startRecording(
        targetNoteId: Uuid? = null,
        onTargetReady: suspend (String, String?) -> Boolean = { _, _ -> true },
    ) = viewModelScope.launch { startRecordingInternal(targetNoteId, onTargetReady = onTargetReady) }

    private suspend fun startRecordingInternal(
        targetNoteId: Uuid?,
        fallbackRecording: AudioUiState? = null,
        onTargetReady: suspend (String, String?) -> Boolean = { _, _ -> true },
    ) {
        recordingMutex.withLock {
            if (!admitStart(targetNoteId)) return
            preparedRecordingPath = null
            Napier.d("AudioViewModel: Starting recording")
            try {
                if (mutableUiState.value.isPlaying) {
                    audioPlaybackManager.stopPlayback()
                }
                lastTargetNoteId = targetNoteId
                val started =
                    audioRecordingManager.startRecording(targetNoteId) { path ->
                        val targetReady = onTargetReady(path, fallbackRecording?.recordedAudioUri)
                        if (targetReady) preparedRecordingPath = path
                        targetReady
                    }
                if (!started) {
                    preparedRecordingPath = null
                    failStart(Res.string.capture_state_recording_could_not_start, fallbackRecording)
                    return
                }
                startRecordingCollectors()
                mutableUiState.update {
                    it.withStartedRecording(
                        targetNoteId,
                        audioRecordingManager.currentRecordingPath,
                        transcriptionService.supportsLiveTranscription || transcriptionService.supportsFileTranscription,
                        fallbackRecording?.recordedAudioUri,
                    )
                }
                preparedRecordingPath = null
                Napier.d("AudioViewModel: Recording started")
            } catch (cancellation: CancellationException) {
                settleCancelledStart(targetNoteId, fallbackRecording)
                throw cancellation
            } catch (e: Exception) {
                Napier.e("Failed to start recording: ${e.message}", e)
                preparedRecordingPath = null
                failStart(Res.string.capture_state_recording_could_not_start, fallbackRecording)
            }
        }
    }

    /**
     * Marks a start as in flight unless a session is already active or starting. Runs under
     * [recordingMutex], so a second tap queued behind the first sees the first's outcome.
     */
    private fun admitStart(targetNoteId: Uuid?): Boolean {
        while (true) {
            val current = mutableUiState.value
            if (current.isRecording || current.isStartingRecording) {
                Napier.d("AudioViewModel: Ignoring start while a session is active or starting")
                return false
            }
            if (current.pendingRecoveryFilePath != null) {
                Napier.d("AudioViewModel: Ignoring start while an earlier take is being recovered")
                return false
            }
            val admitted =
                current.copy(
                    isStartingRecording = true,
                    recordingTargetNoteId = targetNoteId,
                    recordingFilePath = null,
                    error = null,
                    failedRecordingTargetNoteId = null,
                )
            if (mutableUiState.compareAndSet(current, admitted)) return true
        }
    }

    internal fun failStart(
        message: StringResource,
        fallbackRecording: AudioUiState? = null,
    ) {
        mutableUiState.update { it.withFailedRecordingStart(message, lastTargetNoteId, fallbackRecording) }
    }

    /**
     * Stops audio recording, saves the file, and updates the UI state.
     */
    fun stopRecording() {
        viewModelScope.launch {
            recordingMutex.withLock { stopRecordingInternal() }
        }
    }

    internal fun applyStoppedRecording(
        uri: String?,
        targetNoteId: Uuid?,
        publishRecording: Boolean,
        resolvedDuration: Duration? = null,
    ) {
        mutableUiState.update {
            it.withStoppedRecording(uri, targetNoteId, publishRecording, resolvedDuration, transcriptionService.supportsFileTranscription)
        }
    }

    private fun ownsActiveRecording(): Boolean {
        if (!mutableUiState.value.isRecording || !audioRecordingManager.isRecording) return false
        val activeOwner = audioRecordingManager.currentRecordingTargetNoteId
        return activeOwner == null || activeOwner == lastTargetNoteId
    }

    /**
     * Pauses the current recording.
     * Only works on platforms that support pause functionality.
     */
    fun pauseRecording() {
        viewModelScope.launch {
            recordingMutex.withLock {
                if (!ownsActiveRecording()) return@withLock
                Napier.d("AudioViewModel: Pausing recording")
                try {
                    val success = audioRecordingManager.pauseRecording()

                    if (success) {
                        mutableUiState.update { it.copy(isPaused = true, isRecording = true) }
                        Napier.d("Recording paused successfully")
                    } else {
                        Napier.w("Failed to pause recording - functionality may not be supported on this platform")
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Napier.e("Error pausing recording: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Resumes a paused recording.
     * Only works on platforms that support resume functionality.
     */
    fun resumeRecording() {
        viewModelScope.launch {
            recordingMutex.withLock {
                if (!ownsActiveRecording()) return@withLock
                Napier.d("AudioViewModel: Resuming recording")
                try {
                    val success = audioRecordingManager.resumeRecording()

                    if (success) {
                        mutableUiState.update { it.copy(isPaused = false, isRecording = true) }
                        Napier.d("Recording resumed successfully")
                    } else {
                        Napier.w("Failed to resume recording - functionality may not be supported on this platform")
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Napier.e("Error resuming recording: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Restarts the recording from scratch, clearing accumulated transcription.
     */
    fun restartRecording(onTargetReady: suspend (String, String?) -> Boolean = { _, _ -> true }) {
        viewModelScope.launch {
            var previousRecording: AudioUiState? = null
            try {
                recordingMutex.withLock {
                    if (!ownsActiveRecording()) return@launch
                    val uri = stopRecordingInternal(publishRecording = false) ?: return@launch
                    previousRecording =
                        mutableUiState.value.copy(
                            recordedAudioUri = uri,
                            recordingFilePath = null,
                            recordingTargetNoteId = null,
                        )
                    audioRecordingManager.resetTranscription()
                    mutableUiState.update {
                        it.copy(
                            transcriptionState = AudioUiState.TranscriptionState.NotRequested,
                            audioLevels = emptyList(),
                            duration = Duration.ZERO,
                            recordedAudioUri = null,
                            failedRecordingTargetNoteId = null,
                            error = null,
                        )
                    }
                }
                startRecordingInternal(
                    targetNoteId = lastTargetNoteId,
                    fallbackRecording = previousRecording,
                    onTargetReady = onTargetReady,
                )
            } catch (cancellation: CancellationException) {
                previousRecording?.let { restoreCancelledRestart(it) }
                throw cancellation
            }
        }
    }

    /**
     * Toggles recording between recording and paused states.
     */
    fun toggleRecordingPause() {
        if (mutableUiState.value.isRecording) {
            if (mutableUiState.value.isPaused) {
                resumeRecording()
            } else {
                pauseRecording()
            }
        }
    }

    /**
     * Toggles playback between playing and paused states.
     */
    fun togglePlayback(
        uri: String,
        metadata: AudioPlaybackMetadata? = null,
    ) = playbackController.toggle(uri, metadata)

    /**
     * Starts playing the audio from the given URI.
     */
    fun startPlayback(
        uri: String,
        metadata: AudioPlaybackMetadata? = null,
    ) = playbackController.start(uri, metadata)

    /**
     * Pauses the current playback.
     */
    fun pausePlayback() = playbackController.pause()

    fun pausePlaybackFor(uri: String) = playbackController.pause(uri)

    fun seekPlaybackFor(
        uri: String,
        position: Float,
    ) = playbackController.seek(position, uri)

    /**
     * Seeks to a specific position in the audio.
     */
    fun seekTo(position: Float) = playbackController.seek(position)

    /**
     * Seeks to an absolute position in milliseconds.
     */
    fun seekToPositionMs(
        positionMs: Long,
        durationMs: Long,
    ) = playbackController.seek(positionMs, durationMs)

    /**
     * Clean up resources when ViewModel is cleared.
     *
     * AudioRecordingManager and the underlying TranscriptionService are
     * application-scoped singletons — they MUST outlive any individual
     * ViewModel so an in-flight Whisper refinement pass can run to completion
     * after the user navigates away from the editor. We only release things
     * that this ViewModel actually owns: the local flow collectors. If a
     * recording is still active when the screen goes away, we ask the
     * singleton to stop it on its own scope (the foreground service would
     * otherwise dangle), but we never tear the singleton itself down here.
     */
    override fun onCleared() {
        super.onCleared()
        Napier.d("AudioViewModel: Being cleared")
        try {
            stopRecordingCollectors()
            val state = mutableUiState.value
            val activeOwner = audioRecordingManager.currentRecordingTargetNoteId
            val ownsSession =
                if (activeOwner != null) activeOwner == lastTargetNoteId else state.isRecording || state.isStartingRecording
            if (ownsSession &&
                !audioRecordingManager.continuesWithoutEditor &&
                (audioRecordingManager.isRecording || state.isRecording || state.isStartingRecording)
            ) {
                audioRecordingManager.requestStopRecording()
            }
            // AudioPlaybackManager is also a process-lifetime singleton.
            // Stop the current track so the user doesn't hear it after
            // leaving the editor, but never call release() — that tears
            // down the controller and the underlying MediaSessionService
            // is meant to outlive any single view model.
            playbackController.stopIfPlaying()
        } catch (e: Exception) {
            Napier.e("Error cleaning up audio resources: ${e.message}", e)
        }
    }

    fun clearRecordedAudio() {
        mutableUiState.update { it.copy(recordedAudioUri = null) }
    }

    /**
     * Surfaces a pending recording so the editor's save path can absorb a URI
     * that hasn't yet been transferred to the block via the Compose-side
     * `LaunchedEffect` in `AudioBlockEditor`.
     *
     * Returns null when no recording state is associated with [blockId].
     * Drives an active recording to completion before returning.
     */
    override suspend fun resolvePending(blockId: Uuid): AudioCaptureState? = pendingResolution.resolve(blockId, lastTargetNoteId)

    /**
     * Resets playback state for a new block. Call when the block being displayed changes
     * (e.g. after deleting a recording and opening another one).
     */
    fun resetPlaybackState() {
        stopRecordingCollectors()
        if (mutableUiState.value.isPlaying) {
            audioPlaybackManager.stopPlayback()
        }
        mutableUiState.update {
            it.copy(
                isPlaying = false,
                playbackProgress = 0f,
                currentUri = null,
                recordedAudioUri = null,
                duration = Duration.ZERO,
                audioLevels = emptyList(),
                // Preserve InProgress transcription — a file-transcription job may still be running
                transcriptionState =
                    if (it.transcriptionState is AudioUiState.TranscriptionState.InProgress) {
                        it.transcriptionState
                    } else {
                        AudioUiState.TranscriptionState.NotRequested
                    },
                error = null,
            )
        }
    }

    internal fun startRecordingCollectors() = recordingStateObserver.start()

    internal fun stopRecordingCollectors() = recordingStateObserver.stop()
}
