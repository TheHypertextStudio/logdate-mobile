package app.logdate.client.sensor.battery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of BatteryInfoProvider for desktop platforms.
 *
 * Desktop platforms typically don't provide easy access to battery information
 * through a standardized API. This implementation provides reasonable defaults
 * and could be extended in the future to use platform-specific libraries for
 * more accurate battery information.
 */
class DesktopBatteryInfoProvider : BatteryInfoProvider {
    // Default to a reasonable battery state for desktop
    private val batteryState =
        MutableStateFlow(
            BatteryState(
                level = 100,
                isCharging = true,
                isPowerSaveMode = false,
            ),
        )

    override val currentBatteryState: Flow<BatteryState> = batteryState.asStateFlow()

    override suspend fun getCurrentBatteryState(): BatteryState = batteryState.value

    override suspend fun isPowerSaveMode(): Boolean = false

    override fun cleanup() {
        // No-op for desktop
    }
}
