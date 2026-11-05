package app.logdate.client.location.settings

import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing location tracking settings.
 */
interface LocationTrackingSettingsRepository {
    /**
     * Get the current location tracking settings.
     */
    suspend fun getSettings(): LocationTrackingSettings

    /**
     * Observe the location tracking settings for changes.
     */
    fun observeSettings(): Flow<LocationTrackingSettings>

    /**
     * Update the location tracking settings.
     *
     * @param settings Complete settings snapshot to persist.
     */
    suspend fun updateSettings(settings: LocationTrackingSettings)

    /**
     * Update the background tracking enabled setting.
     *
     * @param enabled Whether background tracking should be enabled.
     */
    suspend fun setBackgroundTrackingEnabled(enabled: Boolean)

    /** Update location capture for journal entries without changing other preferences. */
    suspend fun setJournalTrackingEnabled(enabled: Boolean) {
        updateSettings(getSettings().copy(autoTrackForJournalEntries = enabled))
    }

    /** Update location capture for timeline review without changing other preferences. */
    suspend fun setTimelineTrackingEnabled(enabled: Boolean) {
        updateSettings(getSettings().copy(autoTrackForTimelineReview = enabled))
    }

    /**
     * Update the tracking interval.
     *
     * @param intervalMinutes Minimum interval between persisted location samples, in minutes.
     */
    suspend fun setTrackingInterval(intervalMinutes: Long) {
        updateSettings(getSettings().copy(minimumPersistIntervalMinutes = intervalMinutes))
    }

    /**
     * Update the active capture mode.
     *
     * @param mode Capture strategy used for background tracking.
     */
    suspend fun setCaptureMode(mode: LocationCaptureMode) {
        updateSettings(getSettings().copy(captureMode = mode))
    }

    /**
     * Update whether optional server assists are enabled.
     *
     * @param enabled Whether server-assisted location nudges should be enabled.
     */
    suspend fun setServerAssistEnabled(enabled: Boolean) {
        updateSettings(getSettings().copy(serverAssistEnabled = enabled))
    }

    /**
     * Set the fallback location used by devices without OS location services.
     *
     * @param location Default location to use, or `null` to clear the fallback.
     */
    suspend fun setDefaultLocation(location: DefaultLocation?) {
        updateSettings(getSettings().copy(defaultLocation = location))
    }
}
