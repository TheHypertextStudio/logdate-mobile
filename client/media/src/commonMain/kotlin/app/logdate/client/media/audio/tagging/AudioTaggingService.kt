package app.logdate.client.media.audio.tagging

import app.logdate.client.media.audio.download.ModelDownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A non-voice ambient sound detected in an audio recording, e.g. "Bird",
 * "Rain", "Car passing by". The label set is the AudioSet ontology.
 *
 * @param name human-readable label
 * @param confidence model probability for this detection, in the range [0, 1]
 * @param startMs offset from the start of the recording where this sound began
 * @param durationMs how long the sound was sustained
 */
data class DetectedSound(
    val name: String,
    val confidence: Float,
    val startMs: Long,
    val durationMs: Long,
)

sealed class AudioTaggingResult {
    /**
     * Tagging completed successfully. May be emitted multiple times during a
     * progressive scan; the [sounds] list is the cumulative result so far.
     *
     * @param isFinal true once no further updates are expected for this audio
     */
    data class Success(
        val sounds: List<DetectedSound>,
        val isFinal: Boolean = false,
    ) : AudioTaggingResult()

    /**
     * Tagging failed. The recording is otherwise unaffected.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : AudioTaggingResult()

    /** The model isn't available on device — caller should treat as no-op. */
    object Unavailable : AudioTaggingResult()
}

/**
 * Detects non-voice ambient sounds (birds, traffic, music, rain, etc.) in
 * captured audio. Implementations are expected to be on-device only.
 *
 * Tagging happens after a recording is captured. The service runs the audio
 * through a fixed-window classifier and emits a [AudioTaggingResult.Success]
 * with the cumulative detections, optionally multiple times as the analysis
 * progresses through the recording.
 */
interface AudioTaggingService {
    /**
     * Whether the tagging model is present on device. Until this is true,
     * [tagAudio] will return [AudioTaggingResult.Unavailable].
     */
    val isAvailable: Boolean

    /**
     * Best-effort eager initialization. Subsequent [tagAudio] calls start
     * faster. No-op if the model is unavailable.
     */
    suspend fun warmUp(): Boolean

    /**
     * Tags ambient sounds in the recording at [audioUri]. Returns a flow that
     * emits cumulative [AudioTaggingResult.Success] values as analysis windows
     * complete, then a final value with [AudioTaggingResult.Success.isFinal]
     * set to true. Errors are emitted as [AudioTaggingResult.Error].
     *
     * Implementations should run on a background dispatcher and be safe to
     * cancel mid-stream.
     */
    fun tagAudio(audioUri: String): Flow<AudioTaggingResult>

    /**
     * Live status of the tagging model download. View models observe this
     * directly to render a download banner or progress indicator. Default
     * sits at [ModelDownloadStatus.NotSupported] for implementations that
     * don't ship a downloadable tagger.
     */
    val modelDownloadStatus: StateFlow<ModelDownloadStatus>
        get() = NotSupportedAudioTaggingDownloadStatus

    /**
     * Idempotently kicks the tagging model download. If a download is
     * already in flight or the model is already on disk, this is a no-op.
     * Progress flows out via [modelDownloadStatus].
     */
    fun startModelDownload() = Unit

    /** Retries a failed installed-file check without starting a download. */
    fun checkModelAvailability() = Unit

    /** Releases native resources held by the underlying model. */
    fun release()
}

private val NotSupportedAudioTaggingDownloadStatus: StateFlow<ModelDownloadStatus> =
    MutableStateFlow(ModelDownloadStatus.NotSupported).asStateFlow()

/**
 * Unavailable [AudioTaggingService] for platforms that don't ship the on-device
 * tagger. Stateless — exposed as a singleton object so DI bindings don't allocate
 * per resolution.
 */
object NoopAudioTaggingService : AudioTaggingService {
    override val isAvailable: Boolean = false

    override suspend fun warmUp(): Boolean = false

    override fun tagAudio(audioUri: String): Flow<AudioTaggingResult> = flowOf(AudioTaggingResult.Unavailable)

    override fun release() = Unit
}
