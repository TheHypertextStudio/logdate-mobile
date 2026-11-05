package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.device.identity.DefaultDeviceManager
import app.logdate.client.device.models.DevicePlatform
import androidx.lifecycle.viewModelScope
import app.logdate.client.sync.peer.devices.LinkedDeviceRemoval
import app.logdate.client.sync.peer.devices.LinkedDeviceSession
import app.logdate.client.sync.peer.devices.LinkedDevicesFailure
import app.logdate.client.sync.peer.devices.LinkedDevicesLoad
import app.logdate.client.sync.peer.devices.LinkedDevicesRepository
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import kotlinx.coroutines.cancel
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_fallback_name
import logdate.client.feature.core.generated.resources.devices_platform_android
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for [DevicesViewModel].
 *
 * Validates the device management interface, ensuring that associated devices
 * can be listed, renamed, or removed, and that the current device's identity
 * is correctly handled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    private lateinit var deviceIdProvider: FakeDeviceIdProvider
    private lateinit var repository: FakeDeviceRepository
    private lateinit var deviceManager: DefaultDeviceManager
    private val linked = FakeLinkedDevicesRepository()
    private lateinit var viewModel: DevicesViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val currentDeviceId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    private val device1Id = Uuid.parse("223e4567-e89b-12d3-a456-426614174000")
    private val device2Id = Uuid.parse("323e4567-e89b-12d3-a456-426614174000")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Setup device manager with a current device and two other devices
        deviceIdProvider = FakeDeviceIdProvider(currentDeviceId)
        repository = FakeDeviceRepository()
        deviceManager =
            DefaultDeviceManager(
                deviceIdProvider = deviceIdProvider,
                deviceRepository = repository,
                initialDeviceName = "Current Device",
                platform = DevicePlatform.ANDROID,
                appVersion = "1.0.0",
            )

        // Add some associated devices
        val device1 =
            deviceInfo(
                id = device1Id,
                name = "Device 1",
                platform = DevicePlatform.ANDROID,
            )
        val device2 =
            deviceInfo(
                id = device2Id,
                name = "Device 2",
                platform = DevicePlatform.IOS,
            )
        repository.addAssociatedDevice(device1)
        repository.addAssociatedDevice(device2)

        // Create view model
        viewModel = DevicesViewModel(deviceManager, linked)
    }

    @AfterTest
    fun tearDown() {
        viewModel.stopObserving()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state must not present unloaded devices as success`() =
        testScope.runTest {
            // When created
            val initialState = viewModel.uiState.first()

            // Then
            assertEquals(DeviceListAvailability.Loading, initialState.availability)
            assertTrue(initialState.devices.isEmpty(), "Initial state should have no devices")
            assertEquals(DeviceRefreshState.Idle, initialState.refresh)
        }

    @Test
    fun `loadDevices should load devices`() =
        testScope.runTest {
            // When
            viewModel.loadDevices()
            advanceUntilIdle()
            val state = viewModel.uiState.first()

            // Then
            assertEquals(DeviceListAvailability.Available, state.availability)
            assertEquals(3, state.devices.size, "Should load all 3 devices")
            assertEquals(DeviceRefreshState.Idle, state.refresh)
            assertEquals(currentDeviceId, state.devices.first().id, "This device should lead the list")

            // Verify current device is properly marked
            val currentDevice = assertNotNull(state.devices.find { it.id == currentDeviceId })
            assertTrue(currentDevice.isCurrentDevice, "Current device should be marked as current")
            assertEquals("Current Device".toUiText(), currentDevice.name, "Current device should have correct name")

            // Verify other devices are not marked as current
            state.devices
                .filter { it.id != currentDeviceId }
                .forEach { device ->
                    assertFalse(device.isCurrentDevice, "Other devices should not be marked as current")
                }
        }

    @Test
    fun `raw device identifiers are not shown as names`() =
        testScope.runTest {
            repository.addAssociatedDevice(
                deviceInfo(
                    id = Uuid.parse("423e4567-e89b-12d3-a456-426614174000"),
                    name = "sdk_gphone16k_arm64",
                    platform = DevicePlatform.ANDROID,
                ),
            )

            viewModel.loadDevices()
            advanceUntilIdle()

            val names =
                viewModel.uiState.value.devices
                    .map { it.name }
            assertTrue(Res.string.devices_fallback_name.toUiText(Res.string.devices_platform_android.toUiText()) in names)
            assertFalse("sdk_gphone16k_arm64".toUiText() in names)
        }

    @Test
    fun `closing the screen stops device observation without showing an error`() =
        testScope.runTest {
            viewModel.loadDevices()
            advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.devices.size)
            viewModel.stopObserving()
            advanceUntilIdle()
            repository.addAssociatedDevice(deviceInfo(Uuid.random(), "Another device", DevicePlatform.MACOS))
            advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.devices.size)
            assertEquals(DeviceRefreshState.Idle, viewModel.uiState.value.refresh)
        }

    @Test
    fun `renameDevice should update device name`() =
        testScope.runTest {
            // Given
            viewModel.loadDevices()
            advanceUntilIdle()
            val initialState = viewModel.uiState.first()
            val initialDeviceCount = initialState.devices.size

            // When
            val newName = "Renamed Device"
            viewModel.renameDevice(newName)
            advanceUntilIdle()
            val updatedState = viewModel.uiState.first()

            // Then
            assertEquals(initialDeviceCount, updatedState.devices.size, "Device count should not change")
            val currentDevice = updatedState.devices.find { it.isCurrentDevice }
            assertEquals(newName.toUiText(), currentDevice?.name, "Device should be renamed")
            assertEquals(1, repository.updateDeviceInfoCallCount, "Should update device info in repository")
        }

    @Test
    fun `removeDevice should remove the device`() =
        testScope.runTest {
            // Given
            viewModel.loadDevices()
            advanceUntilIdle()
            val initialState = viewModel.uiState.first()
            val initialDeviceCount = initialState.devices.size

            // When
            viewModel.removeDevice(device1Id)
            advanceUntilIdle()
            val updatedState = viewModel.uiState.first()

            // Then
            assertEquals(initialDeviceCount - 1, updatedState.devices.size, "One device should be removed")
            assertNull(updatedState.devices.find { it.id == device1Id }, "Device 1 should be removed")
            assertEquals(1, repository.removeCallCount, "Should call remove on the device repository")
            assertEquals(device1Id, repository.lastRemovedDeviceId, "Correct device ID should be removed")
        }

    @Test
    fun `resetDeviceId should refresh device ID`() =
        testScope.runTest {
            // Given
            viewModel.loadDevices()
            advanceUntilIdle()
            val initialDeviceId = deviceIdProvider.getDeviceId().value

            // When
            viewModel.resetDeviceId()
            advanceUntilIdle()

            // Then
            assertNotEquals(initialDeviceId, deviceIdProvider.getDeviceId().value, "Device ID should change")
            assertEquals(1, deviceIdProvider.refreshCallCount, "Should call refreshDeviceId on the provider")
        }

    @Test
    fun `error handling should work for loadDevices`() =
        testScope.runTest {
            // Given
            repository.shouldFailGetAssociatedDevices = true

            // When
            viewModel.loadDevices()
            advanceUntilIdle()
            val state = viewModel.uiState.first()

            // Then
            assertEquals(DeviceListAvailability.Unavailable, state.availability)
        }

    @Test
    fun `error handling should work for removeDevice`() =
        testScope.runTest {
            // Given
            viewModel.loadDevices()
            advanceUntilIdle()
            repository.shouldFailDeviceRemoval = true

            // When
            viewModel.removeDevice(device1Id)
            advanceUntilIdle()
            val state = viewModel.uiState.first()

            // Then
            assertEquals(DeviceOperationState.Failed(DeviceAction.Remove), state.operations[device1Id])
        }

    @Test
    fun `retry clears load failure immediately and success leaves no stale error`() =
        testScope.runTest {
            repository.shouldFailGetAssociatedDevices = true
            viewModel.loadDevices()
            advanceUntilIdle()
            assertEquals(DeviceListAvailability.Unavailable, viewModel.uiState.value.availability)

            repository.shouldFailGetAssociatedDevices = false
            viewModel.loadDevices()
            assertEquals(DeviceListAvailability.Loading, viewModel.uiState.value.availability)
            advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.devices.size)
            assertEquals(DeviceRefreshState.Idle, viewModel.uiState.value.refresh)
        }

    @Test
    fun `refresh keeps authoritative rows visible`() =
        testScope.runTest {
            viewModel.loadDevices()
            advanceUntilIdle()
            val rows = viewModel.uiState.value.devices
            repository.observationGate = CompletableDeferred()
            viewModel.loadDevices()
            runCurrent()
            assertEquals(rows, viewModel.uiState.value.devices)
            assertEquals(DeviceListAvailability.Available, viewModel.uiState.value.availability)
            assertEquals(DeviceRefreshState.Running, viewModel.uiState.value.refresh)
            repository.observationGate?.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `duplicate removal only issues one pending request`() =
        testScope.runTest {
            viewModel.loadDevices()
            advanceUntilIdle()
            repository.removalGate = CompletableDeferred()
            viewModel.removeDevice(device1Id)
            viewModel.removeDevice(device1Id)
            runCurrent()
            assertEquals(1, repository.removeCallCount)
            assertNotNull(
                viewModel.uiState.value.devices
                    .find { it.id == device1Id },
            )
            repository.removalGate?.complete(Unit)
            advanceUntilIdle()
            assertNull(
                viewModel.uiState.value.devices
                    .find { it.id == device1Id },
            )
        }

    @Test
    fun `cancelled removal leaves stable rows without failure`() =
        testScope.runTest {
            viewModel.loadDevices()
            advanceUntilIdle()
            repository.removalFailure = CancellationException("Cancelled")
            viewModel.removeDevice(device1Id)
            advanceUntilIdle()
            assertNotNull(
                viewModel.uiState.value.devices
                    .find { it.id == device1Id },
            )
            assertNull(viewModel.uiState.value.operations[device1Id])
        }

    @Test
    fun `manager rename rejection must not change local device name`() =
        testScope.runTest {
            repository.updateAccepted = false
            assertFailsWith<IllegalStateException> { deviceManager.renameDevice("Rejected name") }
            assertEquals("Current Device", deviceManager.getCurrentDeviceInfo().name)
        }

    @Test
    fun `manager rename propagates cancellation without changing local name`() =
        testScope.runTest {
            repository.updateFailure = CancellationException("Cancelled")
            assertFailsWith<CancellationException> { deviceManager.renameDevice("Cancelled name") }
            assertEquals("Current Device", deviceManager.getCurrentDeviceInfo().name)
        }

    @Test
    fun `manager reset reports failed registration while preserving actual new identity`() =
        testScope.runTest {
            repository.registrationAccepted = false
            assertFailsWith<IllegalStateException> { deviceManager.refreshDeviceId() }
            assertEquals(deviceIdProvider.getDeviceId().value, deviceManager.getCurrentDeviceInfo().id)
            assertNotEquals(currentDeviceId, deviceManager.getCurrentDeviceInfo().id)
        }

    @Test
    fun `manager reset propagates registration cancellation`() =
        testScope.runTest {
            repository.registrationFailure = CancellationException("Cancelled")
            assertFailsWith<CancellationException> { deviceManager.refreshDeviceId() }
            assertEquals(deviceIdProvider.getDeviceId().value, deviceManager.getCurrentDeviceInfo().id)
        }

    @Test
    fun `manager registration propagates cancellation`() =
        testScope.runTest {
            repository.registrationFailure = CancellationException("Cancelled")
            assertFailsWith<CancellationException> { deviceManager.registerWithCloud() }
        }
    @Test
    fun `linked devices stay hidden while linking is unavailable`() =
        testScope.runTest {
            linked.isAvailable = false

            viewModel.loadDevices()
            advanceUntilIdle()

            assertEquals(LinkedDevicesUiState.Unavailable, viewModel.uiState.value.linked)
            assertEquals(0, linked.loads)
        }

    @Test
    fun `linked devices are listed with their platform and times`() =
        testScope.runTest {
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))

            viewModel.loadDevices()
            advanceUntilIdle()

            assertEquals(LinkedDevicesUiState.Listed(listOf(linkedWatchUi)), viewModel.uiState.value.linked)
        }

    @Test
    fun `an account without linked devices lists none`() =
        testScope.runTest {
            linked.answers += LinkedDevicesLoad.Loaded(emptyList())

            viewModel.loadDevices()
            advanceUntilIdle()

            assertEquals(LinkedDevicesUiState.Listed(emptyList()), viewModel.uiState.value.linked)
        }

    @Test
    fun `linked devices show as loading until the server answers`() =
        testScope.runTest {
            val answer = CompletableDeferred<LinkedDevicesLoad>()
            linked.pending = answer

            viewModel.loadDevices()
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Loading, viewModel.uiState.value.linked)

            answer.complete(LinkedDevicesLoad.Loaded(listOf(linkedWatch)))
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Listed(listOf(linkedWatchUi)), viewModel.uiState.value.linked)
        }

    @Test
    fun `a failed load says why and can be tried again`() =
        testScope.runTest {
            linked.answers += LinkedDevicesLoad.Failed(LinkedDevicesFailure.Unreachable)
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))

            viewModel.loadDevices()
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Failed(LinkedDevicesFailure.Unreachable), viewModel.uiState.value.linked)

            viewModel.loadLinkedDevices()
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Listed(listOf(linkedWatchUi)), viewModel.uiState.value.linked)
        }

    @Test
    fun `removing a linked device lists the devices that remain`() =
        testScope.runTest {
            val removal = CompletableDeferred<LinkedDeviceRemoval>()
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))
            linked.answers += LinkedDevicesLoad.Loaded(emptyList())
            linked.removal = removal
            viewModel.loadDevices()
            advanceUntilIdle()

            viewModel.removeLinkedDevice(linkedWatch.id)
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Listed(listOf(linkedWatchUi), removingId = linkedWatch.id), viewModel.uiState.value.linked)

            removal.complete(LinkedDeviceRemoval.Removed)
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Listed(emptyList()), viewModel.uiState.value.linked)
            assertEquals(listOf(linkedWatch), linked.removed)
        }

    @Test
    fun `reloading the page while a removal runs lets the removal finish`() =
        testScope.runTest {
            val removal = CompletableDeferred<LinkedDeviceRemoval>()
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))
            linked.answers += LinkedDevicesLoad.Loaded(emptyList())
            linked.removal = removal
            viewModel.loadDevices()
            advanceUntilIdle()
            viewModel.removeLinkedDevice(linkedWatch.id)
            advanceUntilIdle()

            viewModel.loadDevices()
            viewModel.renameDevice("Kitchen phone")
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Listed(listOf(linkedWatchUi), removingId = linkedWatch.id), viewModel.uiState.value.linked)
            assertTrue(linked.removalActive)

            removal.complete(LinkedDeviceRemoval.Removed)
            advanceUntilIdle()
            assertEquals(LinkedDevicesUiState.Listed(emptyList()), viewModel.uiState.value.linked)
            assertTrue(linked.removalFinished)
        }

    @Test
    fun `a removal finishes even when the page closes`() =
        testScope.runTest {
            val removal = CompletableDeferred<LinkedDeviceRemoval>()
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))
            linked.removal = removal
            viewModel.loadDevices()
            advanceUntilIdle()
            viewModel.removeLinkedDevice(linkedWatch.id)
            advanceUntilIdle()

            viewModel.viewModelScope.cancel()
            removal.complete(LinkedDeviceRemoval.Removed)
            advanceUntilIdle()

            assertTrue(linked.removalFinished)
        }

    @Test
    fun `a watch removed while away says it erases its data when it connects`() =
        testScope.runTest {
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))
            linked.answers += LinkedDevicesLoad.Loaded(emptyList())
            linked.removal = CompletableDeferred(LinkedDeviceRemoval.RemovedWillWipeOnNextConnect)
            viewModel.loadDevices()
            advanceUntilIdle()

            viewModel.removeLinkedDevice(linkedWatch.id)
            advanceUntilIdle()

            assertEquals(
                LinkedDevicesUiState.Listed(emptyList(), notice = LinkedDeviceNotice.ErasesOnNextConnect("Pixel Watch 3")),
                viewModel.uiState.value.linked,
            )
            viewModel.dismissLinkedDeviceNotice()
            assertEquals(LinkedDevicesUiState.Listed(emptyList()), viewModel.uiState.value.linked)
        }

    @Test
    fun `a failed removal keeps the device and says why`() =
        testScope.runTest {
            val failure = LinkedDevicesFailure.WatchRemovalStopped("sync")
            linked.answers += LinkedDevicesLoad.Loaded(listOf(linkedWatch))
            linked.removal = CompletableDeferred(LinkedDeviceRemoval.Failed(failure))
            viewModel.loadDevices()
            advanceUntilIdle()

            viewModel.removeLinkedDevice(linkedWatch.id)
            advanceUntilIdle()

            assertEquals(
                LinkedDevicesUiState.Listed(
                    listOf(linkedWatchUi),
                    notice = LinkedDeviceNotice.RemoveFailed(linkedWatch.id, "Pixel Watch 3", failure),
                ),
                viewModel.uiState.value.linked,
            )
            assertEquals(1, linked.loads)
        }

    private val linkedWatch =
        LinkedDeviceSession(
            id = "watch-session",
            name = "Pixel Watch 3",
            platform = "wear_os",
            linkedAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
            lastSeenAt = Instant.fromEpochMilliseconds(1_760_600_000_000),
        )

    private val linkedWatchUi =
        LinkedDeviceUiState(
            id = "watch-session",
            name = "Pixel Watch 3".toUiText(),
            platformName = Res.string.devices_platform_wear_os,
            linkedAtFormatted = formatDeviceLastActive(linkedWatch.linkedAt)!!.toUiText(),
            lastSeenFormatted = formatDeviceLastActive(linkedWatch.lastSeenAt)!!.toUiText(),
            pairedWatch = false,
            erasesData = true,
        )

    private class FakeLinkedDevicesRepository : LinkedDevicesRepository {
        var isAvailable = true
        val answers = ArrayDeque<LinkedDevicesLoad>()
        var pending: CompletableDeferred<LinkedDevicesLoad>? = null
        var removal = CompletableDeferred<LinkedDeviceRemoval>(LinkedDeviceRemoval.Removed)
        val removed = mutableListOf<LinkedDeviceSession>()
        var loads = 0

        override suspend fun available(): Boolean = isAvailable

        override suspend fun load(): LinkedDevicesLoad {
            loads++
            pending?.let { return it.await() }
            return answers.removeFirstOrNull() ?: LinkedDevicesLoad.Unavailable
        }

        var removalActive = false
        var removalFinished = false

        override suspend fun remove(device: LinkedDeviceSession): LinkedDeviceRemoval {
            removed += device
            removalActive = true
            return removal.await().also {
                removalActive = false
                removalFinished = true
            }
        }
    }

}
