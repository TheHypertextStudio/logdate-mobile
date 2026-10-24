package app.logdate.feature.editor.ui.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import app.logdate.client.media.device.DefaultMediaDevices
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.media.device.MediaDeviceUiState
import app.logdate.client.media.device.unavailableRouteMessage
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.capture_state_camera_numbered
import logdate.client.feature.editor.generated.resources.capture_state_external_camera_numbered
import logdate.client.feature.editor.generated.resources.capture_state_external_cameras_hint
import org.jetbrains.compose.resources.StringResource

/** Camera enumeration and saved route selection used by preview binding. */
internal class AndroidCameraDeviceSelection(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(CAMERA_ROUTE_PREFS_NAME, Context.MODE_PRIVATE)

    fun cameraSelectorForDeviceId(deviceId: String?): CameraSelector {
        val cameraId = deviceId?.removePrefix(CAMERA_DEVICE_ID_PREFIX)
        if (cameraId.isNullOrBlank() || cameraId == deviceId) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        return CameraSelector
            .Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { info ->
                    Camera2CameraInfo.from(info).cameraId == cameraId
                }
            }.build()
    }

    fun cameraSelectionFor(
        provider: ProcessCameraProvider,
        preferredDeviceId: String? = null,
        fallbackFacing: CameraFacing = CameraFacing.BACK,
        requireBuiltIn: Boolean = false,
    ): MediaDeviceSelectionUiState {
        val devices =
            provider
                .availableCameraInfos
                .mapIndexed { index, cameraInfo -> cameraInfo.toMediaDeviceUiState(index) }
                .filter {
                    !requireBuiltIn ||
                        it.category == MediaDeviceCategory.FRONT_CAMERA ||
                        it.category == MediaDeviceCategory.BACK_CAMERA
                }.ifEmpty { defaultCameraSelection(fallbackFacing).devices }

        val selectedId =
            preferredDeviceId?.takeIf { preferred -> devices.any { it.id == preferred } }
                ?: devices.firstOrNull { it.category.matchesFacing(fallbackFacing) }?.id
                ?: devices.first().id
        val routeControlMessage =
            when {
                preferredDeviceId != null && preferredDeviceId != selectedId -> {
                    unavailableRouteMessage(MediaDeviceKind.CAMERA, devices.first { it.id == selectedId }.label)
                }

                devices.none { it.isExternal } -> {
                    Res.string.capture_state_external_cameras_hint.toUiText()
                }

                else -> {
                    null
                }
            }

        return MediaDeviceSelectionUiState(
            kind = MediaDeviceKind.CAMERA,
            devices = devices,
            selectedDeviceId = selectedId,
            isSelectionControllable = devices.size > 1,
            routeControlMessage = routeControlMessage,
        )
    }

    fun preferredCameraDeviceId(): String? = preferences.getString(KEY_PREFERRED_CAMERA_DEVICE_ID, null)

    fun persistPreferredCameraDeviceId(deviceId: String?) {
        if (deviceId == null) return
        preferences
            .edit()
            .putString(KEY_PREFERRED_CAMERA_DEVICE_ID, deviceId)
            .apply()
    }

    private fun CameraInfo.toMediaDeviceUiState(index: Int): MediaDeviceUiState {
        val camera2Info = Camera2CameraInfo.from(this)
        val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        val category = lensFacing.toCameraCategory()
        return MediaDeviceUiState(
            id = "$CAMERA_DEVICE_ID_PREFIX${camera2Info.cameraId}",
            label = cameraLabel(category, index),
            kind = MediaDeviceKind.CAMERA,
            category = category,
            isExternal = category == MediaDeviceCategory.EXTERNAL,
        )
    }

    private fun Int?.toCameraCategory(): MediaDeviceCategory =
        when (this) {
            CameraCharacteristics.LENS_FACING_FRONT -> MediaDeviceCategory.FRONT_CAMERA
            CameraCharacteristics.LENS_FACING_BACK -> MediaDeviceCategory.BACK_CAMERA
            CameraCharacteristics.LENS_FACING_EXTERNAL -> MediaDeviceCategory.EXTERNAL
            else -> MediaDeviceCategory.EXTERNAL
        }

    private fun cameraLabel(
        category: MediaDeviceCategory,
        index: Int,
    ): UiText =
        when (category) {
            MediaDeviceCategory.FRONT_CAMERA -> DefaultMediaDevices.frontCamera.label
            MediaDeviceCategory.BACK_CAMERA -> DefaultMediaDevices.backCamera.label
            MediaDeviceCategory.EXTERNAL -> Res.string.capture_state_external_camera_numbered.toUiText(index + 1)
            else -> Res.string.capture_state_camera_numbered.toUiText(index + 1)
        }

    private companion object {
        const val CAMERA_DEVICE_ID_PREFIX = "camera-"
        const val CAMERA_ROUTE_PREFS_NAME = "logdate_camera_routes"
        const val KEY_PREFERRED_CAMERA_DEVICE_ID = "preferred_camera_device_id"
    }
}

/** The platform's own words for a camera failure, or [fallback] when it gives none. */
internal fun Exception.cameraFailure(fallback: StringResource): CameraCaptureError =
    CameraCaptureError.Unknown(message?.toUiText() ?: fallback.toUiText())

internal fun MediaDeviceCategory.matchesFacing(facing: CameraFacing): Boolean =
    when (facing) {
        CameraFacing.FRONT -> this == MediaDeviceCategory.FRONT_CAMERA
        CameraFacing.BACK -> this == MediaDeviceCategory.BACK_CAMERA
    }

internal fun MediaDeviceSelectionUiState.facingForSelection(): CameraFacing =
    when (selectedDevice?.category) {
        MediaDeviceCategory.FRONT_CAMERA -> CameraFacing.FRONT
        else -> CameraFacing.BACK
    }
