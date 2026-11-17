package app.logdate.client.networking.saver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [NetworkSaverModeProvider] whose connection and data saver state each test sets with
 * [setNetworkSaverState]. It starts on Wi-Fi with data saver off.
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
