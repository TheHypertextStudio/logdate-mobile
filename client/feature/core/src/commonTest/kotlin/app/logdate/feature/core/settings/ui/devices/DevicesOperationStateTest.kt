package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.text.toUiText
import app.logdate.client.device.identity.DefaultDeviceManager
import app.logdate.client.device.models.DeviceInfo
import app.logdate.client.device.models.DevicePlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class DevicesOperationStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val currentId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    private val otherId = Uuid.parse("223e4567-e89b-12d3-a456-426614174000")
    private val secondId = Uuid.parse("323e4567-e89b-12d3-a456-426614174000")
    private lateinit var repository: FakeDeviceRepository
    private lateinit var idProvider: FakeDeviceIdProvider
    private lateinit var viewModel: DevicesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeDeviceRepository()
        repository.addAssociatedDevice(deviceInfo(otherId, "Other device", DevicePlatform.IOS))
        repository.addAssociatedDevice(deviceInfo(secondId, "Second device", DevicePlatform.WEB))
        idProvider = FakeDeviceIdProvider(currentId)
        viewModel = DevicesViewModel(DefaultDeviceManager(idProvider, repository, "This device", DevicePlatform.ANDROID, "1.0"), unavailableLinkedDevices)
        viewModel.loadDevices()
    }

    @AfterTest
    fun tearDown() {
        viewModel.stopObserving()
        Dispatchers.resetMain()
    }

    @Test
    fun `failed refresh retains available devices and retry removes only refresh failure`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            val rows = viewModel.uiState.value.devices
            repository.shouldFailDeviceRemoval = true
            viewModel.removeDevice(otherId)
            advanceUntilIdle()
            repository.shouldFailGetAssociatedDevices = true
            viewModel.loadDevices()
            advanceUntilIdle()
            assertEquals(DeviceListAvailability.Available, viewModel.uiState.value.availability)
            assertEquals(rows, viewModel.uiState.value.devices)
            assertEquals(DeviceRefreshState.Failed, viewModel.uiState.value.refresh)

            repository.shouldFailGetAssociatedDevices = false
            viewModel.loadDevices()
            assertEquals(DeviceRefreshState.Running, viewModel.uiState.value.refresh)
            assertEquals(DeviceOperationState.Failed(DeviceAction.Remove), viewModel.uiState.value.operations[otherId])
            advanceUntilIdle()
            assertEquals(DeviceRefreshState.Idle, viewModel.uiState.value.refresh)
        }

    @Test
    fun `removal retry clears its own error while another device remains failed`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.shouldFailDeviceRemoval = true
            viewModel.removeDevice(otherId)
            viewModel.removeDevice(secondId)
            advanceUntilIdle()
            repository.shouldFailDeviceRemoval = false
            repository.removalGate = CompletableDeferred()
            viewModel.removeDevice(otherId)
            assertEquals(DeviceOperationState.Running(DeviceAction.Remove), viewModel.uiState.value.operations[otherId])
            assertEquals(DeviceOperationState.Failed(DeviceAction.Remove), viewModel.uiState.value.operations[secondId])
            repository.removalGate?.complete(Unit)
            advanceUntilIdle()
            assertNull(
                viewModel.uiState.value.devices
                    .find { it.id == otherId },
            )
            assertEquals(DeviceOperationState.Succeeded(DeviceAction.Remove), viewModel.uiState.value.operations[otherId])
        }

    @Test
    fun `rename excludes another rename and identity reset but not another device action`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.updateGate = CompletableDeferred()
            viewModel.renameDevice("New name")
            viewModel.renameDevice("Duplicate name")
            viewModel.resetDeviceId()
            viewModel.removeDevice(otherId)
            runCurrent()
            assertEquals(DeviceOperationState.Running(DeviceAction.Rename), viewModel.uiState.value.operations[currentId])
            assertEquals(1, repository.updateDeviceInfoCallCount)
            assertEquals(0, idProvider.refreshCallCount)
            assertNull(
                viewModel.uiState.value.devices
                    .find { it.id == otherId },
            )
            assertEquals(
                "This device".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
            repository.updateGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "New name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
        }

    @Test
    fun `failed rename stays on current device and retry succeeds`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.updateAccepted = false
            viewModel.renameDevice("New name")
            advanceUntilIdle()
            assertEquals(DeviceOperationState.Failed(DeviceAction.Rename), viewModel.uiState.value.operations[currentId])
            assertEquals(
                "This device".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
            repository.updateAccepted = true
            viewModel.renameDevice("New name")
            assertEquals(DeviceOperationState.Running(DeviceAction.Rename), viewModel.uiState.value.operations[currentId])
            advanceUntilIdle()
            assertEquals(DeviceOperationState.Succeeded(DeviceAction.Rename), viewModel.uiState.value.operations[currentId])
            assertEquals(
                "New name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
        }

    @Test
    fun `false removal result is a scoped failure and retains the device`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.removalAccepted = false
            viewModel.removeDevice(otherId)
            advanceUntilIdle()
            assertEquals(DeviceOperationState.Failed(DeviceAction.Remove), viewModel.uiState.value.operations[otherId])
            assertNotNull(
                viewModel.uiState.value.devices
                    .find { it.id == otherId },
            )
            assertEquals(DeviceRefreshState.Idle, viewModel.uiState.value.refresh)
        }

    @Test
    fun `cancelled rename returns to idle and permits an explicit retry`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.updateFailure = CancellationException("Cancelled")
            viewModel.renameDevice("New name")
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.operations[currentId])
            assertEquals(
                "This device".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
            repository.updateFailure = null
            viewModel.renameDevice("New name")
            advanceUntilIdle()
            assertEquals(
                "New name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
        }

    @Test
    fun `reset registration failure retains actual identity and scopes failure to it`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.registrationAccepted = false
            viewModel.resetDeviceId()
            advanceUntilIdle()
            val current =
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
            assertNotEquals(currentId, current.id)
            assertEquals(idProvider.getDeviceId().value, current.id)
            assertEquals(DeviceOperationState.Failed(DeviceAction.ResetIdentity), viewModel.uiState.value.operations[current.id])
        }

    @Test
    fun `finishing a mutation after route departure does not restart observation`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repository.updateGate = CompletableDeferred()
            viewModel.renameDevice("New name")
            runCurrent()
            viewModel.stopObserving()
            val observations = repository.observationCallCount
            repository.updateGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(observations, repository.observationCallCount)
            assertFalse(viewModel.uiState.value.operations[currentId] is DeviceOperationState.Running)
            viewModel.loadDevices()
            advanceUntilIdle()
            assertEquals(
                "New name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
        }

    @Test
    fun `late emission from replaced observation cannot overwrite fresh devices`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            val expected = viewModel.uiState.value.devices
            val lateEmission = CompletableDeferred<Unit>()
            repository.observationOverride = {
                object : Flow<List<DeviceInfo>> {
                    override suspend fun collect(collector: FlowCollector<List<DeviceInfo>>) {
                        withContext(NonCancellable) {
                            lateEmission.await()
                            collector.emit(listOf(deviceInfo(Uuid.random(), "Stale device", DevicePlatform.WEB)))
                        }
                    }
                }
            }
            viewModel.loadDevices()
            runCurrent()
            repository.observationOverride = null
            viewModel.loadDevices()
            runCurrent()
            lateEmission.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                expected.map { it.id },
                viewModel.uiState.value.devices
                    .map { it.id },
            )
            assertEquals(DeviceRefreshState.Idle, viewModel.uiState.value.refresh)
        }

    @Test
    fun `refresh cannot replace a successful rename with an older server name`() =
        runTest(dispatcher) {
            repository.addAssociatedDevice(deviceInfo(currentId, "Older server name", DevicePlatform.ANDROID))
            advanceUntilIdle()
            assertEquals(
                "Older server name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
            viewModel.renameDevice("New name")
            advanceUntilIdle()
            assertEquals(
                "New name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
            repository.addAssociatedDevice(deviceInfo(Uuid.random(), "Another device", DevicePlatform.WEB))
            advanceUntilIdle()
            assertEquals(
                "New name".toUiText(),
                viewModel.uiState.value.devices
                    .first { it.isCurrentDevice }
                    .name,
            )
        }
}
