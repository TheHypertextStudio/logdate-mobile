package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.text.UiText
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

sealed interface DeviceListAvailability {
    data object Loading : DeviceListAvailability

    data object Available : DeviceListAvailability

    data object Unavailable : DeviceListAvailability
}

sealed interface DeviceRefreshState {
    data object Idle : DeviceRefreshState

    data object Running : DeviceRefreshState

    data object Failed : DeviceRefreshState
}

enum class DeviceAction {
    Rename,
    Remove,
    ResetIdentity,
}

sealed interface DeviceOperationState {
    val action: DeviceAction

    data class Running(
        override val action: DeviceAction,
    ) : DeviceOperationState

    data class Failed(
        override val action: DeviceAction,
    ) : DeviceOperationState

    data class Succeeded(
        override val action: DeviceAction,
    ) : DeviceOperationState
}

data class DevicesUiState(
    val availability: DeviceListAvailability = DeviceListAvailability.Loading,
    val devices: List<DeviceInfoUiState> = emptyList(),
    val refresh: DeviceRefreshState = DeviceRefreshState.Idle,
    val operations: Map<Uuid, DeviceOperationState> = emptyMap(),
    val linked: LinkedDevicesUiState = LinkedDevicesUiState.Unavailable,
)

data class DeviceInfoUiState(
    val id: Uuid,
    val name: UiText,
    val platformName: StringResource,
    val lastActiveFormatted: UiText,
    val appVersion: String,
    val isCurrentDevice: Boolean,
)
