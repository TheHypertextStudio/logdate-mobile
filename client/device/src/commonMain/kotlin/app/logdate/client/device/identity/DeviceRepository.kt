package app.logdate.client.device.identity

import app.logdate.client.device.models.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Repository for managing device information and registration.
 */
interface DeviceRepository {
    /**
     * Registers a device with the cloud service.
     *
     * @param deviceInfo The device information to register
     * @param notificationToken Optional notification token for push notifications
     * @return True if registration was successful
     */
    suspend fun registerDevice(
        deviceInfo: DeviceInfo,
        notificationToken: String? = null,
    ): Boolean

    /**
     * Gets all devices associated with the current user account.
     *
     * @return Flow of device information objects
     */
    fun getAssociatedDevices(): Flow<List<DeviceInfo>>

    /** Settings observes refresh outcomes separately from cached rows. */
    fun observeAssociatedDevices(): Flow<DeviceListObservation> =
        getAssociatedDevices().map { DeviceListObservation.Available(scope = null, devices = it) }

    /**
     * Updates information about a device.
     *
     * @param deviceInfo The updated device information
     * @return True if the update was successful
     */
    suspend fun updateDeviceInfo(deviceInfo: DeviceInfo): Boolean

    /**
     * Updates the notification token for a device.
     *
     * @param deviceId The ID of the device to update
     * @param token The new notification token
     * @return True if the update was successful
     */
    suspend fun updateDeviceToken(
        deviceId: Uuid,
        token: String,
    ): Boolean

    /**
     * Removes a device from the user's account.
     *
     * @param deviceId The ID of the device to remove
     * @return True if the device was removed successfully
     */
    suspend fun removeDevice(deviceId: Uuid): Boolean
}
