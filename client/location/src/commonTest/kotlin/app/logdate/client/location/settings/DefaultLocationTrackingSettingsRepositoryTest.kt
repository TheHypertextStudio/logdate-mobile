package app.logdate.client.location.settings

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DefaultLocationTrackingSettingsRepository], which manages the user's
 * configuration for background location tracking and data capture.
 *
 * These tests verify that settings are correctly persisted, that invalid values
 * are safely clamped to defaults, and that legacy configuration values are
 * correctly migrated to the current schema.
 */
class DefaultLocationTrackingSettingsRepositoryTest {
    @Test
    fun `journal tracking changes preserve an interval changed during the save`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val repository = DefaultLocationTrackingSettingsRepository(storage)
            storage.beforeBooleanWrite = { repository.setTrackingInterval(5) }

            repository.setJournalTrackingEnabled(false)

            assertEquals(5, repository.getSettings().minimumPersistIntervalMinutes)
            assertFalse(repository.getSettings().autoTrackForJournalEntries)
            assertTrue(repository.getSettings().autoTrackForTimelineReview)
        }

    @Test
    fun `timeline tracking changes preserve a capture mode changed during the save`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val repository = DefaultLocationTrackingSettingsRepository(storage)
            storage.beforeBooleanWrite = { repository.setCaptureMode(LocationCaptureMode.ACTIVE) }

            repository.setTimelineTrackingEnabled(false)

            assertEquals(LocationCaptureMode.ACTIVE, repository.getSettings().captureMode)
            assertTrue(repository.getSettings().autoTrackForJournalEntries)
            assertFalse(repository.getSettings().autoTrackForTimelineReview)
        }

    @Test
    fun `getSettings clamps interval and falls back on unknown capture mode`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            storage.putLong("location_tracking_interval_minutes", 1)
            storage.putString("location_capture_mode", "NOT_A_REAL_MODE")

            val repository = DefaultLocationTrackingSettingsRepository(storage)

            val settings = repository.getSettings()

            assertEquals(2, settings.minimumPersistIntervalMinutes)
            assertEquals(LocationCaptureMode.PASSIVE, settings.captureMode)
            assertFalse(settings.backgroundTrackingEnabled)
        }

    @Test
    fun `updateSettings persists active mode values`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val repository = DefaultLocationTrackingSettingsRepository(storage)

            repository.updateSettings(
                LocationTrackingSettings(
                    backgroundTrackingEnabled = true,
                    minimumPersistIntervalMinutes = 5,
                    captureMode = LocationCaptureMode.ACTIVE,
                    serverAssistEnabled = true,
                    autoTrackForJournalEntries = false,
                    autoTrackForTimelineReview = false,
                ),
            )

            val settings = repository.getSettings()

            assertTrue(settings.backgroundTrackingEnabled)
            assertEquals(5, settings.minimumPersistIntervalMinutes)
            assertEquals(LocationCaptureMode.ACTIVE, settings.captureMode)
            assertTrue(settings.serverAssistEnabled)
            assertFalse(settings.autoTrackForJournalEntries)
            assertFalse(settings.autoTrackForTimelineReview)
        }

    @Test
    fun `default location is absent until configured`() =
        runTest {
            val repository = DefaultLocationTrackingSettingsRepository(InMemoryKeyValueStorage())

            val settings = repository.getSettings()

            assertEquals(null, settings.defaultLocation)
        }

    @Test
    fun `setDefaultLocation persists fallback coordinates`() =
        runTest {
            val repository = DefaultLocationTrackingSettingsRepository(InMemoryKeyValueStorage())
            val location =
                DefaultLocation.fromLocation(
                    Location(
                        latitude = 40.7128,
                        longitude = -74.0060,
                        altitude = LocationAltitude(33.0, AltitudeUnit.FEET),
                    ),
                )

            repository.setDefaultLocation(location)

            assertEquals(location, repository.getSettings().defaultLocation)
        }

    @Test
    fun `setDefaultLocation clears fallback coordinates`() =
        runTest {
            val repository = DefaultLocationTrackingSettingsRepository(InMemoryKeyValueStorage())
            repository.setDefaultLocation(
                DefaultLocation.fromLocation(
                    Location(
                        latitude = 40.7128,
                        longitude = -74.0060,
                        altitude = LocationAltitude(0.0, AltitudeUnit.METERS),
                    ),
                ),
            )

            repository.setDefaultLocation(null)

            assertEquals(null, repository.getSettings().defaultLocation)
        }

    @Test
    fun `old STABLE value migrates to PASSIVE`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            storage.putString("location_capture_mode", "STABLE")

            val repository = DefaultLocationTrackingSettingsRepository(storage)
            val settings = repository.getSettings()

            assertEquals(LocationCaptureMode.PASSIVE, settings.captureMode)
        }

    @Test
    fun `old EXPERIMENT_MIRRORED value migrates to ACTIVE`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            storage.putString("location_capture_mode", "EXPERIMENT_MIRRORED")

            val repository = DefaultLocationTrackingSettingsRepository(storage)
            val settings = repository.getSettings()

            assertEquals(LocationCaptureMode.ACTIVE, settings.captureMode)
        }
}

private class InMemoryKeyValueStorage : KeyValueStorage {
    var beforeBooleanWrite: suspend () -> Unit = {}
    private val values = mutableMapOf<String, Any?>()
    private val state = MutableStateFlow<Map<String, Any?>>(emptyMap())

    override suspend fun getString(key: String): String? = values[key] as? String

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        values[key] = value
        state.value = values.toMap()
    }

    override suspend fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defaultValue

    override suspend fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        beforeBooleanWrite()
        values[key] = value
        state.value = values.toMap()
    }

    override suspend fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = values[key] as? Int ?: defaultValue

    override suspend fun putInt(
        key: String,
        value: Int,
    ) {
        values[key] = value
        state.value = values.toMap()
    }

    override suspend fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = values[key] as? Long ?: defaultValue

    override suspend fun putLong(
        key: String,
        value: Long,
    ) {
        values[key] = value
        state.value = values.toMap()
    }

    override suspend fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = values[key] as? Float ?: defaultValue

    override suspend fun putFloat(
        key: String,
        value: Float,
    ) {
        values[key] = value
        state.value = values.toMap()
    }

    override suspend fun remove(key: String) {
        values.remove(key)
        state.value = values.toMap()
    }

    override suspend fun contains(key: String): Boolean = values.containsKey(key)

    override suspend fun clear() {
        values.clear()
        state.value = emptyMap()
    }

    override fun observeString(key: String): Flow<String?> = state.map { it[key] as? String }

    override fun observeBoolean(
        key: String,
        defaultValue: Boolean,
    ): Flow<Boolean> = state.map { it[key] as? Boolean ?: defaultValue }

    override fun observeInt(
        key: String,
        defaultValue: Int,
    ): Flow<Int> = state.map { it[key] as? Int ?: defaultValue }

    override fun observeLong(
        key: String,
        defaultValue: Long,
    ): Flow<Long> = state.map { it[key] as? Long ?: defaultValue }

    override fun observeFloat(
        key: String,
        defaultValue: Float,
    ): Flow<Float> = state.map { it[key] as? Float ?: defaultValue }
}
