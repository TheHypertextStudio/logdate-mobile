package app.logdate.client.sensor.network

import app.logdate.client.networking.saver.NetworkConnectionType
import app.logdate.client.networking.saver.NetworkSaverModeProvider
import app.logdate.client.networking.saver.NetworkSaverState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of NetworkSaverModeProvider for desktop platforms.
 *
 * This implementation is a simple wrapper around JvmNetworkSaverModeProvider
 * with desktop-specific considerations.
 */
class DesktopNetworkSaverModeProvider : NetworkSaverModeProvider {
    // Default desktop state - desktop platforms typically don't have data saver modes
    private val networkSaverState =
        MutableStateFlow(
            NetworkSaverState(
                isDataSaverEnabled = false,
                connectionType = NetworkConnectionType.ETHERNET,
            ),
        )

    override val dataSaverModeState: Flow<NetworkSaverState> = networkSaverState.asStateFlow()

    override suspend fun getCurrentDataSaverState(): NetworkSaverState = networkSaverState.value

    override suspend fun isDataSaverModeActive(): Boolean = false

    override fun cleanup() {
        // No-op for desktop
    }
}
