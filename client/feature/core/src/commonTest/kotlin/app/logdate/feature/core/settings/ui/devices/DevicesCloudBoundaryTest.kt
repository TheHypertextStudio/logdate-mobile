package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.datastore.UserSession
import app.logdate.client.text.toUiText
import app.logdate.client.device.identity.DefaultDeviceManager
import app.logdate.client.device.identity.data.AccountDeviceApi
import app.logdate.client.device.identity.data.CloudDeviceRepository
import app.logdate.client.device.models.DevicePlatform
import app.logdate.client.device.storage.SecureSessionStorage
import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.config.DefaultLogDateConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesCloudBoundaryTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<DevicesViewModel>()
    private val origin = "https://journal.example.com"
    private val currentId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    private val otherId = Uuid.parse("223e4567-e89b-12d3-a456-426614174000")
    private val json =
        """
        [{
            "id": "$otherId",
            "name": "Other device",
            "platform": "MACOS",
            "appVersion": "1.0",
            "createdAt": 1000,
            "lastActive": 2000
        }]
        """.trimIndent()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        models.forEach { it.stopObserving() }
        Dispatchers.resetMain()
    }

    @Test
    fun `production adapter preserves initial unknown then shows HTTP failure`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            client { request ->
                if (request.method == HttpMethod.Put) {
                    respond("{}")
                } else {
                    release.await()
                    respond("", HttpStatusCode.ServiceUnavailable)
                }
            }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(DeviceListAvailability.Loading, fixture.model.uiState.value.availability)
                assertTrue(
                    fixture.model.uiState.value.devices
                        .isEmpty(),
                )
                release.complete(Unit)
                runCurrent()
                assertEquals(DeviceListAvailability.Unavailable, fixture.model.uiState.value.availability)
                fixture.model.stopObserving()
            }
        }

    @Test
    fun `real empty list is available and still includes this device`() =
        runTest(dispatcher) {
            client { request -> if (request.method == HttpMethod.Put) respond("{}") else respond("[]") }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(DeviceListAvailability.Available, fixture.model.uiState.value.availability)
                assertEquals(
                    listOf(currentId),
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                fixture.model.stopObserving()
            }
        }

    @Test
    fun `cached production rows remain refreshing until failure and can be retried`() =
        runTest(dispatcher) {
            var fail = false
            var release: CompletableDeferred<Unit>? = null
            client { request ->
                if (request.method == HttpMethod.Put) {
                    respond("{}")
                } else {
                    release?.await()
                    if (fail) respond("", HttpStatusCode.ServiceUnavailable) else respond(json)
                }
            }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                val rows = fixture.model.uiState.value.devices
                assertEquals(2, rows.size)
                fail = true
                release = CompletableDeferred()
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(
                    rows.map { it.id },
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                assertEquals(DeviceRefreshState.Running, fixture.model.uiState.value.refresh)
                release?.complete(Unit)
                runCurrent()
                assertEquals(DeviceListAvailability.Available, fixture.model.uiState.value.availability)
                assertEquals(DeviceRefreshState.Failed, fixture.model.uiState.value.refresh)
                assertEquals(
                    rows.map { it.id },
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                fail = false
                val retryRelease = CompletableDeferred<Unit>()
                release = retryRelease
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(DeviceRefreshState.Running, fixture.model.uiState.value.refresh)
                assertEquals(DeviceListAvailability.Available, fixture.model.uiState.value.availability)
                assertEquals(
                    rows.map { it.id },
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                retryRelease.complete(Unit)
                runCurrent()
                assertEquals(DeviceRefreshState.Idle, fixture.model.uiState.value.refresh)
                assertEquals(
                    rows.map { it.id },
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                fixture.model.stopObserving()
            }
        }

    @Test
    fun `production account switch clears retired rows before the new fetch finishes`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            client { request ->
                if (request.method == HttpMethod.Put) {
                    respond("{}")
                } else if (request.headers["Authorization"] == "Bearer access-a") {
                    respond(json)
                } else {
                    release.await()
                    respond("", HttpStatusCode.ServiceUnavailable)
                }
            }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                assertTrue(
                    fixture.model.uiState.value.devices
                        .any { it.id == otherId },
                )
                fixture.sessions.saveSession(UserSession("access-b", "refresh-b", "account-b"))
                runCurrent()
                assertFalse(
                    fixture.model.uiState.value.devices
                        .any { it.id == otherId },
                )
                assertEquals(DeviceListAvailability.Loading, fixture.model.uiState.value.availability)
                release.complete(Unit)
                runCurrent()
                assertEquals(DeviceListAvailability.Unavailable, fixture.model.uiState.value.availability)
                fixture.model.stopObserving()
            }
        }

    @Test
    fun `same account token rotation retains rows while the real adapter revalidates`() =
        runTest(dispatcher) {
            var release: CompletableDeferred<Unit>? = null
            client { request ->
                if (request.method == HttpMethod.Put) {
                    respond("{}")
                } else {
                    release?.await()
                    respond(json)
                }
            }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                val rows =
                    fixture.model.uiState.value.devices
                        .map { it.id }
                release = CompletableDeferred()
                fixture.sessions.saveSession(UserSession("rotated-access", "refresh-a", "account-a"))
                runCurrent()
                assertEquals(
                    rows,
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                assertEquals(DeviceRefreshState.Running, fixture.model.uiState.value.refresh)
                release?.complete(Unit)
                runCurrent()
                assertEquals(DeviceRefreshState.Idle, fixture.model.uiState.value.refresh)
                fixture.model.stopObserving()
            }
        }

    @Test
    fun `stopping a cached production refresh is quiet and rejects its late result`() =
        runTest(dispatcher) {
            var release: CompletableDeferred<Unit>? = null
            client { request ->
                if (request.method == HttpMethod.Put) {
                    respond("{}")
                } else {
                    release?.await()
                    if (release == null) respond(json) else respond("", HttpStatusCode.ServiceUnavailable)
                }
            }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                val rows =
                    fixture.model.uiState.value.devices
                        .map { it.id }
                release = CompletableDeferred()
                fixture.model.loadDevices()
                runCurrent()
                fixture.model.stopObserving()
                release?.complete(Unit)
                runCurrent()
                assertEquals(
                    rows,
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                assertEquals(DeviceRefreshState.Idle, fixture.model.uiState.value.refresh)
            }
        }

    @Test
    fun `stop and resume replaces the real observation without stale completion or duplicate polling`() =
        runTest(dispatcher) {
            val oldRequest = CompletableDeferred<Unit>()
            val resumedRequest = CompletableDeferred<Unit>()
            var lists = 0
            val freshJson = json.replace("Other device", "Fresh device")
            client { request ->
                if (request.method == HttpMethod.Put) {
                    respond("{}")
                } else {
                    when (++lists) {
                        1 -> respond(json)
                        2 -> {
                            oldRequest.await()
                            respond("", HttpStatusCode.ServiceUnavailable)
                        }
                        else -> {
                            resumedRequest.await()
                            respond(freshJson)
                        }
                    }
                }
            }.use { client ->
                val fixture = fixture(client)
                fixture.model.loadDevices()
                runCurrent()
                val rows =
                    fixture.model.uiState.value.devices
                        .map { it.id }
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(2, lists)
                fixture.model.stopObserving()
                assertEquals(DeviceRefreshState.Idle, fixture.model.uiState.value.refresh)
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(3, lists)
                assertEquals(
                    rows,
                    fixture.model.uiState.value.devices
                        .map { it.id },
                )
                assertEquals(DeviceRefreshState.Running, fixture.model.uiState.value.refresh)
                oldRequest.complete(Unit)
                runCurrent()
                assertEquals(DeviceRefreshState.Running, fixture.model.uiState.value.refresh)
                resumedRequest.complete(Unit)
                runCurrent()
                assertEquals(DeviceRefreshState.Idle, fixture.model.uiState.value.refresh)
                assertEquals(
                    "Fresh device".toUiText(),
                    fixture.model.uiState.value.devices
                        .first { it.id == otherId }
                        .name,
                )
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(4, lists)
                fixture.model.stopObserving()
            }
        }

    @Test
    fun `retired real repository rename cannot change the new account row or operations`() =
        runTest(dispatcher) {
            val releaseRename = CompletableDeferred<Unit>()
            var delayRegistration = false
            client { request ->
                if (request.method == HttpMethod.Put) {
                    if (delayRegistration) releaseRename.await()
                    respond("{}")
                } else {
                    respond(json)
                }
            }.use { client ->
                val fixture = fixture(client, bindApiSession = false)
                fixture.model.loadDevices()
                runCurrent()
                delayRegistration = true
                fixture.model.renameDevice("Retired owner name")
                runCurrent()
                fixture.sessions.saveSession(UserSession("access-b", "refresh-b", "account-b"))
                runCurrent()
                releaseRename.complete(Unit)
                runCurrent()
                assertEquals("This device", fixture.manager.getCurrentDeviceInfo().name)
                assertTrue(
                    fixture.model.uiState.value.operations
                        .isEmpty(),
                )
                fixture.model.loadDevices()
                runCurrent()
                assertEquals(
                    "This device".toUiText(),
                    fixture.model.uiState.value.devices
                        .first { it.isCurrentDevice }
                        .name,
                )
                fixture.model.stopObserving()
            }
        }

    private fun TestScope.client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) {
            engine {
                dispatcher = UnconfinedTestDispatcher(testScheduler)
                addHandler(handler)
            }
        }

    private suspend fun TestScope.fixture(
        client: HttpClient,
        bindApiSession: Boolean = true,
    ): Fixture {
        val cache = BoundarySecureStorage()
        val sessions = SecureSessionStorage(cache, DefaultLogDateConfigRepository(initialBackendUrl = origin), backgroundScope)
        sessions.saveSession(UserSession("access-a", "refresh-a", "account-a"))
        val api = AccountDeviceApi(client, sessions.takeIf { bindApiSession })
        val repository = CloudDeviceRepository(api, sessions, cache)
        val manager = DefaultDeviceManager(FakeDeviceIdProvider(currentId), repository, "This device", DevicePlatform.ANDROID, "1.0")
        val model = DevicesViewModel(manager, unavailableLinkedDevices)
        models += model
        return Fixture(model, sessions, manager)
    }

    private data class Fixture(
        val model: DevicesViewModel,
        val sessions: SecureSessionStorage,
        val manager: DefaultDeviceManager,
    )
}

private class BoundarySecureStorage : SecureStorage {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun getString(key: String): String? = values.value[key]

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        values.value = values.value + (key to value)
    }

    override suspend fun remove(key: String) {
        values.value = values.value - key
    }

    override suspend fun clear() {
        values.value = emptyMap()
    }

    override fun observeString(key: String) = values.map { it[key] }

    override fun observeAll() = values

    override suspend fun encrypt(data: ByteArray): ByteArray = data

    override suspend fun decrypt(data: ByteArray): ByteArray = data
}
