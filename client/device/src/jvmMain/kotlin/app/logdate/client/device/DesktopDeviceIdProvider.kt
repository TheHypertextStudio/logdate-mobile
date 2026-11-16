package app.logdate.client.device

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.InetAddress
import java.util.UUID

/**
 * Desktop implementation of InstanceIdProvider that generates a device ID
 * based on hostname or a random UUID if hostname cannot be determined.
 */
object DesktopDeviceIdProvider : InstanceIdProvider {
    private val _currentInstanceId = MutableStateFlow(generateDesktopDeviceId())

    override val currentInstanceId: SharedFlow<String> = _currentInstanceId.asSharedFlow()

    override fun resetInstanceId() {
        _currentInstanceId.value = generateDesktopDeviceId()
        Napier.i("Reset desktop device ID: ${_currentInstanceId.value}")
    }

    /**
     * Generates a unique device ID for desktop platforms based on hostname.
     * Falls back to a random UUID if hostname cannot be determined.
     */
    private fun generateDesktopDeviceId(): String {
        val hostname =
            try {
                InetAddress.getLocalHost().hostName
            } catch (e: Exception) {
                Napier.w("Could not determine hostname, using random ID", e)
                UUID.randomUUID().toString().take(8)
            }

        return "desktop_$hostname"
    }
}
