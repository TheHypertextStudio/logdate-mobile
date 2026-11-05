package app.logdate.client.device.identity.data

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.identity.DeviceListObservation
import app.logdate.client.device.identity.DeviceListScope
import app.logdate.client.device.identity.DeviceRepository
import app.logdate.client.device.models.DeviceInfo
import app.logdate.client.device.models.DevicePlatform
import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.model.RegisterDeviceRequest
import app.logdate.shared.model.RegisteredDevice
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Account-scoped device metadata. Polling belongs to the screen's cancellable observation. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudDeviceRepository(
    private val api: AccountDeviceApi,
    private val sessions: SessionStorage,
    private val cache: SecureStorage,
) : DeviceRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val registrationMutex = Mutex()

    override suspend fun registerDevice(
        deviceInfo: DeviceInfo,
        notificationToken: String?,
    ): Boolean {
        val session = sessions.getOriginBoundSession() ?: return false
        return registrationMutex.withLock {
            val name = cache.getString(nameKey(session, deviceInfo.id)) ?: deviceInfo.name
            queueRegistration(session, deviceInfo.copy(name = name))
            sendPendingRegistration(session)
        }
    }

    override fun getAssociatedDevices(): Flow<List<DeviceInfo>> =
        observeAssociatedDevices().mapNotNull { observation ->
            when (observation) {
                is DeviceListObservation.Scoped -> null
                is DeviceListObservation.Refreshing -> observation.cachedDevices.orEmpty()
                is DeviceListObservation.Available -> observation.devices
                is DeviceListObservation.Failed -> observation.cachedDevices.orEmpty()
            }
        }

    override fun observeAssociatedDevices(): Flow<DeviceListObservation> =
        sessions.getSessionFlow().flatMapLatest {
            flow {
                val session = sessions.getOriginBoundSession()
                if (session == null) {
                    emit(DeviceListObservation.Available(scope = null, devices = emptyList()))
                    return@flow
                }
                val scope = DeviceListScope(session.origin, session.session.accountId)
                // Retire the previous account before reading its replacement's cache or network.
                emit(DeviceListObservation.Scoped(scope))
                val key = cacheKey(session)
                var lastKnown = cache.getString(key)?.let { runCatching { json.decodeFromString<List<RegisteredDevice>>(it) }.getOrNull() }
                if (sessions.getOriginBoundSession() != session) return@flow
                emit(DeviceListObservation.Refreshing(scope, lastKnown?.map { it.toInfo() }))
                while (currentCoroutineContext().isActive && sessions.getOriginBoundSession() == session) {
                    try {
                        registrationMutex.withLock { sendPendingRegistration(session) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        Napier.w("Couldn't register this account device", failure)
                    }
                    try {
                        val devices =
                            api.list(session).map { device ->
                                val name = cache.getString(nameKey(session, Uuid.parse(device.id)))
                                if (name != null) device.copy(name = name) else device
                            }
                        if (sessions.getOriginBoundSession() != session) return@flow
                        cache.putString(key, json.encodeToString(devices))
                        lastKnown = devices
                        emit(DeviceListObservation.Available(scope, devices.map { it.toInfo() }))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        Napier.w("Couldn't refresh the account device list", failure)
                        if (sessions.getOriginBoundSession() != session) return@flow
                        emit(DeviceListObservation.Failed(scope, lastKnown?.map { it.toInfo() }))
                    }
                    delay(5_000)
                }
            }
        }

    override suspend fun updateDeviceInfo(deviceInfo: DeviceInfo): Boolean {
        val session = sessions.getOriginBoundSession() ?: return false
        return registrationMutex.withLock {
            cache.putString(nameKey(session, deviceInfo.id), deviceInfo.name)
            queueRegistration(session, deviceInfo)
            sendPendingRegistration(session)
        }
    }

    override suspend fun updateDeviceToken(
        deviceId: Uuid,
        token: String,
    ): Boolean = false

    override suspend fun removeDevice(deviceId: Uuid): Boolean = false

    private fun cacheKey(session: OriginBoundSession): String =
        "account-devices-" +
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode("${session.origin}|${session.session.accountId}".encodeToByteArray())

    private fun nameKey(
        session: OriginBoundSession,
        id: Uuid,
    ) = "${cacheKey(session)}-name-$id"

    private suspend fun queueRegistration(
        session: OriginBoundSession,
        device: DeviceInfo,
    ) {
        val pending = PendingRegistration(device.id.toString(), RegisterDeviceRequest(device.name, device.platform.name, device.appVersion))
        cache.putString("${cacheKey(session)}-pending", json.encodeToString(pending))
    }

    private suspend fun sendPendingRegistration(session: OriginBoundSession): Boolean {
        val key = "${cacheKey(session)}-pending"
        val encoded = cache.getString(key) ?: return true
        if (!ownsScope(session)) return false
        val pending = json.decodeFromString<PendingRegistration>(encoded)
        val accepted = api.register(session, Uuid.parse(pending.id), pending.request)
        if (!ownsScope(session)) return false
        if (accepted) cache.remove(key)
        return accepted && ownsScope(session)
    }

    private fun ownsScope(captured: OriginBoundSession): Boolean {
        val current = sessions.getOriginBoundSession() ?: return false
        return current.origin == captured.origin && current.session.accountId == captured.session.accountId
    }

    @Serializable
    private data class PendingRegistration(
        val id: String,
        val request: RegisterDeviceRequest,
    )

    private fun RegisteredDevice.toInfo(): DeviceInfo =
        DeviceInfo(
            id = Uuid.parse(id),
            name = name,
            platform = DevicePlatform.entries.firstOrNull { it.name == platform } ?: DevicePlatform.UNKNOWN,
            appVersion = appVersion,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            lastActive = Instant.fromEpochMilliseconds(lastActive),
        )
}
