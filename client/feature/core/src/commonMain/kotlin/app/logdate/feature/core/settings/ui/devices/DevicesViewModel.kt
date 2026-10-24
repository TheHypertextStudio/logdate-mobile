package app.logdate.feature.core.settings.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.device.identity.DefaultDeviceManager
import app.logdate.client.device.identity.DeviceListObservation
import app.logdate.client.device.identity.DeviceListScope
import app.logdate.client.device.identity.safeDeviceDisplayName
import app.logdate.client.device.models.DeviceInfo
import app.logdate.client.device.models.DevicePlatform
import app.logdate.client.sync.peer.devices.LinkedDeviceRemoval
import app.logdate.client.sync.peer.devices.LinkedDeviceSession
import app.logdate.client.sync.peer.devices.LinkedDevicesLoad
import app.logdate.client.sync.peer.devices.LinkedDevicesRepository
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.shared.model.devices.DeviceLinkPlatform
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_fallback_name
import logdate.client.feature.core.generated.resources.devices_last_active_unknown
import logdate.client.feature.core.generated.resources.devices_platform_android
import logdate.client.feature.core.generated.resources.devices_platform_ios
import logdate.client.feature.core.generated.resources.devices_platform_linux
import logdate.client.feature.core.generated.resources.devices_platform_macos
import logdate.client.feature.core.generated.resources.devices_platform_unknown
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
import logdate.client.feature.core.generated.resources.devices_platform_web
import logdate.client.feature.core.generated.resources.devices_platform_windows
import org.jetbrains.compose.resources.StringResource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ViewModel for managing devices screen.
 */
class DevicesViewModel(
    private val deviceManager: DefaultDeviceManager,
    private val linkedDevices: LinkedDevicesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()
    private var devicesJob: Job? = null
    private var linkedJob: Job? = null
    private var removalJob: Job? = null
    private var linkedSessions: List<LinkedDeviceSession> = emptyList()
    private var observationGeneration = 0L
    private var isObserving = false
    private var pendingCurrentName: String? = null
    private var observedScope: DeviceListScope? = null
    private var hasObservedScope = false
    private var scopeGeneration = 0L

    fun loadDevices() {
        loadLinkedDevices()
        isObserving = true
        val generation = ++observationGeneration
        devicesJob?.cancel()
        _uiState.update {
            if (it.availability == DeviceListAvailability.Available) {
                it.copy(refresh = DeviceRefreshState.Running)
            } else {
                it.copy(availability = DeviceListAvailability.Loading, refresh = DeviceRefreshState.Idle)
            }
        }
        devicesJob =
            viewModelScope.launch {
                try {
                    val currentDevice = deviceManager.getCurrentDeviceInfo()
                    launch { deviceManager.registerWithCloud() }
                    deviceManager.observeAssociatedDevices().collect { observation ->
                        if (generation == observationGeneration) applyObservation(observation, currentDevice)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Napier.e("Failed to load devices", failure)
                    if (generation == observationGeneration) {
                        _uiState.update {
                            if (it.availability == DeviceListAvailability.Available) {
                                it.copy(refresh = DeviceRefreshState.Failed)
                            } else {
                                it.copy(availability = DeviceListAvailability.Unavailable, refresh = DeviceRefreshState.Idle)
                            }
                        }
                    }
                }
            }
    }

    /**
     * Lists the devices linked to the account with their own sessions, when the account can link
     * devices. A running removal lists them itself once it finishes.
     */
    fun loadLinkedDevices() {
        if (removalJob?.isActive == true) return
        linkedJob?.cancel()
        linkedJob = viewModelScope.launch { refreshLinkedDevices(notice = null) }
    }

    /**
     * Removes linked device [id]: its session ends, and a watch this phone linked is also asked to
     * erase its data. The removal's steps run to the end even if the page closes, so a watch is never
     * left revoked without being asked to erase.
     */
    fun removeLinkedDevice(id: String) {
        if (removalJob?.isActive == true) return
        val listed = _uiState.value.linked as? LinkedDevicesUiState.Listed ?: return
        val device = linkedSessions.firstOrNull { it.id == id } ?: return
        linkedJob?.cancel()
        setLinked(listed.copy(removingId = id, notice = null))
        removalJob =
            viewModelScope.launch {
                when (val result = withContext(NonCancellable) { linkedDevices.remove(device) }) {
                    LinkedDeviceRemoval.Removed -> {
                        refreshLinkedDevices(notice = null)
                    }

                    LinkedDeviceRemoval.RemovedWillWipeOnNextConnect -> {
                        refreshLinkedDevices(notice = LinkedDeviceNotice.ErasesOnNextConnect(device.name))
                    }

                    is LinkedDeviceRemoval.Failed -> {
                        Napier.w("Linked device ${device.id} was not removed: ${result.failure}")
                        val notice = LinkedDeviceNotice.RemoveFailed(device.id, device.name, result.failure)
                        setLinked(listed.copy(removingId = null, notice = notice))
                    }
                }
            }
    }

    fun dismissLinkedDeviceNotice() {
        val listed = _uiState.value.linked as? LinkedDevicesUiState.Listed ?: return
        setLinked(listed.copy(notice = null))
    }

    private suspend fun refreshLinkedDevices(notice: LinkedDeviceNotice?) {
        if (!linkedDevices.available()) {
            setLinked(LinkedDevicesUiState.Unavailable)
            return
        }
        if (_uiState.value.linked !is LinkedDevicesUiState.Listed) setLinked(LinkedDevicesUiState.Loading)
        val state =
            when (val loaded = linkedDevices.load()) {
                LinkedDevicesLoad.Unavailable -> {
                    LinkedDevicesUiState.Unavailable
                }

                is LinkedDevicesLoad.Failed -> {
                    Napier.w("Linked devices could not be listed: ${loaded.failure}")
                    LinkedDevicesUiState.Failed(loaded.failure)
                }

                is LinkedDevicesLoad.Loaded -> {
                    linkedSessions = loaded.devices
                    LinkedDevicesUiState.Listed(loaded.devices.map { it.toUiState() }, notice = notice)
                }
            }
        setLinked(state)
    }

    private fun setLinked(state: LinkedDevicesUiState) {
        _uiState.update { it.copy(linked = state) }
    }

    private fun LinkedDeviceSession.toUiState(): LinkedDeviceUiState {
        val platformName = linkedPlatformName(platform)
        return LinkedDeviceUiState(
            id = id,
            name = deviceName(name, platformName),
            platformName = platformName,
            linkedAtFormatted = lastActiveText(linkedAt),
            lastSeenFormatted = lastActiveText(lastSeenAt),
            pairedWatch = pairedWatch,
            erasesData = platform == DeviceLinkPlatform.WEAR_OS,
        )
    }

    private fun linkedPlatformName(platform: String): StringResource =
        when (platform) {
            DeviceLinkPlatform.WEAR_OS -> getPlatformName(DevicePlatform.WEAR_OS)
            else -> getPlatformName(DevicePlatform.UNKNOWN)
        }
    private fun applyObservation(
        observation: DeviceListObservation,
        currentDevice: DeviceInfo,
    ) {
        if (!hasObservedScope || observedScope != observation.scope) {
            scopeGeneration++
            if (hasObservedScope) {
                pendingCurrentName = null
                _uiState.value = DevicesUiState()
            }
            observedScope = observation.scope
            hasObservedScope = true
        }
        when (observation) {
            is DeviceListObservation.Scoped -> Unit
            is DeviceListObservation.Available ->
                showDevices(observation.devices, currentDevice, DeviceRefreshState.Idle, confirmed = true)
            is DeviceListObservation.Refreshing -> {
                val cached = observation.cachedDevices
                if (cached != null) {
                    showDevices(cached, currentDevice, DeviceRefreshState.Running)
                } else {
                    _uiState.update {
                        if (it.availability == DeviceListAvailability.Available) {
                            it.copy(refresh = DeviceRefreshState.Running)
                        } else {
                            it.copy(availability = DeviceListAvailability.Loading, refresh = DeviceRefreshState.Idle)
                        }
                    }
                }
            }
            is DeviceListObservation.Failed -> {
                val cached = observation.cachedDevices
                if (cached != null) {
                    showDevices(cached, currentDevice, DeviceRefreshState.Failed)
                } else {
                    _uiState.update {
                        if (it.availability == DeviceListAvailability.Available) {
                            it.copy(refresh = DeviceRefreshState.Failed)
                        } else {
                            it.copy(availability = DeviceListAvailability.Unavailable, refresh = DeviceRefreshState.Idle)
                        }
                    }
                }
            }
        }
    }

    private fun showDevices(
        devices: List<DeviceInfo>,
        currentDevice: DeviceInfo,
        refresh: DeviceRefreshState,
        confirmed: Boolean = false,
    ) {
        val registeredCurrent = devices.firstOrNull { it.id == currentDevice.id } ?: currentDevice
        val pendingName = pendingCurrentName
        val visibleCurrent = if (pendingName == null) registeredCurrent else registeredCurrent.copy(name = pendingName)
        if (confirmed && devices.any { it.id == currentDevice.id && it.name == pendingName }) pendingCurrentName = null
        val allDevices = (listOf(visibleCurrent) + devices).distinctBy { it.id }
        _uiState.update {
            it.copy(
                availability = DeviceListAvailability.Available,
                devices = allDevices.map { info -> info.toUiState(info.id == currentDevice.id) },
                refresh = refresh,
            )
        }
    }

    fun stopObserving() {
        isObserving = false
        observationGeneration++
        devicesJob?.cancel()
        devicesJob = null
        _uiState.update { if (it.refresh == DeviceRefreshState.Running) it.copy(refresh = DeviceRefreshState.Idle) else it }
    }

    fun renameDevice(newName: String) {
        if (newName.isBlank()) return
        val current = _uiState.value.devices.firstOrNull { it.isCurrentDevice } ?: return
        val owner = scopeGeneration
        mutate(current.id, DeviceAction.Rename) {
            val name = newName.trim()
            deviceManager.renameDevice(name)
            if (owner == scopeGeneration) {
                pendingCurrentName = name
                updateCurrentIdentity(current.id)
            }
        }
    }

    fun removeDevice(deviceId: Uuid) {
        if (_uiState.value.devices.none { it.id == deviceId && !it.isCurrentDevice }) return
        mutate(deviceId, DeviceAction.Remove) {
            check(deviceManager.removeDevice(deviceId).getOrThrow()) { "Device removal was not accepted" }
        }
    }

    fun resetDeviceId() {
        val current = _uiState.value.devices.firstOrNull { it.isCurrentDevice } ?: return
        mutate(current.id, DeviceAction.ResetIdentity) { deviceManager.refreshDeviceId() }
    }

    fun clearOperation(deviceId: Uuid) {
        _uiState.update {
            if (it.operations[deviceId] is DeviceOperationState.Running) it else it.copy(operations = it.operations - deviceId)
        }
    }

    private fun mutate(
        deviceId: Uuid,
        action: DeviceAction,
        operation: suspend () -> Unit,
    ) {
        if (_uiState.value.operations[deviceId] is DeviceOperationState.Running) return
        val owner = scopeGeneration
        _uiState.update { it.copy(operations = it.operations + (deviceId to DeviceOperationState.Running(action))) }
        viewModelScope.launch {
            var result: DeviceOperationState? = null
            try {
                operation()
                if (owner != scopeGeneration) return@launch
                result = DeviceOperationState.Succeeded(action)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Napier.e("Failed device action: $action", failure)
                if (owner == scopeGeneration) result = DeviceOperationState.Failed(action)
            } finally {
                if (owner == scopeGeneration) {
                    val outcome = result
                    _uiState.update {
                        it.copy(operations = if (outcome == null) it.operations - deviceId else it.operations + (deviceId to outcome))
                    }
                    // A reset can change local identity before cloud registration fails or is cancelled.
                    if (action == DeviceAction.ResetIdentity) {
                        updateCurrentIdentity(deviceId)
                        if (isObserving) loadDevices()
                    }
                }
            }
            if (owner == scopeGeneration &&
                isObserving &&
                result is DeviceOperationState.Succeeded &&
                action != DeviceAction.ResetIdentity
            ) {
                loadDevices()
            }
        }
    }

    private suspend fun updateCurrentIdentity(previousId: Uuid) {
        val current = deviceManager.getCurrentDeviceInfo()
        _uiState.update {
            val operation = it.operations[previousId]
            it.copy(
                devices = it.devices.map { row -> if (row.isCurrentDevice) current.toUiState(true) else row },
                operations =
                    (it.operations - previousId).let { operations ->
                        if (operation == null) operations else operations + (current.id to operation)
                    },
            )
        }
    }

    /**
     * Converts a DeviceInfo to a DeviceInfoUiState.
     */
    private fun DeviceInfo.toUiState(isCurrentDevice: Boolean): DeviceInfoUiState {
        val platformName = getPlatformName(platform)
        return DeviceInfoUiState(
            id = id,
            name = deviceName(name, platformName),
            platformName = platformName,
            lastActiveFormatted = lastActiveText(lastActive),
            appVersion = appVersion,
            isCurrentDevice = isCurrentDevice,
        )
    }

    /**
     * Gets a user-friendly platform name.
     */
    private fun deviceName(
        name: String,
        platformName: StringResource,
    ): UiText =
        safeDeviceDisplayName(name, "").takeIf { it.isNotBlank() }?.toUiText()
            ?: Res.string.devices_fallback_name.toUiText(platformName.toUiText())

    private fun lastActiveText(timestamp: Instant): UiText =
        formatDeviceLastActive(timestamp)?.toUiText() ?: Res.string.devices_last_active_unknown.toUiText()

    private fun getPlatformName(platform: DevicePlatform): StringResource =
        when (platform) {
            DevicePlatform.ANDROID -> Res.string.devices_platform_android
            DevicePlatform.WEAR_OS -> Res.string.devices_platform_wear_os
            DevicePlatform.IOS -> Res.string.devices_platform_ios
            DevicePlatform.MACOS -> Res.string.devices_platform_macos
            DevicePlatform.WINDOWS -> Res.string.devices_platform_windows
            DevicePlatform.LINUX -> Res.string.devices_platform_linux
            DevicePlatform.WEB -> Res.string.devices_platform_web
            DevicePlatform.UNKNOWN -> Res.string.devices_platform_unknown
        }
}
