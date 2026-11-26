package app.logdate.wear.presentation.settings

import app.cash.turbine.test
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionType
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.SyncStatus
import app.logdate.wear.sync.PhoneReachability
import app.logdate.wear.sync.ReachablePhone
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests [WearSettingsViewModel]. The phone, the sync status and the location settings are followed as
 * they change; only the location permission is read again, on resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearSettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val phone = MutableStateFlow<ReachablePhone?>(null)
    private val status =
        MutableStateFlow(
            SyncStatus(isEnabled = false, lastSyncTime = null, pendingUploads = 0, isSyncing = false, hasErrors = false),
        )
    private val locationSettings = MutableStateFlow(LocationTrackingSettings())
    private lateinit var syncManager: SyncManager
    private lateinit var locationSettingsRepository: LocationTrackingSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var locationTrackingManager: LocationTrackingManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        syncManager = mockk(relaxed = true)
        locationSettingsRepository = mockk(relaxed = true)
        permissionManager = mockk(relaxed = true)
        locationTrackingManager = mockk(relaxed = true)
        every { syncManager.syncStatusFlow } returns status
        every { locationSettingsRepository.observeSettings() } returns locationSettings
        coEvery { locationSettingsRepository.setBackgroundTrackingEnabled(any()) } returns Unit
        coEvery { locationSettingsRepository.setCaptureMode(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(appVersion: String? = null): WearSettingsViewModel =
        WearSettingsViewModel(
            syncManager,
            PhoneReachability { phone },
            locationSettingsRepository,
            permissionManager,
            locationTrackingManager,
            appVersion,
        )

    @Test
    fun `initial state is disconnected with no sync info`() =
        runTest {
            createViewModel().uiState.test {
                val state = expectMostRecentItem()
                assertFalse(state.isPhoneConnected)
                assertNull(state.phoneName)
                assertNull(state.lastSyncTime)
                assertEquals(0, state.pendingCount)
                assertFalse(state.hasErrors)
                assertFalse(state.isSyncingNow)
                assertTrue(state.autoTagJournalEntries)
                assertFalse(state.backgroundLocationEnabled)
                assertEquals(LocationCaptureMode.PASSIVE, state.locationCaptureMode)
            }
        }

    @Test
    fun `initial state reflects location settings and foreground permission`() =
        runTest {
            locationSettings.value =
                LocationTrackingSettings(
                    backgroundTrackingEnabled = true,
                    captureMode = LocationCaptureMode.ACTIVE,
                    autoTrackForJournalEntries = true,
                )
            every { permissionManager.isPermissionGranted(PermissionType.LOCATION) } returns true

            createViewModel().uiState.test {
                val state = expectMostRecentItem()
                assertTrue(state.locationPermissionGranted)
                assertTrue(state.backgroundLocationEnabled)
                assertEquals(LocationCaptureMode.ACTIVE, state.locationCaptureMode)
                assertTrue(state.autoTagJournalEntries)
            }
        }

    @Test
    fun `the phone's name follows the phone coming and going`() =
        runTest {
            createViewModel().uiState.test {
                assertFalse(expectMostRecentItem().isPhoneConnected)

                phone.value = ReachablePhone("Pixel 9")
                val connected = awaitItem()
                assertTrue(connected.isPhoneConnected)
                assertEquals("Pixel 9", connected.phoneName)

                phone.value = null
                assertFalse(awaitItem().isPhoneConnected)
            }
        }

    @Test
    fun `waiting items, errors and the last sync follow the sync status`() =
        runTest {
            val lastSync = Instant.fromEpochMilliseconds(1_710_000_000_000)
            createViewModel().uiState.test {
                expectMostRecentItem()

                status.value = status.value.copy(lastSyncTime = lastSync, pendingUploads = 3, hasErrors = true)
                val state = awaitItem()

                assertEquals(lastSync, state.lastSyncTime)
                assertEquals(3, state.pendingCount)
                assertTrue(state.hasErrors)
            }
        }

    @Test
    fun `location settings follow the repository`() =
        runTest {
            createViewModel().uiState.test {
                expectMostRecentItem()

                locationSettings.value =
                    LocationTrackingSettings(
                        backgroundTrackingEnabled = true,
                        captureMode = LocationCaptureMode.ACTIVE,
                        autoTrackForJournalEntries = true,
                    )
                val state = awaitItem()

                assertTrue(state.backgroundLocationEnabled)
                assertEquals(LocationCaptureMode.ACTIVE, state.locationCaptureMode)
            }
        }

    @Test
    fun `the location permission is read again on resume`() =
        runTest {
            every { permissionManager.isPermissionGranted(PermissionType.LOCATION) } returns false
            val viewModel = createViewModel()

            viewModel.uiState.test {
                assertFalse(expectMostRecentItem().locationPermissionGranted)

                every { permissionManager.isPermissionGranted(PermissionType.LOCATION) } returns true
                viewModel.onResume()

                assertTrue(awaitItem().locationPermissionGranted)
            }
        }

    @Test
    fun `syncNow runs a full sync and shows it while it runs`() =
        runTest {
            val gate = CompletableDeferred<SyncResult>()
            coEvery { syncManager.fullSync() } coAnswers { gate.await() }
            val viewModel = createViewModel()

            viewModel.uiState.test {
                expectMostRecentItem()

                viewModel.syncNow()
                assertTrue(awaitItem().isSyncingNow)

                gate.complete(SyncResult(success = true))
                assertFalse(awaitItem().isSyncingNow)
            }
            coVerify(exactly = 1) { syncManager.fullSync() }
        }

    @Test
    fun `setBackgroundLocationEnabled persists the setting and reapplies tracking`() =
        runTest {
            createViewModel().setBackgroundLocationEnabled(true)

            coVerify { locationSettingsRepository.setBackgroundTrackingEnabled(true) }
            verify { locationTrackingManager.startTracking() }
        }

    @Test
    fun `setLocationCaptureMode persists the mode and reapplies tracking`() =
        runTest {
            createViewModel().setLocationCaptureMode(LocationCaptureMode.ACTIVE)

            coVerify { locationSettingsRepository.setCaptureMode(LocationCaptureMode.ACTIVE) }
            verify { locationTrackingManager.startTracking() }
        }

    @Test
    fun `openLocationPermissions opens app permission settings`() =
        runTest {
            createViewModel().openLocationPermissions()

            verify { permissionManager.openPermissionSettings() }
        }

    @Test
    fun `nothing is polled`() =
        runTest {
            createViewModel().uiState.test { expectMostRecentItem() }

            coVerify(exactly = 0) { syncManager.getSyncStatus() }
            coVerify(exactly = 0) { locationSettingsRepository.getSettings() }
        }

    @Test
    fun `app version is surfaced in ui state`() =
        runTest {
            createViewModel(appVersion = "0.2.0").uiState.test {
                assertEquals("0.2.0", expectMostRecentItem().appVersion)
            }
        }
}
