package app.logdate.client.location.settings

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.withFailureReporting
import app.logdate.shared.model.AltitudeUnit
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Default implementation of [LocationTrackingSettingsRepository] using [KeyValueStorage].
 */
class DefaultLocationTrackingSettingsRepository(
    private val keyValueStorage: KeyValueStorage,
) : LocationTrackingSettingsRepository {
    internal fun reportingFailures(): LocationTrackingSettingsRepository =
        DefaultLocationTrackingSettingsRepository(keyValueStorage.withFailureReporting())

    companion object {
        private const val KEY_BACKGROUND_TRACKING_ENABLED = "location_background_tracking_enabled"
        private const val KEY_MINIMUM_PERSIST_INTERVAL_MINUTES = "location_tracking_interval_minutes"
        private const val KEY_AUTO_TRACK_JOURNAL = "location_auto_track_journal"
        private const val KEY_AUTO_TRACK_TIMELINE = "location_auto_track_timeline"
        private const val KEY_CAPTURE_MODE = "location_capture_mode"
        private const val KEY_SERVER_ASSIST_ENABLED = "location_server_assist_enabled"
        private const val KEY_DEFAULT_LOCATION_LATITUDE = "location_default_latitude"
        private const val KEY_DEFAULT_LOCATION_LONGITUDE = "location_default_longitude"
        private const val KEY_DEFAULT_LOCATION_ALTITUDE = "location_default_altitude"
        private const val KEY_DEFAULT_LOCATION_ALTITUDE_UNIT = "location_default_altitude_unit"

        // Default values
        private const val DEFAULT_TRACKING_INTERVAL_MINUTES = 30L
    }

    override suspend fun getSettings(): LocationTrackingSettings =
        LocationTrackingSettings(
            backgroundTrackingEnabled =
                keyValueStorage.getBoolean(
                    KEY_BACKGROUND_TRACKING_ENABLED,
                    false,
                ),
            minimumPersistIntervalMinutes =
                keyValueStorage
                    .getLong(
                        KEY_MINIMUM_PERSIST_INTERVAL_MINUTES,
                        DEFAULT_TRACKING_INTERVAL_MINUTES,
                    ).coerceAtLeast(2),
            captureMode = parseCaptureMode(keyValueStorage.getString(KEY_CAPTURE_MODE)),
            serverAssistEnabled =
                keyValueStorage.getBoolean(
                    KEY_SERVER_ASSIST_ENABLED,
                    false,
                ),
            autoTrackForJournalEntries =
                keyValueStorage.getBoolean(
                    KEY_AUTO_TRACK_JOURNAL,
                    true,
                ),
            autoTrackForTimelineReview =
                keyValueStorage.getBoolean(
                    KEY_AUTO_TRACK_TIMELINE,
                    true,
                ),
            defaultLocation = getDefaultLocation(),
        )

    override fun observeSettings(): Flow<LocationTrackingSettings> =
        combine(
            combine(
                keyValueStorage.observeBoolean(KEY_BACKGROUND_TRACKING_ENABLED, false),
                keyValueStorage
                    .observeLong(KEY_MINIMUM_PERSIST_INTERVAL_MINUTES, DEFAULT_TRACKING_INTERVAL_MINUTES)
                    .map { it.coerceAtLeast(2) },
                keyValueStorage
                    .observeString(KEY_CAPTURE_MODE)
                    .map(::parseCaptureMode),
                keyValueStorage.observeBoolean(KEY_SERVER_ASSIST_ENABLED, false),
            ) { backgroundEnabled, interval, captureMode, serverAssistEnabled ->
                BaseSettingsSnapshot(
                    backgroundEnabled = backgroundEnabled,
                    interval = interval,
                    captureMode = captureMode,
                    serverAssistEnabled = serverAssistEnabled,
                )
            },
            keyValueStorage.observeBoolean(KEY_AUTO_TRACK_JOURNAL, true),
            keyValueStorage.observeBoolean(KEY_AUTO_TRACK_TIMELINE, true),
            observeDefaultLocation(),
        ) { base, autoJournal, autoTimeline, defaultLocation ->
            LocationTrackingSettings(
                backgroundTrackingEnabled = base.backgroundEnabled,
                minimumPersistIntervalMinutes = base.interval,
                captureMode = base.captureMode,
                serverAssistEnabled = base.serverAssistEnabled,
                autoTrackForJournalEntries = autoJournal,
                autoTrackForTimelineReview = autoTimeline,
                defaultLocation = defaultLocation,
            )
        }

    override suspend fun updateSettings(settings: LocationTrackingSettings) {
        Napier.i(
            "Updating location tracking settings: " +
                "background=${settings.backgroundTrackingEnabled}, " +
                "interval=${settings.minimumPersistIntervalMinutes}, " +
                "captureMode=${settings.captureMode}, " +
                "serverAssist=${settings.serverAssistEnabled}, " +
                "autoJournal=${settings.autoTrackForJournalEntries}, " +
                "autoTimeline=${settings.autoTrackForTimelineReview}, " +
                "defaultLocationConfigured=${settings.defaultLocation != null}",
        )

        keyValueStorage.putBoolean(
            KEY_BACKGROUND_TRACKING_ENABLED,
            settings.backgroundTrackingEnabled,
        )

        keyValueStorage.putLong(
            KEY_MINIMUM_PERSIST_INTERVAL_MINUTES,
            settings.minimumPersistIntervalMinutes.coerceAtLeast(2),
        )

        keyValueStorage.putString(
            KEY_CAPTURE_MODE,
            settings.captureMode.name,
        )

        keyValueStorage.putBoolean(
            KEY_SERVER_ASSIST_ENABLED,
            settings.serverAssistEnabled,
        )

        keyValueStorage.putBoolean(
            KEY_AUTO_TRACK_JOURNAL,
            settings.autoTrackForJournalEntries,
        )

        keyValueStorage.putBoolean(
            KEY_AUTO_TRACK_TIMELINE,
            settings.autoTrackForTimelineReview,
        )

        persistDefaultLocation(settings.defaultLocation)
    }

    override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
        Napier.i("Setting background tracking enabled: $enabled")
        keyValueStorage.putBoolean(KEY_BACKGROUND_TRACKING_ENABLED, enabled)
    }

    override suspend fun setJournalTrackingEnabled(enabled: Boolean) {
        keyValueStorage.putBoolean(KEY_AUTO_TRACK_JOURNAL, enabled)
    }

    override suspend fun setTimelineTrackingEnabled(enabled: Boolean) {
        keyValueStorage.putBoolean(KEY_AUTO_TRACK_TIMELINE, enabled)
    }

    override suspend fun setTrackingInterval(intervalMinutes: Long) {
        val safeInterval = intervalMinutes.coerceAtLeast(2)
        Napier.i("Setting tracking interval: $safeInterval minutes")
        keyValueStorage.putLong(KEY_MINIMUM_PERSIST_INTERVAL_MINUTES, safeInterval)
    }

    override suspend fun setCaptureMode(mode: LocationCaptureMode) {
        Napier.i("Setting capture mode: $mode")
        keyValueStorage.putString(KEY_CAPTURE_MODE, mode.name)
    }

    override suspend fun setServerAssistEnabled(enabled: Boolean) {
        Napier.i("Setting server assist enabled: $enabled")
        keyValueStorage.putBoolean(KEY_SERVER_ASSIST_ENABLED, enabled)
    }

    override suspend fun setDefaultLocation(location: DefaultLocation?) {
        Napier.i("Setting default fallback location configured: ${location != null}")
        persistDefaultLocation(location)
    }

    private fun parseCaptureMode(value: String?): LocationCaptureMode =
        when (value) {
            "STABLE", "PASSIVE", null -> LocationCaptureMode.PASSIVE
            "EXPERIMENT_MIRRORED", "ACTIVE" -> LocationCaptureMode.ACTIVE
            else -> {
                Napier.w("Unknown stored location capture mode '$value'; falling back to PASSIVE")
                LocationCaptureMode.PASSIVE
            }
        }

    private data class BaseSettingsSnapshot(
        val backgroundEnabled: Boolean,
        val interval: Long,
        val captureMode: LocationCaptureMode,
        val serverAssistEnabled: Boolean,
    )

    private suspend fun getDefaultLocation(): DefaultLocation? =
        parseDefaultLocation(
            latitude = keyValueStorage.getString(KEY_DEFAULT_LOCATION_LATITUDE),
            longitude = keyValueStorage.getString(KEY_DEFAULT_LOCATION_LONGITUDE),
            altitude = keyValueStorage.getString(KEY_DEFAULT_LOCATION_ALTITUDE),
            altitudeUnit = keyValueStorage.getString(KEY_DEFAULT_LOCATION_ALTITUDE_UNIT),
        )

    private fun observeDefaultLocation(): Flow<DefaultLocation?> =
        combine(
            keyValueStorage.observeString(KEY_DEFAULT_LOCATION_LATITUDE),
            keyValueStorage.observeString(KEY_DEFAULT_LOCATION_LONGITUDE),
            keyValueStorage.observeString(KEY_DEFAULT_LOCATION_ALTITUDE),
            keyValueStorage.observeString(KEY_DEFAULT_LOCATION_ALTITUDE_UNIT),
            ::parseDefaultLocation,
        )

    private suspend fun persistDefaultLocation(location: DefaultLocation?) {
        if (location == null) {
            keyValueStorage.remove(KEY_DEFAULT_LOCATION_LATITUDE)
            keyValueStorage.remove(KEY_DEFAULT_LOCATION_LONGITUDE)
            keyValueStorage.remove(KEY_DEFAULT_LOCATION_ALTITUDE)
            keyValueStorage.remove(KEY_DEFAULT_LOCATION_ALTITUDE_UNIT)
            return
        }

        keyValueStorage.putString(KEY_DEFAULT_LOCATION_LATITUDE, location.latitude.toString())
        keyValueStorage.putString(KEY_DEFAULT_LOCATION_LONGITUDE, location.longitude.toString())
        keyValueStorage.putString(KEY_DEFAULT_LOCATION_ALTITUDE, location.altitudeValue.toString())
        keyValueStorage.putString(KEY_DEFAULT_LOCATION_ALTITUDE_UNIT, location.altitudeUnit.name)
    }

    private fun parseDefaultLocation(
        latitude: String?,
        longitude: String?,
        altitude: String?,
        altitudeUnit: String?,
    ): DefaultLocation? {
        val parsedLatitude = latitude?.toDoubleOrNull() ?: return null
        val parsedLongitude = longitude?.toDoubleOrNull() ?: return null
        if (parsedLatitude !in -90.0..90.0 || parsedLongitude !in -180.0..180.0) {
            Napier.w("Ignoring invalid stored default location coordinates")
            return null
        }

        val parsedAltitude = altitude?.toDoubleOrNull() ?: 0.0
        val parsedAltitudeUnit =
            altitudeUnit
                ?.let { value -> runCatching { AltitudeUnit.valueOf(value) }.getOrNull() }
                ?: AltitudeUnit.METERS

        return DefaultLocation(
            latitude = parsedLatitude,
            longitude = parsedLongitude,
            altitudeValue = parsedAltitude,
            altitudeUnit = parsedAltitudeUnit,
        )
    }
}

/** Settings need failure reporting without changing background-service storage policy. */
fun LocationTrackingSettingsRepository.withFailureReporting(): LocationTrackingSettingsRepository =
    if (this is DefaultLocationTrackingSettingsRepository) reportingFailures() else this
