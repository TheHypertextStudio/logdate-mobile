package app.logdate.client.media.device

import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import logdate.client.media.generated.resources.Res
import logdate.client.media.generated.resources.media_device_microphone_checking
import logdate.client.media.generated.resources.media_device_microphone_not_active

internal object MediaDeviceSelectionResolver {
    fun resolveAudioInput(
        devices: List<MediaDeviceUiState>,
        preferredDeviceId: String?,
    ): MediaDeviceSelectionUiState {
        val availableDevices = devices.ifEmpty { listOf(DefaultMediaDevices.systemMicrophone) }
        val selectedId =
            preferredDeviceId
                ?.takeIf { preferred -> availableDevices.any { it.id == preferred } }
                ?: availableDevices.first().id
        val routeControlMessage =
            when {
                preferredDeviceId != null && preferredDeviceId != selectedId -> {
                    unavailableRouteMessage(MediaDeviceKind.AUDIO_INPUT, availableDevices.first().label)
                }

                availableDevices.size == 1 &&
                    availableDevices.first().category == MediaDeviceCategory.SYSTEM_DEFAULT -> {
                    systemControlledRouteControlMessage(MediaDeviceKind.AUDIO_INPUT)
                }

                else -> {
                    null
                }
            }

        return MediaDeviceSelectionUiState(
            kind = MediaDeviceKind.AUDIO_INPUT,
            devices = availableDevices,
            selectedDeviceId = selectedId,
            isSelectionControllable = availableDevices.any { it.category != MediaDeviceCategory.SYSTEM_DEFAULT },
            routeControlMessage = routeControlMessage,
        )
    }

    fun resolveRecordingAudioInput(
        selection: MediaDeviceSelectionUiState,
        routedDeviceId: String?,
        error: UiText?,
    ): MediaDeviceSelectionUiState {
        val routed = selection.devices.firstOrNull { it.id == routedDeviceId && it.isAvailable }
        return selection.copy(
            selectedDeviceId = routed?.id,
            isSelectionConfirmed = routed != null,
            routeControlMessage =
                error ?: when {
                    routed == null -> {
                        Res.string.media_device_microphone_checking.toUiText()
                    }

                    routed.id != selection.selectedDeviceId -> {
                        Res.string.media_device_microphone_not_active.toUiText(routed.label)
                    }

                    else -> {
                        null
                    }
                },
        )
    }

    fun resolveAudioOutput(
        devices: List<MediaDeviceUiState>,
        preferredDeviceId: String?,
    ): MediaDeviceSelectionUiState {
        val availableDevices =
            collapseToPhysicalDevices(devices).ifEmpty { listOf(DefaultMediaDevices.systemOutput) }
        val selectedId =
            preferredDeviceId
                ?.let { preferred -> resolveWithinGroup(preferred, devices, availableDevices) }
                ?: availableDevices.first().id

        return MediaDeviceSelectionUiState(
            kind = MediaDeviceKind.AUDIO_OUTPUT,
            devices = availableDevices,
            selectedDeviceId = selectedId,
            isSelectionControllable =
                availableDevices.any { it.category != MediaDeviceCategory.SYSTEM_DEFAULT },
            routeControlMessage =
                systemControlledRouteControlMessage(MediaDeviceKind.AUDIO_OUTPUT)
                    .takeIf {
                        availableDevices.all { device ->
                            device.category == MediaDeviceCategory.SYSTEM_DEFAULT
                        }
                    },
        )
    }

    /**
     * Keeps one entry per physical device: the best-quality profile in each group, in the order
     * the groups were first seen.
     */
    private fun collapseToPhysicalDevices(devices: List<MediaDeviceUiState>): List<MediaDeviceUiState> =
        devices
            .groupBy { it.groupKey }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.qualityRank } }
            .sortedBy { collapsed -> devices.indexOfFirst { it.groupKey == collapsed.groupKey } }

    /**
     * Maps a stored preference onto the surviving row. A preference recorded against a profile
     * that later lost its group — the A2DP entry when LE Audio connects, say — must still select
     * that device rather than silently falling back to the built-in speaker.
     */
    private fun resolveWithinGroup(
        preferredDeviceId: String,
        allDevices: List<MediaDeviceUiState>,
        availableDevices: List<MediaDeviceUiState>,
    ): String? {
        availableDevices.firstOrNull { it.id == preferredDeviceId }?.let { return it.id }
        val preferredGroup = allDevices.firstOrNull { it.id == preferredDeviceId }?.groupKey ?: return null
        return availableDevices.firstOrNull { it.groupKey == preferredGroup }?.id
    }
}
