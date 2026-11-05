package app.logdate.client.domain.dayboundary

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.withFailureReporting
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default implementation of [DayBoundarySettingsRepository] using [KeyValueStorage].
 */
class DefaultDayBoundarySettingsRepository(
    private val keyValueStorage: KeyValueStorage,
) : DayBoundarySettingsRepository {
    internal fun reportingFailures(): DayBoundarySettingsRepository =
        DefaultDayBoundarySettingsRepository(keyValueStorage.withFailureReporting())

    companion object {
        private const val KEY_SLEEP_BASED_ENABLED = "day_boundary_sleep_based_enabled"
    }

    override suspend fun getSettings(): DayBoundarySettings =
        DayBoundarySettings(
            sleepBasedBoundariesEnabled = keyValueStorage.getBoolean(KEY_SLEEP_BASED_ENABLED, false),
        )

    override fun observeSettings(): Flow<DayBoundarySettings> =
        keyValueStorage
            .observeBoolean(KEY_SLEEP_BASED_ENABLED, false)
            .map { enabled ->
                DayBoundarySettings(sleepBasedBoundariesEnabled = enabled)
            }

    override suspend fun setSleepBasedBoundariesEnabled(enabled: Boolean) {
        Napier.i("Setting sleep-based boundaries enabled preference: $enabled")
        keyValueStorage.putBoolean(KEY_SLEEP_BASED_ENABLED, enabled)
    }
}

/** Settings need failure reporting without changing background-service storage policy. */
fun DayBoundarySettingsRepository.withFailureReporting(): DayBoundarySettingsRepository =
    if (this is DefaultDayBoundarySettingsRepository) reportingFailures() else this
