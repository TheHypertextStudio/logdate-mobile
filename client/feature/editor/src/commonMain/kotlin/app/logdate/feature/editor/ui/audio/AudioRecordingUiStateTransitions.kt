package app.logdate.feature.editor.ui.audio

import app.logdate.client.media.audio.transcription.TranscriptionResult
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.capture_state_recording_could_not_be_saved
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration
import kotlin.uuid.Uuid

internal fun AudioUiState.withAttachedRecording(
    targetNoteId: Uuid?,
    filePath: String?,
    paused: Boolean,
    starting: Boolean = false,
    fallbackFilePath: String? = null,
    clearRecordedUri: Boolean = false,
): AudioUiState =
    copy(
        isRecording = !starting,
        isStartingRecording = starting,
        isPaused = paused,
        recordingTargetNoteId = targetNoteId,
        recordingFilePath = filePath,
        recordingFallbackFilePath = fallbackFilePath,
        pendingRecoveryFilePath = null,
        recordedAudioUri = if (clearRecordedUri) null else recordedAudioUri,
        failedRecordingTargetNoteId = if (clearRecordedUri) null else failedRecordingTargetNoteId,
        error = null,
    )

internal fun AudioUiState.withStartedRecording(
    targetNoteId: Uuid?,
    filePath: String?,
    transcriptionAvailable: Boolean,
    fallbackFilePath: String? = null,
): AudioUiState =
    copy(
        isRecording = true,
        isStartingRecording = false,
        isPaused = false,
        isPlaying = false,
        audioLevels = emptyList(),
        duration = Duration.ZERO,
        transcriptionState =
            when {
                transcriptionState is AudioUiState.TranscriptionState.Error -> transcriptionState
                transcriptionAvailable -> AudioUiState.TranscriptionState.InProgress
                else -> AudioUiState.TranscriptionState.NotRequested
            },
        error = null,
        recordingFilePath = filePath,
        recordingFallbackFilePath = fallbackFilePath,
        pendingRecoveryFilePath = null,
        recordingTargetNoteId = targetNoteId,
    )

internal fun AudioUiState.withFailedRecordingStart(
    message: StringResource,
    lastTargetNoteId: Uuid?,
    fallback: AudioUiState?,
): AudioUiState =
    copy(
        isRecording = false,
        isStartingRecording = false,
        failedRecordingTargetNoteId = if (fallback == null) recordingTargetNoteId ?: lastTargetNoteId else null,
        recordingTargetNoteId = null,
        recordingFilePath = null,
        recordingFallbackFilePath = null,
        pendingRecoveryFilePath = null,
        recordedAudioUri = fallback?.recordedAudioUri ?: recordedAudioUri,
        duration = fallback?.duration ?: duration,
        transcriptionState = fallback?.transcriptionState ?: transcriptionState,
        error = message,
    )

internal fun AudioUiState.withCancelledRecordingStart(): AudioUiState =
    copy(
        isRecording = false,
        isStartingRecording = false,
        isPaused = false,
        recordingTargetNoteId = null,
        recordingFilePath = null,
        recordingFallbackFilePath = null,
    )

internal fun AudioUiState.withStoppedRecording(
    uri: String?,
    targetNoteId: Uuid?,
    publishRecording: Boolean,
    resolvedDuration: Duration?,
    fileTranscriptionAvailable: Boolean,
): AudioUiState =
    copy(
        isRecording = false,
        isPaused = false,
        recordedAudioUri = if (publishRecording) uri else null,
        recordingFilePath = if (publishRecording) null else uri,
        recordingFallbackFilePath = null,
        pendingRecoveryFilePath = null,
        recordingTargetNoteId = if (publishRecording || uri == null) null else targetNoteId,
        duration = resolvedDuration ?: duration,
        transcriptionState =
            when {
                transcriptionState is AudioUiState.TranscriptionState.Success -> transcriptionState
                fileTranscriptionAvailable -> AudioUiState.TranscriptionState.InProgress
                else -> AudioUiState.TranscriptionState.NotRequested
            },
        failedRecordingTargetNoteId = if (uri == null) targetNoteId else null,
        error = if (uri == null) Res.string.capture_state_recording_could_not_be_saved else error,
    )

internal fun AudioUiState.withFailedRecordingStop(
    targetNoteId: Uuid?,
    fallbackFilePath: String?,
    fallbackDuration: Duration?,
    pendingFilePath: String?,
): AudioUiState =
    copy(
        isRecording = false,
        isPaused = false,
        recordingFilePath = null,
        recordingFallbackFilePath = fallbackFilePath,
        recordingTargetNoteId = null,
        recordedAudioUri = if (pendingFilePath == null) fallbackFilePath else null,
        pendingRecoveryFilePath = pendingFilePath,
        duration = if (fallbackFilePath != null) fallbackDuration ?: duration else duration,
        failedRecordingTargetNoteId = if (fallbackFilePath == null && pendingFilePath == null) targetNoteId else null,
        error =
            when {
                fallbackFilePath != null || pendingFilePath != null -> null
                else -> Res.string.capture_state_recording_could_not_be_saved
            },
    )

internal fun AudioUiState.withRestoredRecording(previous: AudioUiState): AudioUiState =
    copy(
        isRecording = false,
        isStartingRecording = false,
        isPaused = false,
        recordingTargetNoteId = null,
        recordingFilePath = null,
        recordingFallbackFilePath = recordingFallbackFilePath ?: previous.recordingFallbackFilePath,
        pendingRecoveryFilePath = pendingRecoveryFilePath ?: previous.pendingRecoveryFilePath,
        recordedAudioUri = if (pendingRecoveryFilePath != null) null else previous.recordedAudioUri,
        duration = previous.duration,
        transcriptionState = previous.transcriptionState,
        failedRecordingTargetNoteId = null,
        error = previous.error,
    )

internal fun AudioUiState.withTranscriptionResult(result: TranscriptionResult): AudioUiState =
    when (result) {
        is TranscriptionResult.Success -> {
            copy(
                transcriptionState =
                    AudioUiState.TranscriptionState.Success(
                        text = result.text,
                        timedTranscript = result.timedTranscript,
                        isFinal = result.isFinal,
                        isRefining = result.isRefining,
                    ),
            )
        }

        is TranscriptionResult.Error -> {
            copy(transcriptionState = AudioUiState.TranscriptionState.Error(result.reason))
        }

        is TranscriptionResult.InProgress -> {
            if (transcriptionState is AudioUiState.TranscriptionState.Success) {
                this
            } else {
                copy(transcriptionState = AudioUiState.TranscriptionState.InProgress)
            }
        }

        TranscriptionResult.Cancelled -> {
            copy(transcriptionState = AudioUiState.TranscriptionState.NotRequested)
        }
    }
