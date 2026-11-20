package app.logdate.feature.editor.ui.camera

import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceSelectionUiState

/**
 * Which camera controls sit in the viewfinder's corner.
 *
 * Front and back cameras only need a flip button. The camera picker appears only when an external
 * camera is connected, because a list of two built-in cameras is just a slower flip.
 */
data class CameraCornerControls(
    val showFlip: Boolean,
    val showCameraPicker: Boolean,
)

fun cameraCornerControls(selection: MediaDeviceSelectionUiState): CameraCornerControls {
    val builtInCount =
        selection.devices.count {
            it.category == MediaDeviceCategory.FRONT_CAMERA || it.category == MediaDeviceCategory.BACK_CAMERA
        }
    return CameraCornerControls(
        showFlip = builtInCount >= 2,
        showCameraPicker = selection.devices.any { it.isExternal },
    )
}
