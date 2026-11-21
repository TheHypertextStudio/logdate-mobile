package app.logdate.feature.editor.ui.editor

import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.SystemCaptureTimeZone
import app.logdate.feature.editor.ui.camera.CaptureMode
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.formatMediaDuration
import app.logdate.shared.model.Location
import app.logdate.shared.model.PhotoPresentation
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Base interface for entry block data.
 * Each block represents a discrete piece of content within a journal entry.
 */
sealed interface EntryBlockUiState {
    val id: Uuid
    val timestamp: Instant
    val location: Location?

    /**
     * IANA id of the time zone the device was in when the block was created, or null when the
     * block came from a draft or note that did not record one. It is stamped with [timestamp]
     * so a block reopened later, in another zone, still records the zone it was written in.
     */
    val timeZoneId: String?

    fun hasContent(): Boolean

    /**
     * Whether this block requests full-screen immersive layout while active.
     * When true, the editor expands the block to fill the screen and hides non-essential
     * toolbar actions. Defaults to false; block types with capture/editing flows override this.
     */
    val wantsImmersiveLayout: Boolean get() = false
}

/**
 * Sub-interface for blocks backed by a media URI (image, video, audio, camera).
 */
sealed interface MediaBlockUiState : EntryBlockUiState {
    val uri: String?
    val caption: String
}

/**
 * Text block data representing text content in a journal entry.
 */
data class TextBlockUiState(
    override val id: Uuid = Uuid.random(),
    override val timestamp: Instant = Clock.System.now(),
    override val location: Location? = null,
    val content: String = "",
    override val timeZoneId: String? = SystemCaptureTimeZone.currentTimeZoneId(),
) : EntryBlockUiState {
    override fun hasContent(): Boolean = content.isNotBlank()
}

/**
 * Image block data representing an image in a journal entry.
 */
data class ImageBlockUiState(
    override val id: Uuid = Uuid.random(),
    override val timestamp: Instant = Clock.System.now(),
    override val location: Location? = null,
    override val uri: String? = null,
    override val caption: String = "",
    val presentation: PhotoPresentation = PhotoPresentation.EdgeToEdge,
    override val timeZoneId: String? = SystemCaptureTimeZone.currentTimeZoneId(),
    /** Where the media was captured, as saved with the note: coordinates and, when known, the person's place. */
    val noteLocation: NoteLocation? = null,
) : MediaBlockUiState {
    override fun hasContent(): Boolean = uri != null
}

/**
 * Camera block data representing media captured from the camera in a journal entry.
 * Can be either a photo or a video, determined by [mediaType].
 *
 * @property id Unique identifier for the block.
 * @property timestamp When the block was created.
 * @property location Geographic location where the media was captured, if available.
 * @property uri Content URI pointing to the captured photo or video file.
 * @property caption User-provided caption or description for the media.
 * @property mediaType Type of media captured (photo or video).
 * @property durationMs Duration in milliseconds (only applicable for videos).
 */
data class CameraBlockUiState(
    override val id: Uuid = Uuid.random(),
    override val timestamp: Instant = Clock.System.now(),
    override val location: Location? = null,
    override val uri: String? = null,
    val pendingFilePath: String? = null,
    override val caption: String = "",
    val mediaType: CapturedMediaType = CapturedMediaType.PHOTO,
    val durationMs: Long = 0,
    override val timeZoneId: String? = SystemCaptureTimeZone.currentTimeZoneId(),
    /** Whether the live camera starts ready for photos or video, before anything is captured. */
    val initialCaptureMode: CaptureMode = CaptureMode.PHOTO,
    /** Where the media was captured, as saved with the note: coordinates and, when known, the person's place. */
    val noteLocation: NoteLocation? = null,
) : MediaBlockUiState {
    override fun hasContent(): Boolean = uri != null

    /** Requests immersive layout while the camera viewfinder is live (no captured media yet). */
    override val wantsImmersiveLayout: Boolean get() = uri == null

    /**
     * Returns a formatted duration string in MM:SS format.
     * Only meaningful when [mediaType] is [CapturedMediaType.VIDEO].
     */
    val formattedDuration: String
        get() {
            return formatMediaDuration(durationMs, true)
        }
}

/**
 * Video block data representing a video in a journal entry.
 *
 * @property id Unique identifier for the block.
 * @property timestamp When the block was created.
 * @property location Geographic location where the video was recorded, if available.
 * @property uri Content URI pointing to the video file.
 * @property caption User-provided caption or description for the video.
 * @property durationMs Duration of the video in milliseconds.
 */
data class VideoBlockUiState(
    override val id: Uuid = Uuid.random(),
    override val timestamp: Instant = Clock.System.now(),
    override val location: Location? = null,
    override val uri: String? = null,
    override val caption: String = "",
    val durationMs: Long = 0,
    override val timeZoneId: String? = SystemCaptureTimeZone.currentTimeZoneId(),
    /** Where the media was captured, as saved with the note: coordinates and, when known, the person's place. */
    val noteLocation: NoteLocation? = null,
) : MediaBlockUiState {
    override fun hasContent(): Boolean = uri != null

    /**
     * Returns a formatted duration string in MM:SS format.
     */
    val formattedDuration: String
        get() {
            return formatMediaDuration(durationMs, true)
        }
}

/**
 * Audio block data representing an audio recording in a journal entry.
 *
 * The recording lifecycle lives in [captureState]. Transient recording progress
 * (Recording / Stopping) is represented inside the block itself so the editor
 * can persist intent into a draft and reason about pending media at save time
 * — instead of holding the in-flight URI inside an app-scoped ViewModel where
 * a save action could race past it.
 */
data class AudioBlockUiState(
    override val id: Uuid = Uuid.random(),
    override val timestamp: Instant = Clock.System.now(),
    override val location: Location? = null,
    val captureState: AudioCaptureState = AudioCaptureState.Empty,
    override val caption: String = "",
    val transcription: String = "",
    override val timeZoneId: String? = SystemCaptureTimeZone.currentTimeZoneId(),
    /**
     * Start recording as soon as the block appears and the microphone is allowed. Voice launches
     * (the Record voice shortcut, tile, and widget) set it, because the person already chose to
     * record; it clears once recording is requested.
     */
    val startOnOpen: Boolean = false,
) : MediaBlockUiState {
    override val uri: String?
        get() = (captureState as? AudioCaptureState.Ready)?.uri

    val durationMs: Long
        get() = (captureState as? AudioCaptureState.Ready)?.durationMs ?: 0L

    /** Backward-compatible alias for [durationMs]. Prefer [durationMs] in new code. */
    val duration: Long
        get() = durationMs

    override fun hasContent(): Boolean = captureState !is AudioCaptureState.Empty

    /** True only when the recording is finalized and ready to be persisted as a journal note. */
    fun isPersistable(): Boolean = captureState is AudioCaptureState.Ready
}
