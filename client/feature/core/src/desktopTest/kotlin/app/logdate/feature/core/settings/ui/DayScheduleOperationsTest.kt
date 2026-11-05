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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DayScheduleOperationsTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun `one preference saving leaves the other setting available`() =
        runTest {
            val repository = Repository()
            val pending = CompletableDeferred<Unit>()
            repository.write = { pending.await() }
            val model = model(repository)
            model.toggleSleepBasedBoundaries(true)
            model.toggleSleepBasedBoundaries(true)
            assertEquals(1, repository.writes)
            assertEquals(
                false,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
            assertIs<DaySettingWriteState.Saving<Boolean>>(model.uiState.value.sleepWrite)
            model.setFallbackStartHour(8)
            assertEquals(8, model.uiState.value.fallbackStartHour)
            assertEquals(DaySettingWriteState.Idle, model.uiState.value.fallbackWrite)
            assertIs<DaySettingWriteState.Saving<Boolean>>(model.uiState.value.sleepWrite)
            pending.complete(Unit)
            assertEquals(
                true,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
            assertEquals(DaySettingWriteState.Idle, model.uiState.value.sleepWrite)
        }

    @Test fun `failed fallback persistence retains the real value and retry target`() =
        runTest {
            val store = Store().apply { fail = true }
            val model = model(store = store)
            model.setFallbackStartHour(9)
            assertEquals(4, model.uiState.value.fallbackStartHour)
            assertEquals(DaySettingWriteState.Failed(9), model.uiState.value.fallbackWrite)
            assertEquals(DaySettingWriteState.Idle, model.uiState.value.sleepWrite)
            store.fail = false
            model.retryFallbackWrite()
            assertEquals(9, model.uiState.value.fallbackStartHour)
            assertEquals(DaySettingWriteState.Idle, model.uiState.value.fallbackWrite)
            assertEquals(2, store.writes)
        }

    @Test fun `cancelling a write leaves its setting unchanged without an error`() =
        runTest {
            val repository = Repository().apply { write = { throw CancellationException("Ordinary cancellation") } }
            val model = model(repository)
            model.toggleSleepBasedBoundaries(true)
            assertEquals(
                false,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
            assertEquals(DaySettingWriteState.Idle, model.uiState.value.sleepWrite)
        }

    @Test fun `an existing permission grant never changes the saved preference`() =
        runTest {
            val repository = Repository()
            val model = model(repository)
            model.onPermissionStatus(true, false)
            assertEquals(0, repository.writes)
            assertEquals(
                false,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
        }

    @Test fun `a new permission grant waits for the real preferences before enabling`() =
        runTest {
            val repository = Repository()
            val store = Store(initiallyLoaded = false)
            val model = model(repository, store)
            model.onPermissionStatus(false, false)
            model.onPermissionRequestStarted()
            model.onPermissionStatus(true, true)
            model.onPermissionResult(true)
            assertEquals(0, repository.writes)
            store.emit()
            assertEquals(1, repository.writes)
            assertEquals(
                true,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
        }

    @Test fun `read failure retains the loaded values and a successful retry clears it`() =
        runTest {
            val repository = Repository()
            val model = model(repository)
            repository.values.value = Result.failure(IllegalStateException("Read unavailable"))
            assertIs<DaySchedulePreferencesState.Unavailable>(model.uiState.value.preferences)
            assertEquals(4, model.uiState.value.fallbackStartHour)
            repository.values.value = Result.success(DayBoundarySettings(sleepBasedBoundariesEnabled = true))
            model.retryPreferences()
            assertIs<DaySchedulePreferencesState.Available>(model.uiState.value.preferences)
            assertEquals(
                true,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
        }

    @Test fun `initial false followed by an existing grant never enables a disabled preference`() =
        runTest {
            val repository = Repository()
            val model = model(repository)
            model.onPermissionStatus(false, false)
            model.onPermissionStatus(true, false)
            assertEquals(0, repository.writes)
            assertEquals(
                false,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
        }

    @Test fun `confirmed writes update visible values after the observer has failed`() =
        runTest {
            val repository = Repository()
            val model = model(repository)
            repository.values.value = Result.failure(IllegalStateException("Read unavailable"))
            model.toggleSleepBasedBoundaries(true)
            assertEquals(
                true,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
            model.setFallbackStartHour(9)
            assertEquals(9, model.uiState.value.fallbackStartHour)
        }

    @Test fun `an explicit disabled choice retires a pending permission request`() =
        runTest {
            val repository = Repository()
            val model = model(repository)
            model.onPermissionRequestStarted()
            model.toggleSleepBasedBoundaries(false)
            model.onPermissionResult(true)
            model.onPermissionStatus(true, true)
            assertEquals(0, repository.writes)
            assertEquals(
                false,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
        }

    @Test fun `immediate permission denial cannot arm a later unrelated grant`() =
        runTest {
            val repository = Repository()
            val model = model(repository)
            model.onPermissionStatus(false, false)
            model.onPermissionRequestStarted()
            model.onPermissionResult(false)
            model.onPermissionStatus(false, requested = true)
            model.onPermissionRequestStarted()
            model.onPermissionResult(false)
            model.onPermissionStatus(true, requested = true)
            assertEquals(0, repository.writes)
            assertEquals(
                false,
                model.uiState.value.dayBoundarySettings
                    ?.sleepBasedBoundariesEnabled,
            )
            model.onPermissionRequestStarted()
            model.onPermissionResult(true)
            assertEquals(1, repository.writes)
        }

    private fun model(
        repository: Repository = Repository(),
        store: Store = Store(),
    ): DayBoundarySettingsViewModel {
        val health =
            Proxy.newProxyInstance(
                LocalFirstHealthRepository::class.java.classLoader,
                arrayOf(LocalFirstHealthRepository::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getHealthDataAvailability" -> HealthDataAvailability.NOT_AVAILABLE
                    "toString" -> "Test health provider"
                    else -> error("Unexpected health operation: ${method.name}")
                }
            } as LocalFirstHealthRepository
        return DayBoundarySettingsViewModel(repository, LogdatePreferencesDataSource(store), ObserveHealthConnectStatusUseCase(health))
    }

    private class Repository : DayBoundarySettingsRepository {
        val values = MutableStateFlow(Result.success(DayBoundarySettings()))
        var writes = 0
        var write: suspend (Boolean) -> Unit = {}

        override suspend fun getSettings() = values.value.getOrThrow()

        override fun observeSettings() = values.map { it.getOrThrow() }

        override suspend fun setSleepBasedBoundariesEnabled(enabled: Boolean) {
            writes++
            write(enabled)
            values.value = Result.success(DayBoundarySettings(sleepBasedBoundariesEnabled = enabled))
        }
    }

    private class Store(
        initiallyLoaded: Boolean = true,
    ) : DataStore<Preferences> {
        private val values = MutableSharedFlow<Preferences>(replay = 1).apply { if (initiallyLoaded) tryEmit(emptyPreferences()) }
        private var current: Preferences = emptyPreferences()
        var fail = false
        var writes = 0
        override val data: Flow<Preferences> = values

        suspend fun emit() {
            values.emit(current)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes++
            if (fail) error("Write unavailable")
            current = transform(current)
            values.emit(current)
            return current
        }
    }
}
