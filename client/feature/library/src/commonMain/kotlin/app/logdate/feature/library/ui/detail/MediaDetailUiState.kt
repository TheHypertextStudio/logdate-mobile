package app.logdate.feature.library.ui.detail

import app.logdate.client.repository.journals.NoteLocation
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * UI state for the media detail screen.
 */
sealed interface MediaDetailUiState {
    data object Loading : MediaDetailUiState

    data class Error(
        val message: StringResource,
    ) : MediaDetailUiState

    data class ImageContent(
        val mediaId: Uuid,
        val mediaRef: String,
        val createdAt: Instant,
        val location: NoteLocation?,
        val locationDisplayName: String? = null,
        val journals: List<JournalReference> = emptyList(),
        val exif: ExifDisplayData? = null,
    ) : MediaDetailUiState

    data class VideoContent(
        val mediaId: Uuid,
        val mediaRef: String,
        val createdAt: Instant,
        val location: NoteLocation?,
        val locationDisplayName: String? = null,
        val journals: List<JournalReference> = emptyList(),
    ) : MediaDetailUiState

    data class AudioContent(
        val mediaId: Uuid,
        val mediaRef: String,
        val durationMs: Long,
        val createdAt: Instant,
        val location: NoteLocation?,
        val locationDisplayName: String? = null,
        val journals: List<JournalReference> = emptyList(),
    ) : MediaDetailUiState
}

/**
 * A journal that contains this media item, used for "Appears in" cross-references.
 */
data class JournalReference(
    val id: Uuid,
    val title: String,
)

/**
 * Camera metadata extracted from EXIF for display in the detail view.
 */
data class ExifDisplayData(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val aperture: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val shutterSpeed: String? = null,
)

/**
 * State for presenter mode — showing media on an external display.
 */
data class PresenterState(
    val isExternalDisplayAvailable: Boolean = false,
    val isPresenting: Boolean = false,
    val currentIndex: Int = 0,
    val totalItems: Int = 0,
    val mediaItems: List<MediaViewerItem> = emptyList(),
)

/**
 * A media item available to the mobile viewer and presenter strip.
 */
data class MediaViewerItem(
    val uid: Uuid,
    val uri: String,
    val isVideo: Boolean,
    val isAudio: Boolean = false,
)

/**
 * Viewer state for paging across visible Library media.
 */
data class MediaViewerState(
    val currentIndex: Int = 0,
    val totalItems: Int = 0,
    val mediaItems: List<MediaViewerItem> = emptyList(),
)

/**
 * UI state for compact-screen viewer chrome.
 */
data class MediaViewerChromeState(
    val isVisible: Boolean = true,
)

/**
 * A media item in the presenter navigation strip.
 *
 * Kept as a typealias so the existing presenter concept stays readable while viewer and presenter
 * share the same underlying item model.
 */
typealias PresenterMediaItem =
    MediaViewerItem
