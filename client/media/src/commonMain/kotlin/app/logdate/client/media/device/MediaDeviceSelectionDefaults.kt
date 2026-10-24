package app.logdate.client.media.device

import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import logdate.client.media.generated.resources.Res
import logdate.client.media.generated.resources.media_device_camera_system_controlled
import logdate.client.media.generated.resources.media_device_camera_unavailable
import logdate.client.media.generated.resources.media_device_microphone_system_controlled
import logdate.client.media.generated.resources.media_device_microphone_unavailable
import logdate.client.media.generated.resources.media_device_output_system_controlled
import logdate.client.media.generated.resources.media_device_output_unavailable

internal fun systemControlledRouteControlMessage(kind: MediaDeviceKind): UiText =
    when (kind) {
        MediaDeviceKind.CAMERA -> Res.string.media_device_camera_system_controlled.toUiText()
        MediaDeviceKind.AUDIO_INPUT -> Res.string.media_device_microphone_system_controlled.toUiText()
        MediaDeviceKind.AUDIO_OUTPUT -> Res.string.media_device_output_system_controlled.toUiText()
    }

/** Says that the selected device is gone and names the device used in its place. */
fun unavailableRouteMessage(
    kind: MediaDeviceKind,
    replacementLabel: UiText,
): UiText =
    when (kind) {
        MediaDeviceKind.CAMERA -> Res.string.media_device_camera_unavailable.toUiText(replacementLabel)
        MediaDeviceKind.AUDIO_INPUT -> Res.string.media_device_microphone_unavailable.toUiText(replacementLabel)
        MediaDeviceKind.AUDIO_OUTPUT -> Res.string.media_device_output_unavailable.toUiText(replacementLabel)
    }

fun systemControlledSelection(kind: MediaDeviceKind): MediaDeviceSelectionUiState =
    when (kind) {
        MediaDeviceKind.CAMERA -> {
            MediaDeviceSelectionUiState(
                kind = MediaDeviceKind.CAMERA,
                devices = listOf(DefaultMediaDevices.backCamera),
                selectedDeviceId = DefaultMediaDevices.backCamera.id,
                isSelectionControllable = false,
                routeControlMessage = systemControlledRouteControlMessage(MediaDeviceKind.CAMERA),
            )
        }

        MediaDeviceKind.AUDIO_INPUT -> {
            MediaDeviceSelectionUiState(
                kind = MediaDeviceKind.AUDIO_INPUT,
                devices = listOf(DefaultMediaDevices.systemMicrophone),
                selectedDeviceId = DefaultMediaDevices.systemMicrophone.id,
                isSelectionControllable = false,
                routeControlMessage = systemControlledRouteControlMessage(MediaDeviceKind.AUDIO_INPUT),
            )
        }

        MediaDeviceKind.AUDIO_OUTPUT -> {
            MediaDeviceSelectionUiState(
                kind = MediaDeviceKind.AUDIO_OUTPUT,
                devices = listOf(DefaultMediaDevices.systemOutput),
                selectedDeviceId = DefaultMediaDevices.systemOutput.id,
                isSelectionControllable = false,
                routeControlMessage = systemControlledRouteControlMessage(MediaDeviceKind.AUDIO_OUTPUT),
            )
        }
    }

fun MediaDeviceSelectionUiState.asSystemControlledSelection(
    routeControlMessage: UiText = systemControlledRouteControlMessage(kind),
): MediaDeviceSelectionUiState =
    copy(
        isSelectionControllable = false,
        routeControlMessage = routeControlMessage,
    )
