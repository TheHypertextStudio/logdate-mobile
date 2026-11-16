package app.logdate.client.sensor.battery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [BatteryInfoProvider] for the desktop app. It reports a fixed battery state, since the JVM
 * exposes none.
 */
class JvmBatteryInfoProvider : BatteryInfoProvider {
    // Default to a reasonable battery state for JVM
    private val batteryStateFlow =
        MutableStateFlow(
            BatteryState(
                level = 100,
                isCharging = true,
                isPowerSaveMode = false,
            ),
        )

    override val currentBatteryState: Flow<BatteryState> = batteryStateFlow.asStateFlow()

    override suspend fun getCurrentBatteryState(): BatteryState = batteryStateFlow.value

    override suspend fun isPowerSaveMode(): Boolean = false

    override fun cleanup() {
        // No-op for JVM
    }
}
