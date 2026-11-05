package app.logdate.client.device.identity.data

import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.DefaultDeviceManager
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.device.identity.DeviceListObservation
import app.logdate.client.device.identity.DeviceListScope
import app.logdate.client.device.models.DevicePlatform
import app.logdate.client.device.storage.FakeSecureStorage
import app.logdate.client.device.storage.SecureSessionStorage
import app.logdate.shared.config.DefaultLogDateConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class CloudDeviceObservationTest {
    private val deviceId = Uuid.parse("223e4567-e89b-12d3-a456-426614174000")
    private val origin = "https://journal.example.com"
    private val accountScope = DeviceListScope(origin, "account-a")
    private val json =
        """
        [{
            "id": "$deviceId",
            "name": "Other device",
            "platform": "MACOS",
            "appVersion": "1.0",
            "createdAt": 1000,
            "lastActive": 2000
        }]
        """.trimIndent()

    @Test
    fun `real manager reports initial HTTP failure without inventing successful empty data`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            val release = CompletableDeferred<Unit>()
            client {
                release.await()
                respond("", HttpStatusCode.ServiceUnavailable)
            }.use { client ->
                val events = mutableListOf<DeviceListObservation>()
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { manager.observeAssociatedDevices().collect { events += it } }
                runCurrent()
                assertEquals(DeviceListObservation.Refreshing(accountScope), events.last())
                assertTrue(events.none { it is DeviceListObservation.Available })
                release.complete(Unit)
                runCurrent()
                assertEquals(DeviceListObservation.Failed(accountScope), events.last())
            }
        }

    @Test
    fun `successful empty server list is an authoritative available result`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            client { respond("[]") }.use { client ->
                val events = mutableListOf<DeviceListObservation>()
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { manager.observeAssociatedDevices().collect { events += it } }
                runCurrent()
                assertEquals(DeviceListObservation.Available(accountScope, emptyList()), events.last())
            }
        }

    @Test
    fun `cached observation stays pending until failure and explicit retry can succeed`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            var fail = false
            var release: CompletableDeferred<Unit>? = null
            client {
                release?.await()
                if (fail) respond("", HttpStatusCode.ServiceUnavailable) else respond(json)
            }.use { client ->
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                val initial = mutableListOf<DeviceListObservation>()
                val first =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        manager.observeAssociatedDevices().collect {
                            initial +=
                                it
                        }
                    }
                runCurrent()
                val cached = assertIs<DeviceListObservation.Available>(initial.last()).devices
                first.cancel()
                assertEquals(cached, manager.getAssociatedDevices().first())
                fail = true
                release = CompletableDeferred()
                val refresh = mutableListOf<DeviceListObservation>()
                val second =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        manager.observeAssociatedDevices().collect {
                            refresh +=
                                it
                        }
                    }
                runCurrent()
                assertEquals(DeviceListObservation.Refreshing(accountScope, cached), refresh.last())
                assertTrue(refresh.none { it is DeviceListObservation.Available })
                release?.complete(Unit)
                runCurrent()
                assertEquals(DeviceListObservation.Failed(accountScope, cached), refresh.last())
                second.cancel()
                fail = false
                release = null
                val retry = mutableListOf<DeviceListObservation>()
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { manager.observeAssociatedDevices().collect { retry += it } }
                runCurrent()
                assertEquals(DeviceListObservation.Available(accountScope, cached), retry.last())
            }
        }

    @Test
    fun `failed recurring polls settle without publishing a new refreshing spinner`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            var calls = 0
            client { if (++calls == 1) respond(json) else respond("", HttpStatusCode.ServiceUnavailable) }.use { client ->
                val events = mutableListOf<DeviceListObservation>()
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { manager.observeAssociatedDevices().collect { events += it } }
                runCurrent()
                val cached = assertIs<DeviceListObservation.Available>(events.last()).devices
                val initialEnd = events.size
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(DeviceListObservation.Failed(accountScope, cached), events.last())
                advanceTimeBy(5_000)
                runCurrent()
                assertTrue(events.drop(initialEnd).none { it is DeviceListObservation.Refreshing })
                assertIs<DeviceListObservation.Failed>(events.last())
            }
        }

    @Test
    fun `cancelled observation produces no failure or late completion`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            val release = CompletableDeferred<Unit>()
            client {
                release.await()
                respond(json)
            }.use { client ->
                val events = mutableListOf<DeviceListObservation>()
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                val job =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        manager.observeAssociatedDevices().collect {
                            events +=
                                it
                        }
                    }
                runCurrent()
                job.cancel()
                val count = events.size
                release.complete(Unit)
                runCurrent()
                assertEquals(count, events.size)
                assertFalse(events.any { it is DeviceListObservation.Failed })
            }
        }

    @Test
    fun `new account clears retired rows before its delayed failing request`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            val release = CompletableDeferred<Unit>()
            client { request ->
                if (request.headers["Authorization"] == "Bearer access-a") {
                    respond(json)
                } else {
                    release.await()
                    respond("", HttpStatusCode.ServiceUnavailable)
                }
            }.use { client ->
                val events = mutableListOf<DeviceListObservation>()
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { manager.observeAssociatedDevices().collect { events += it } }
                runCurrent()
                assertIs<DeviceListObservation.Available>(events.last())
                sessions.saveSession(UserSession("access-b", "refresh-b", "account-b"))
                runCurrent()
                val nextScope = DeviceListScope(origin, "account-b")
                assertEquals(DeviceListObservation.Refreshing(nextScope), events.last())
                release.complete(Unit)
                runCurrent()
                assertEquals(DeviceListObservation.Failed(nextScope), events.last())
            }
        }

    @Test
    fun `access token rotation retains display scope and cached rows`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            var release: CompletableDeferred<Unit>? = null
            client {
                release?.await()
                respond(json)
            }.use { client ->
                val events = mutableListOf<DeviceListObservation>()
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client, sessions), sessions, cache))
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { manager.observeAssociatedDevices().collect { events += it } }
                runCurrent()
                val cached = assertIs<DeviceListObservation.Available>(events.last()).devices
                release = CompletableDeferred()
                sessions.saveSession(UserSession("rotated-access", "refresh-a", "account-a"))
                runCurrent()
                assertEquals(DeviceListObservation.Refreshing(accountScope, cached), events.last())
                release?.complete(Unit)
                runCurrent()
                assertEquals(DeviceListObservation.Available(accountScope, cached), events.last())
            }
        }

    @Test
    fun `repository rejects retired owner rename before the manager changes its global name`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            val release = CompletableDeferred<Unit>()
            client {
                release.await()
                respond("{}")
            }.use { client ->
                // The repository must own this guard even when the optional HTTP helper guard is absent.
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client), sessions, cache))
                var outcome: Result<Unit>? = null
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    outcome = runCatching { manager.renameDevice("Retired owner name") }
                }
                runCurrent()
                sessions.saveSession(UserSession("access-b", "refresh-b", "account-b"))
                release.complete(Unit)
                runCurrent()
                assertTrue(outcome?.isFailure == true)
                assertEquals("This device", manager.getCurrentDeviceInfo().name)
            }
        }

    @Test
    fun `same owner token rotation does not reject an accepted pending rename`() =
        runTest {
            val cache = FakeSecureStorage()
            val sessions = sessions(cache)
            val release = CompletableDeferred<Unit>()
            client {
                release.await()
                respond("{}")
            }.use { client ->
                val manager = manager(CloudDeviceRepository(AccountDeviceApi(client), sessions, cache))
                var outcome: Result<Unit>? = null
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    outcome = runCatching { manager.renameDevice("Accepted name") }
                }
                runCurrent()
                sessions.saveSession(UserSession("rotated-access", "refresh-a", "account-a"))
                release.complete(Unit)
                runCurrent()
                assertTrue(outcome?.isSuccess == true)
                assertEquals("Accepted name", manager.getCurrentDeviceInfo().name)
            }
        }

    private suspend fun TestScope.sessions(cache: FakeSecureStorage): SecureSessionStorage =
        SecureSessionStorage(cache, DefaultLogDateConfigRepository(initialBackendUrl = origin), backgroundScope).also {
            it.saveSession(UserSession("access-a", "refresh-a", "account-a"))
        }

    private fun TestScope.client(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine) {
            engine {
                dispatcher = UnconfinedTestDispatcher(testScheduler)
                addHandler(handler)
            }
        }

    private fun manager(repository: CloudDeviceRepository): DefaultDeviceManager =
        DefaultDeviceManager(
            deviceIdProvider =
                object : DeviceIdProvider {
                    private val id = MutableStateFlow(Uuid.parse("123e4567-e89b-12d3-a456-426614174000"))

                    override fun getDeviceId() = id

                    override suspend fun refreshDeviceId() = Unit
                },
            deviceRepository = repository,
            initialDeviceName = "This device",
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0",
        )
}
