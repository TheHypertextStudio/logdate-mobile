package app.logdate.feature.core.settings.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.dayboundary.DayBoundarySettings
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.health.HealthDataAvailability
import app.logdate.client.health.LocalFirstHealthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DayScheduleViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun `timeline does not show invented day boundary values`() =
        runTest {
            val model = TimelineSettingsViewModel(repository(), LogdatePreferencesDataSource(DelayedStore()), healthStatus())
            assertNull(model.uiState.value.dayBoundarySettings)
        }

    @Test fun `day boundary does not show invented persisted values`() =
        runTest {
            val model = DayBoundarySettingsViewModel(repository(), LogdatePreferencesDataSource(DelayedStore()), healthStatus())
            assertNull(model.uiState.value.dayBoundarySettings)
            assertNull(model.uiState.value.fallbackStartHour)
        }

    @Test fun `unknown fallback time cannot overwrite the saved choice`() =
        runTest {
            val store = DelayedStore()
            val model = DayBoundarySettingsViewModel(repository(), LogdatePreferencesDataSource(store), healthStatus())
            model.setFallbackStartHour(8)
            assertEquals(0, store.writes)
        }

    @Test fun `returning to timeline refreshes finite health status without reloading preferences`() =
        runTest {
            var granted = false
            var checks = 0
            val health =
                Proxy.newProxyInstance(
                    LocalFirstHealthRepository::class.java.classLoader,
                    arrayOf(LocalFirstHealthRepository::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getHealthDataAvailability" -> {
                            checks++
                            HealthDataAvailability.AVAILABLE
                        }
                        "hasSleepPermissions" -> granted
                        "toString" -> "Mutable test health provider"
                        else -> error("Unexpected health operation: ${method.name}")
                    }
                } as LocalFirstHealthRepository
            val model =
                TimelineSettingsViewModel(
                    repository(),
                    LogdatePreferencesDataSource(DelayedStore()),
                    ObserveHealthConnectStatusUseCase(health),
                )
            model.onScreenResumed()
            assertEquals(1, checks)
            granted = true
            model.onScreenResumed()
            assertEquals(app.logdate.client.domain.dayboundary.HealthConnectStatus.CONNECTED, model.uiState.value.healthConnectStatus)
            assertEquals(2, checks)
            assertNull(model.uiState.value.dayBoundarySettings)
        }

    private fun repository() =
        object : DayBoundarySettingsRepository {
            override suspend fun getSettings() = DayBoundarySettings()

            override fun observeSettings(): Flow<DayBoundarySettings> = emptyFlow()

            override suspend fun setSleepBasedBoundariesEnabled(enabled: Boolean) = Unit
        }

    private fun healthStatus(): ObserveHealthConnectStatusUseCase {
        val health =
            Proxy.newProxyInstance(
                LocalFirstHealthRepository::class.java.classLoader,
                arrayOf(LocalFirstHealthRepository::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getHealthDataAvailability" -> HealthDataAvailability.NOT_AVAILABLE
                    "toString" -> "Unavailable test health provider"
                    else -> error("Unexpected health operation: ${method.name}")
                }
            } as LocalFirstHealthRepository
        return ObserveHealthConnectStatusUseCase(health)
    }

    private class DelayedStore : DataStore<Preferences> {
        var writes = 0
        override val data: Flow<Preferences> = emptyFlow()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes++
            return transform(emptyPreferences())
        }
    }
}
