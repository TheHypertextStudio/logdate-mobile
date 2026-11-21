package app.logdate.feature.editor.ui.camera

import app.logdate.client.media.device.DefaultMediaDevices
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import kotlinx.coroutines.flow.StateFlow
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.capture_state_external_camera_detection_unavailable

/**
 * Interface for managing camera capture operations.
 * Platform-specific implementations handle the actual camera hardware.
 */
interface CameraCaptureManager {
    /**
     * Observable state of the camera capture manager.
     */
    val state: StateFlow<CameraCaptureState>

    /**
     * Initializes the camera and starts the preview.
     * @param facing The initial camera facing direction.
     */
    suspend fun startPreview(facing: CameraFacing = CameraFacing.BACK)

    /**
     * Stops the camera preview and releases resources.
     */
    suspend fun stopPreview()

    /**
     * Captures a photo.
     * @return The URI of the captured photo, or null if capture failed.
     */
    suspend fun capturePhoto(): String?

    /** Android persists this target in its draft before CameraX receives it. */
    suspend fun capturePhoto(onTargetPrepared: suspend (String) -> Boolean): String? = capturePhoto()

    /**
     * Starts video recording.
     */
    suspend fun startVideoRecording()

    suspend fun startVideoRecording(onTargetPrepared: suspend (String) -> Boolean) = startVideoRecording()

    /**
     * Stops video recording.
     * @return The URI of the recorded video, or null if recording failed.
     */
    suspend fun stopVideoRecording(): String?

    /**
     * Switches between front and back camera.
     */
    suspend fun switchCamera()

    /**
     * Selects a camera from the available device list.
     */
    suspend fun selectCameraDevice(deviceId: String)

    /**
     * Sets the capture mode (photo or video).
     */
    fun setCaptureMode(mode: CaptureMode)

    /**
     * Sets the aspect ratio for the camera viewfinder and capture.
     */
    fun setAspectRatio(ratio: CameraAspectRatio)

    /**
     * Clears the last captured URI from the manager's state.
     * Called after the UI has consumed the captured media to prevent stale state.
     */
    fun clearCapturedUri()

    /**
     * Releases all camera resources.
     * Should be called when the camera is no longer needed.
     */
    fun release()
}

/**
 * The current state of the camera capture manager.
 */
data class CameraCaptureState(
    val isPreviewActive: Boolean = false,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val aspectRatio: CameraAspectRatio = CameraAspectRatio.FULL,
    val isRecording: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val lastCapturedUri: String? = null,
    val error: CameraCaptureError? = null,
    val cameraSelection: MediaDeviceSelectionUiState = defaultCameraSelection(cameraFacing),
)

fun defaultCameraSelection(facing: CameraFacing): MediaDeviceSelectionUiState =
    MediaDeviceSelectionUiState(
        kind = MediaDeviceKind.CAMERA,
        devices = listOf(DefaultMediaDevices.backCamera, DefaultMediaDevices.frontCamera),
        selectedDeviceId =
            when (facing) {
                CameraFacing.BACK -> DefaultMediaDevices.backCamera.id
                CameraFacing.FRONT -> DefaultMediaDevices.frontCamera.id
            },
        routeControlMessage = Res.string.capture_state_external_camera_detection_unavailable.toUiText(),
    )

/**
 * Camera facing direction.
 */
enum class CameraFacing {
    FRONT,
    BACK,
}

/**
 * Capture mode for the camera.
 */
enum class CaptureMode {
    PHOTO,
    VIDEO,
}

/**
 * Aspect ratio options for camera capture.
 *
 * The [ratio] is expressed as width/height for use with Compose's `aspectRatio` modifier.
 */
enum class CameraAspectRatio(
    val displayName: String,
    val ratio: Float,
) {
    FULL(displayName = "9:16", ratio = 9f / 16f),
    STANDARD(displayName = "4:3", ratio = 3f / 4f),
    SQUARE(displayName = "1:1", ratio = 1f),
}

/**
 * Type of captured media.
 */
enum class CapturedMediaType {
    PHOTO,
    VIDEO,
}

/**
 * Errors that can occur during camera operations.
 */
sealed class CameraCaptureError {
    data object CameraNotAvailable : CameraCaptureError()

    data object PermissionDenied : CameraCaptureError()

    data object CaptureFailed : CameraCaptureError()

    data object RecordingFailed : CameraCaptureError()

    data class Unknown(
        val message: UiText,
    ) : CameraCaptureError()
}
