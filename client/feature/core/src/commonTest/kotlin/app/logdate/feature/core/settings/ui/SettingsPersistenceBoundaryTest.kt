package app.logdate.feature.core.settings.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.datastore.DataStoreKeyValueStorage
import app.logdate.client.domain.dayboundary.DefaultDayBoundarySettingsRepository
import app.logdate.client.location.settings.DefaultLocationTrackingSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import app.logdate.client.domain.dayboundary.withFailureReporting as dayBoundaryFailureReporting
import app.logdate.client.location.settings.withFailureReporting as locationFailureReporting

/** Tests the real persistence adapter, not a fake repository that already throws correctly. */
class SettingsPersistenceBoundaryTest {
    @Test
    fun `day schedule write failure reaches the caller`() =
        runTest {
            val repository =
                DefaultDayBoundarySettingsRepository(
                    DataStoreKeyValueStorage(FailingPreferences()),
                ).dayBoundaryFailureReporting()
            assertFailsWith<IllegalStateException> { repository.setSleepBasedBoundariesEnabled(true) }
        }

    @Test
    fun `day schedule read failure never becomes a saved false`() =
        runTest {
            val repository =
                DefaultDayBoundarySettingsRepository(
                    DataStoreKeyValueStorage(FailingPreferences(readFails = true)),
                ).dayBoundaryFailureReporting()
            assertFailsWith<IllegalStateException> { repository.observeSettings().first() }
        }

    @Test
    fun `location write failure reaches the caller`() =
        runTest {
            val repository =
                DefaultLocationTrackingSettingsRepository(
                    DataStoreKeyValueStorage(FailingPreferences()),
                ).locationFailureReporting()
            assertFailsWith<IllegalStateException> { repository.setTrackingInterval(5) }
        }

    @Test
    fun `location read failure never becomes fabricated default preferences`() =
        runTest {
            val repository =
                DefaultLocationTrackingSettingsRepository(
                    DataStoreKeyValueStorage(FailingPreferences(readFails = true)),
                ).locationFailureReporting()
            assertFailsWith<IllegalStateException> { repository.observeSettings().first() }
        }

    private class FailingPreferences(
        private val readFails: Boolean = false,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> =
            flow {
                if (readFails) error("Storage read unavailable")
                emit(emptyPreferences())
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("Storage write unavailable")
    }
}
