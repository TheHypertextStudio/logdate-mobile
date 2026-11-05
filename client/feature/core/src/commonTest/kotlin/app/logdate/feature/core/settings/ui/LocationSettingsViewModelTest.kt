package app.logdate.feature.core.settings.ui

import app.logdate.client.location.settings.DefaultLocation
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationSettingsViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial location settings are unavailable until storage emits`() =
        runTest {
            val repository = FakeLocationSettingsRepository(readGate = CompletableDeferred())
            val model = LocationSettingsViewModel(repository)

            assertIs<LocationSettingsState.Loading>(model.uiState.value)
            model.toggleBackgroundTracking(true)
            assertTrue(repository.backgroundWrites.isEmpty())
        }

    @Test
    fun `repeated background tracking requests share one pending write`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeLocationSettingsRepository().apply { beforeBackgroundWrite = { gate.await() } }
            val model = LocationSettingsViewModel(repository)

            model.toggleBackgroundTracking(true)
            model.toggleBackgroundTracking(true)

            assertEquals(listOf(true), repository.backgroundWrites)
            gate.complete(Unit)
        }

    @Test
    fun `a journal tracking change cannot overwrite a concurrent interval change`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeLocationSettingsRepository().apply { beforeSnapshotWrite = { gate.await() } }
            val model = LocationSettingsViewModel(repository)

            model.toggleJournalTracking(false)
            model.updateTrackingInterval(5)
            gate.complete(Unit)

            assertEquals(5, repository.settings.value.minimumPersistIntervalMinutes)
            assertFalse(repository.settings.value.autoTrackForJournalEntries)
        }

    @Test
    fun `one pending preference preserves its value and leaves unrelated mutations available`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeLocationSettingsRepository().apply { beforeBackgroundWrite = { gate.await() } }
            val model = LocationSettingsViewModel(repository)

            model.toggleBackgroundTracking(true)
            model.toggleTimelineTracking(false)

            assertFalse(model.available().settings.backgroundTrackingEnabled)
            assertFalse(model.available().settings.autoTrackForTimelineReview)
            assertIs<LocationSettingOperation.Saving>(model.available().operation(LocationSetting.BACKGROUND_TRACKING))
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.TIMELINE_TRACKING))
            gate.complete(Unit)
            assertTrue(model.available().settings.backgroundTrackingEnabled)
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.BACKGROUND_TRACKING))
        }

    @Test
    fun `failed preference saves retain authoritative values and retry only the failed target`() =
        runTest {
            val repository = FakeLocationSettingsRepository().apply { beforeBackgroundWrite = { error("private details") } }
            val model = LocationSettingsViewModel(repository)
            model.toggleBackgroundTracking(true)

            assertEquals(
                LocationSettingOperation.Failed(LocationSettingsChange.BackgroundTracking(true)),
                model.available().operation(LocationSetting.BACKGROUND_TRACKING),
            )
            assertFalse(model.available().settings.backgroundTrackingEnabled)
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.JOURNAL_TRACKING))

            val gate = CompletableDeferred<Unit>()
            repository.beforeBackgroundWrite = { gate.await() }
            model.retry(LocationSetting.BACKGROUND_TRACKING)
            assertIs<LocationSettingOperation.Saving>(model.available().operation(LocationSetting.BACKGROUND_TRACKING))
            gate.complete(Unit)
            assertTrue(model.available().settings.backgroundTrackingEnabled)
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.BACKGROUND_TRACKING))
        }

    @Test
    fun `cancelled mutations return quietly to their prior presentation`() =
        runTest {
            val repository =
                FakeLocationSettingsRepository().apply {
                    beforeBackgroundWrite = { throw CancellationException("cancelled") }
                }
            val model = LocationSettingsViewModel(repository)

            model.toggleBackgroundTracking(true)

            assertFalse(model.available().settings.backgroundTrackingEnabled)
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.BACKGROUND_TRACKING))
        }

    @Test
    fun `initial read failures can be retried without inventing a settings snapshot`() =
        runTest {
            val repository = FakeLocationSettingsRepository().apply { readFailure = true }
            val model = LocationSettingsViewModel(repository)
            assertIs<LocationSettingsState.Unavailable>(model.uiState.value)

            repository.readFailure = false
            model.reload()

            assertEquals(LocationTrackingSettings(), model.available().settings)
            assertEquals(2, repository.reads)
        }

    @Test
    fun `changes made from a child route arrive through the existing observation`() =
        runTest {
            val repository = FakeLocationSettingsRepository()
            val model = LocationSettingsViewModel(repository)

            repository.setTrackingInterval(60)

            assertEquals(60, model.available().settings.minimumPersistIntervalMinutes)
            assertEquals(1, repository.reads)
        }

    @Test
    fun `a failed observation retains usable values while reloading`() =
        runTest {
            val repository = FakeLocationSettingsRepository()
            val model = LocationSettingsViewModel(repository)
            repository.observation.value = Result.failure(IllegalStateException("private details"))
            assertTrue(model.available().refreshFailed)
            assertEquals(LocationTrackingSettings(), model.available().settings)

            val gate = CompletableDeferred<Unit>()
            repository.retryReadGate = gate
            model.reload()
            assertTrue(model.available().isRefreshing)
            assertFalse(model.available().refreshFailed)
            gate.complete(Unit)
            assertFalse(model.available().isRefreshing)
        }

    @Test
    fun `capture interval and developer actions persist their own fields`() =
        runTest {
            val repository = FakeLocationSettingsRepository()
            val model = LocationSettingsViewModel(repository)
            val fallback = DefaultLocation(36.1, -115.1, 600.0)

            model.setCaptureMode(LocationCaptureMode.ACTIVE)
            model.updateTrackingInterval(5)
            model.toggleJournalTracking(false)
            model.toggleTimelineTracking(false)
            model.toggleServerAssist(true)
            model.setDefaultLocation(fallback)
            assertEquals(
                LocationTrackingSettings(
                    captureMode = LocationCaptureMode.ACTIVE,
                    minimumPersistIntervalMinutes = 5,
                    autoTrackForJournalEntries = false,
                    autoTrackForTimelineReview = false,
                    serverAssistEnabled = true,
                    defaultLocation = fallback,
                ),
                model.available().settings,
            )
            model.setDefaultLocation(null)
            assertEquals(null, model.available().settings.defaultLocation)
        }

    @Test
    fun `default location retry retains the failed coordinates and leaves server assist independent`() =
        runTest {
            val repository = FakeLocationSettingsRepository().apply { beforeDefaultWrite = { error("private details") } }
            val model = LocationSettingsViewModel(repository)
            val fallback = DefaultLocation(36.1, -115.1, 600.0)
            model.setDefaultLocation(fallback)
            model.toggleServerAssist(true)
            assertEquals(
                LocationSettingOperation.Failed(LocationSettingsChange.DefaultFallback(fallback)),
                model.available().operation(LocationSetting.DEFAULT_LOCATION),
            )
            assertEquals(null, model.available().settings.defaultLocation)
            assertTrue(model.available().settings.serverAssistEnabled)

            repository.beforeDefaultWrite = {}
            model.retry(LocationSetting.DEFAULT_LOCATION)
            assertEquals(fallback, model.available().settings.defaultLocation)
        }

    @Test
    fun `a confirmed mutation reconciles its value after observation has failed`() =
        runTest {
            val repository = FakeLocationSettingsRepository()
            val model = LocationSettingsViewModel(repository)
            repository.observation.value = Result.failure(IllegalStateException("private details"))
            assertTrue(model.available().refreshFailed)

            model.toggleBackgroundTracking(true)

            assertTrue(model.available().settings.backgroundTrackingEnabled)
            assertTrue(model.available().refreshFailed)
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.BACKGROUND_TRACKING))
        }

    @Test
    fun `mutation completion preserves a newer externally observed snapshot`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeLocationSettingsRepository().apply { afterBackgroundWrite = { gate.await() } }
            val model = LocationSettingsViewModel(repository)
            model.toggleBackgroundTracking(true)
            repository.updateSettings(LocationTrackingSettings(backgroundTrackingEnabled = false, minimumPersistIntervalMinutes = 5))

            gate.complete(Unit)

            assertFalse(model.available().settings.backgroundTrackingEnabled)
            assertEquals(5, model.available().settings.minimumPersistIntervalMinutes)
            assertEquals(LocationSettingOperation.Idle, model.available().operation(LocationSetting.BACKGROUND_TRACKING))
        }

    private fun LocationSettingsViewModel.available(): LocationSettingsState.Available = assertIs(uiState.value)

    private class FakeLocationSettingsRepository(
        private val readGate: CompletableDeferred<Unit>? = null,
    ) : LocationTrackingSettingsRepository {
        val settings = MutableStateFlow(LocationTrackingSettings())
        val observation = MutableStateFlow(Result.success(settings.value))
        val backgroundWrites = mutableListOf<Boolean>()
        var beforeBackgroundWrite: suspend () -> Unit = {}
        var afterBackgroundWrite: suspend () -> Unit = {}
        var beforeSnapshotWrite: suspend () -> Unit = {}
        var beforeDefaultWrite: suspend () -> Unit = {}
        var readFailure = false
        var reads = 0
        var retryReadGate: CompletableDeferred<Unit>? = null

        override suspend fun getSettings(): LocationTrackingSettings = settings.value

        override fun observeSettings(): Flow<LocationTrackingSettings> =
            flow {
                reads++
                readGate?.await()
                retryReadGate?.await()
                if (readFailure) error("private storage details")
                observation.value = Result.success(settings.value)
                emitAll(observation.map { it.getOrThrow() })
            }

        override suspend fun updateSettings(settings: LocationTrackingSettings) {
            beforeSnapshotWrite()
            this.settings.value = settings
            observation.value = Result.success(settings)
        }

        override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
            backgroundWrites += enabled
            beforeBackgroundWrite()
            change { it.copy(backgroundTrackingEnabled = enabled) }
            afterBackgroundWrite()
        }

        override suspend fun setTrackingInterval(intervalMinutes: Long) =
            change { it.copy(minimumPersistIntervalMinutes = intervalMinutes) }

        override suspend fun setJournalTrackingEnabled(enabled: Boolean) = change { it.copy(autoTrackForJournalEntries = enabled) }

        override suspend fun setTimelineTrackingEnabled(enabled: Boolean) = change { it.copy(autoTrackForTimelineReview = enabled) }

        override suspend fun setCaptureMode(mode: LocationCaptureMode) = change { it.copy(captureMode = mode) }

        override suspend fun setServerAssistEnabled(enabled: Boolean) = change { it.copy(serverAssistEnabled = enabled) }

        override suspend fun setDefaultLocation(location: DefaultLocation?) {
            beforeDefaultWrite()
            change { it.copy(defaultLocation = location) }
        }

        private fun change(transform: (LocationTrackingSettings) -> LocationTrackingSettings) {
            settings.update(transform)
            observation.value = Result.success(settings.value)
        }
    }
}
