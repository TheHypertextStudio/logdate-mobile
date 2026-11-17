package app.logdate.client.sensor.battery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Static [BatteryInfoProvider] for tests and platforms where battery information is unavailable.
 */
class StaticBatteryInfoProvider : BatteryInfoProvider {
    private val batteryStateFlow =
        MutableStateFlow(
            BatteryState(
                level = 100,
                isCharging = true,
                isPowerSaveMode = false,
            ),
        )

    override val currentBatteryState: Flow<BatteryState> = batteryStateFlow

    override suspend fun getCurrentBatteryState(): BatteryState = batteryStateFlow.value

    override suspend fun isPowerSaveMode(): Boolean = batteryStateFlow.value.isPowerSaveMode

    override fun cleanup() {
    }
}
