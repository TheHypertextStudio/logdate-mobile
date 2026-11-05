package app.logdate.feature.core.settings.ui

import app.logdate.client.location.settings.DefaultLocation
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings

sealed interface LocationSettingsState {
    data object Loading : LocationSettingsState

    data object Unavailable : LocationSettingsState

    data class Available(
        val settings: LocationTrackingSettings,
        val operations: Map<LocationSetting, LocationSettingOperation> = emptyMap(),
        val isRefreshing: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : LocationSettingsState {
        fun operation(setting: LocationSetting): LocationSettingOperation = operations[setting] ?: LocationSettingOperation.Idle

        fun isSaving(setting: LocationSetting): Boolean = operation(setting) is LocationSettingOperation.Saving
    }
}

enum class LocationSetting {
    BACKGROUND_TRACKING,
    JOURNAL_TRACKING,
    TIMELINE_TRACKING,
    INTERVAL,
    CAPTURE_MODE,
    SERVER_ASSIST,
    DEFAULT_LOCATION,
}

sealed interface LocationSettingOperation {
    data object Idle : LocationSettingOperation

    data class Saving(
        val change: LocationSettingsChange,
    ) : LocationSettingOperation

    data class Failed(
        val change: LocationSettingsChange,
    ) : LocationSettingOperation
}

sealed interface LocationSettingsChange {
    data class BackgroundTracking(
        val enabled: Boolean,
    ) : LocationSettingsChange

    data class JournalTracking(
        val enabled: Boolean,
    ) : LocationSettingsChange

    data class TimelineTracking(
        val enabled: Boolean,
    ) : LocationSettingsChange

    data class Interval(
        val minutes: Long,
    ) : LocationSettingsChange

    data class CaptureMode(
        val mode: LocationCaptureMode,
    ) : LocationSettingsChange

    data class ServerAssist(
        val enabled: Boolean,
    ) : LocationSettingsChange

    data class DefaultFallback(
        val location: DefaultLocation?,
    ) : LocationSettingsChange

    val setting: LocationSetting
        get() =
            when (this) {
                is BackgroundTracking -> LocationSetting.BACKGROUND_TRACKING
                is JournalTracking -> LocationSetting.JOURNAL_TRACKING
                is TimelineTracking -> LocationSetting.TIMELINE_TRACKING
                is Interval -> LocationSetting.INTERVAL
                is CaptureMode -> LocationSetting.CAPTURE_MODE
                is ServerAssist -> LocationSetting.SERVER_ASSIST
                is DefaultFallback -> LocationSetting.DEFAULT_LOCATION
            }
}
