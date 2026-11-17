package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.sync.peer.devices.LinkedDevicesFailure
import app.logdate.client.text.UiText
import org.jetbrains.compose.resources.StringResource

/** What the Devices page shows about devices linked to the account with their own sessions. */
sealed interface LinkedDevicesUiState {
    /** Nothing is shown: device linking is off, the phone is signed out, or the server does not link devices. */
    data object Unavailable : LinkedDevicesUiState

    data object Loading : LinkedDevicesUiState

    data class Failed(
        val failure: LinkedDevicesFailure,
    ) : LinkedDevicesUiState

    /**
     * @property removingId The device whose removal is running.
     * @property notice What the last removal needs to tell the person, until they dismiss it.
     */
    data class Listed(
        val devices: List<LinkedDeviceUiState>,
        val removingId: String? = null,
        val notice: LinkedDeviceNotice? = null,
    ) : LinkedDevicesUiState
}

/**
 * One linked device.
 *
 * @property pairedWatch The watch paired with this phone, which the page already shows as its watch.
 * @property erasesData The device is a watch, which erases its LogDate data, unsent moments included,
 *   once its session ends.
 */
data class LinkedDeviceUiState(
    val id: String,
    val name: UiText,
    val platformName: StringResource,
    val lastSeenFormatted: UiText,
    val pairedWatch: Boolean,
    val erasesData: Boolean,
    val pairedWatchNodeId: String? = null,
)

/** The outcome of a removal that the list alone does not show. */
sealed interface LinkedDeviceNotice {
    val deviceName: String

    /** The device can no longer sync, and erases its LogDate data the next time it connects to this phone. */
    data class ErasesOnNextConnect(
        override val deviceName: String,
    ) : LinkedDeviceNotice

    /** The device was erased, and [count] moments it couldn't sync were cleared with it; null when it couldn't say how many. */
    data class ClearedUnsent(
        override val deviceName: String,
        val count: Int?,
    ) : LinkedDeviceNotice

    /** Removing [deviceId] failed; it is still linked unless [failure] says otherwise. */
    data class RemoveFailed(
        val deviceId: String,
        override val deviceName: String,
        val failure: LinkedDevicesFailure,
    ) : LinkedDeviceNotice
}
