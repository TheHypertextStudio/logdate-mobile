package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.device.identity.DeviceRepository
import app.logdate.client.device.models.DeviceInfo
import app.logdate.client.device.models.DevicePlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class FakeDeviceIdProvider(
    initialDeviceId: Uuid,
) : DeviceIdProvider {
    private val deviceId = MutableStateFlow(initialDeviceId)
    var refreshCallCount = 0
    var refreshFailure: Exception? = null

    override fun getDeviceId(): StateFlow<Uuid> = deviceId

    override suspend fun refreshDeviceId() {
        refreshCallCount++
        refreshFailure?.let { throw it }
        deviceId.value = Uuid.parse("423e4567-e89b-12d3-a456-426614174000")
    }
}

internal class FakeDeviceRepository : DeviceRepository {
    private val associatedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    var updateDeviceInfoCallCount = 0
    var removeCallCount = 0
    var observationCallCount = 0
    var lastRemovedDeviceId: Uuid? = null
    var shouldFailGetAssociatedDevices = false
    var shouldFailDeviceRemoval = false
    var observationGate: CompletableDeferred<Unit>? = null
    var observationOverride: (() -> Flow<List<DeviceInfo>>)? = null
    var removalGate: CompletableDeferred<Unit>? = null
    var updateGate: CompletableDeferred<Unit>? = null
    var removalFailure: Exception? = null
    var updateFailure: Exception? = null
    var updateAccepted = true
    var removalAccepted = true
    var registrationAccepted = true
    var registrationFailure: Exception? = null

    override suspend fun registerDevice(
        deviceInfo: DeviceInfo,
        notificationToken: String?,
    ): Boolean {
        registrationFailure?.let { throw it }
        return registrationAccepted
    }

    override fun getAssociatedDevices(): Flow<List<DeviceInfo>> {
        observationCallCount++
        observationOverride?.let { return it() }
        if (shouldFailGetAssociatedDevices) return flow { throw IllegalStateException("Private provider detail") }
        return flow {
            observationGate?.await()
            associatedDevices.collect { emit(it) }
        }
    }

    override suspend fun updateDeviceInfo(deviceInfo: DeviceInfo): Boolean {
        updateDeviceInfoCallCount++
        updateGate?.await()
        updateFailure?.let { throw it }
        return updateAccepted
    }

    override suspend fun updateDeviceToken(
        deviceId: Uuid,
        token: String,
    ): Boolean = true

    override suspend fun removeDevice(deviceId: Uuid): Boolean {
        removeCallCount++
        lastRemovedDeviceId = deviceId
        removalGate?.await()
        removalFailure?.let { throw it }
        if (shouldFailDeviceRemoval) throw IllegalStateException("Private provider detail")
        if (!removalAccepted) return false
        associatedDevices.value = associatedDevices.value.filter { it.id != deviceId }
        return true
    }

    fun addAssociatedDevice(device: DeviceInfo) {
        associatedDevices.value = associatedDevices.value + device
    }
}

internal fun deviceInfo(
    id: Uuid,
    name: String,
    platform: DevicePlatform,
): DeviceInfo =
    DeviceInfo(
        id = id,
        name = name,
        platform = platform,
        createdAt = Clock.System.now(),
        lastActive = Clock.System.now(),
        appVersion = "1.0.0",
        isCurrentDevice = false,
    )

internal val unavailableLinkedDevices =
    object : app.logdate.client.sync.peer.devices.LinkedDevicesRepository {
        override suspend fun available(): Boolean = false
        override suspend fun load() = app.logdate.client.sync.peer.devices.LinkedDevicesLoad.Unavailable
        override suspend fun remove(device: app.logdate.client.sync.peer.devices.LinkedDeviceSession) =
            app.logdate.client.sync.peer.devices.LinkedDeviceRemoval.Failed(
                app.logdate.client.sync.peer.devices.LinkedDevicesFailure.SignInRequired,
            )
    }
