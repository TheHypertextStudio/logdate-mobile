package app.logdate.client.device.identity

import app.logdate.client.device.models.DeviceInfo

/** Account identity for display ownership; credentials never belong in observations. */
data class DeviceListScope(
    val origin: String,
    val accountId: String,
)

sealed interface DeviceListObservation {
    val scope: DeviceListScope?

    /** Announces ownership before reading cache; this carries no list or request outcome. */
    data class Scoped(
        override val scope: DeviceListScope?,
    ) : DeviceListObservation

    data class Refreshing(
        override val scope: DeviceListScope?,
        val cachedDevices: List<DeviceInfo>? = null,
    ) : DeviceListObservation

    data class Available(
        override val scope: DeviceListScope?,
        val devices: List<DeviceInfo>,
    ) : DeviceListObservation

    data class Failed(
        override val scope: DeviceListScope?,
        val cachedDevices: List<DeviceInfo>? = null,
    ) : DeviceListObservation
}
