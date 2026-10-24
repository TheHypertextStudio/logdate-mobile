package app.logdate.client.networking

import app.logdate.client.networking.saver.NetworkConnectionType
import app.logdate.client.networking.saver.NetworkSaverModeProvider
import app.logdate.client.networking.saver.NetworkSaverState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default implementation of [DataUsagePolicy] that derives [DataUsageMode]
 * from the platform's [NetworkSaverModeProvider].
 *
 * The mapping is:
 * - No connection → [DataUsageMode.Restricted]
 * - Background data restricted on a metered network → [DataUsageMode.Restricted]
 * - A watch's Bluetooth link through its phone → [DataUsageMode.Conservative], metered or not
 * - Metered network without a background restriction → [DataUsageMode.Conservative]
 * - Unmetered network → [DataUsageMode.Unrestricted]
 */
class DefaultDataUsagePolicy(
    private val networkSaverModeProvider: NetworkSaverModeProvider,
) : DataUsagePolicy {
    override val policy: Flow<DataUsageMode> =
        networkSaverModeProvider.dataSaverModeState.map { it.toDataUsageMode() }

    override suspend fun currentMode(): DataUsageMode = networkSaverModeProvider.getCurrentDataSaverState().toDataUsageMode()

    override suspend fun currentRestriction(): DataRestriction = networkSaverModeProvider.getCurrentDataSaverState().toDataRestriction()
}

internal fun NetworkSaverState.toDataRestriction(): DataRestriction =
    when {
        connectionType == NetworkConnectionType.NONE -> {
            DataRestriction.OFFLINE
        }

        isDataSaverEnabled && (isMetered || connectionType == NetworkConnectionType.BLUETOOTH_PROXY) -> {
            DataRestriction.BACKGROUND_DATA_BLOCKED
        }

        else -> {
            DataRestriction.NONE
        }
    }

internal fun NetworkSaverState.toDataUsageMode(): DataUsageMode =
    when {
        connectionType == NetworkConnectionType.NONE -> DataUsageMode.Restricted
        isDataSaverEnabled && (isMetered || connectionType == NetworkConnectionType.BLUETOOTH_PROXY) -> DataUsageMode.Restricted
        connectionType == NetworkConnectionType.BLUETOOTH_PROXY -> DataUsageMode.Conservative
        isMetered -> DataUsageMode.Conservative
        else -> DataUsageMode.Unrestricted
    }
