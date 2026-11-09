package app.logdate.feature.editor.ui.audio

import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.transcription.TimedTranscript
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.uuid.Uuid

/**
 * Data class representing the UI state for audio recording and playback.
 */
data class AudioUiState(
    // Recording state
    val isRecording: Boolean = false,
    /**
     * True from the moment a start is accepted until the platform confirms the recorder is
     * running (or refuses). The record control is disabled meanwhile so repeated taps cannot
     * queue a second session.
     */
    val isStartingRecording: Boolean = false,
    val isPaused: Boolean = false, // New state for pause/resume functionality
    val recordedAudioUri: String? = null,
    /**
     * Block id of a recording that ended without producing a file. The block editor uses it to
     * drop the block back to an empty state so the user can record again or save without it.
     * Cleared when the next recording starts.
     */
    val failedRecordingTargetNoteId: Uuid? = null,
    /**
     * Filesystem path of the recording the manager is currently writing to. Surfaced so
     * the editor can persist it as the recovery anchor in the draft's pending-media list
     * while the recording is in flight.
     */
    val recordingFilePath: String? = null,
    /** A completed take kept recoverable until a replacement recording is safely underway. */
    val recordingFallbackFilePath: String? = null,
    /** A stopped recording path awaiting media validation before it can become a completed take. */
    val pendingRecoveryFilePath: String? = null,
    /**
     * Block id the active recording is bound to. Multiple audio blocks may exist in the
     * editor; only the one matching this id should reflect the recording's live state.
     */
    val recordingTargetNoteId: Uuid? = null,
    val audioLevels: List<Float> = emptyList(),
    val duration: Duration = ZERO,
    // Playback state
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
    val currentUri: String? = null,
    // Shared state
    val error: StringResource? = null,
    // Transcription state
    val transcriptionState: TranscriptionState = TranscriptionState.NotRequested,
    // Combined state of the on-device enhanced model downloads (Whisper for
    // transcription refinement, CED for ambient sound tagging). Drives the
    // download banner shown above the recording controls.
    val enhancedModelStatus: EnhancedAudioModelStatus = EnhancedAudioModelStatus.Ready,
) {
    /**
     * States for transcription UI.
     */
    sealed class TranscriptionState {
        /**
         * Transcription has not been requested.
         */
        object NotRequested : TranscriptionState()

        /**
         * Transcription is queued but not started.
         */
        object Pending : TranscriptionState()

        /**
         * Transcription is in progress.
         */
        object InProgress : TranscriptionState()

        /**
         * Transcription completed successfully.
         *
         * @param isRefining true while a higher-accuracy refinement pass is
         *   still rewriting parts of [text] in the background. The UI should
         *   accept that the text may continue to change visibly while this
         *   flag is true and crossfade between values smoothly.
         */
        data class Success(
            val text: String,
            val timedTranscript: TimedTranscript? = null,
            val isFinal: Boolean = false,
            val isRefining: Boolean = false,
        ) : TranscriptionState()

        /**
         * Transcription failed.
         */
        data class Error(
            val reason: TranscriptionFailure,
        ) : TranscriptionState()
    }

    // Compatibility properties to maintain backward compatibility
    val transcription: String?
        get() =
            when (val state = transcriptionState) {
                is TranscriptionState.Success -> state.text
                else -> null
            }

    val timedTranscript: TimedTranscript?
        get() =
            when (val state = transcriptionState) {
                is TranscriptionState.Success -> state.timedTranscript
                else -> null
            }

    val isTranscriptionFinal: Boolean
        get() = (transcriptionState as? TranscriptionState.Success)?.isFinal == true

    val isTranscriptionRefining: Boolean
        get() = (transcriptionState as? TranscriptionState.Success)?.isRefining == true

    val hasTranscriptionError: Boolean
        get() = transcriptionState is TranscriptionState.Error

    val transcriptionInProgress: Boolean
        get() =
            transcriptionState is TranscriptionState.InProgress ||
                transcriptionState is TranscriptionState.Pending
}

/**
 * Combined download state for the two on-device enhanced audio models. The
 * audio editor uses this to decide whether to show a download CTA, a
 * progress bar, or nothing at all.
 *
 * The status reflects the *worst* of the two models — if either is missing
 * or downloading, the banner is shown; only when both are present does it
 * disappear.
 */
sealed interface EnhancedAudioModelStatus {
    /** Both models are on disk. The banner is hidden. */
    data object Ready : EnhancedAudioModelStatus

    /**
     * At least one model isn't on disk and no download is in flight. The
     * banner offers the download.
     */
    data object NotDownloaded : EnhancedAudioModelStatus

    /**
     * A download is in flight. [fraction] is the combined progress fraction
     * across both models in [0, 1], or null if any in-flight download has
     * unknown total bytes.
     */
    data class Downloading(
        val fraction: Float?,
    ) : EnhancedAudioModelStatus

    /**
     * The most recent download attempt ended in failure. The banner shows a
     * retry CTA along with a localized message keyed off [reason].
     */
    data class Failed(
        val reason: ModelDownloadStatus,
    ) : EnhancedAudioModelStatus
}
