package app.logdate.client.networking.saver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * No-op [NetworkSaverModeProvider] for platforms or tests that don't support data saver detection.
 */
class ConfigurableNetworkSaverModeProvider : NetworkSaverModeProvider {
    private val mutableState =
        MutableStateFlow(
            NetworkSaverState(
                isDataSaverEnabled = false,
                connectionType = NetworkConnectionType.WIFI,
            ),
        )

    override val dataSaverModeState: Flow<NetworkSaverState> = mutableState

    fun setNetworkSaverState(state: NetworkSaverState) {
        mutableState.value = state
    }

    override suspend fun getCurrentDataSaverState(): NetworkSaverState = mutableState.value

    override suspend fun isDataSaverModeActive(): Boolean = mutableState.value.isDataSaverEnabled

    override fun cleanup() {}
}
