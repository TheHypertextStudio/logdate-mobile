package app.logdate.client.device.identity.data

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.DeviceRepository
import app.logdate.client.device.identity.di.deviceIdentityModule
import app.logdate.client.device.storage.FakeSecureStorage
import app.logdate.client.device.storage.SecureSessionStorage
import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.RegisterDeviceRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AccountDeviceSessionTest {
    @Test
    fun `platform device repository observes connected devices after session refresh`() =
        runTest {
            val cache = FakeSecureStorage()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://journal.example.com")
            val sessions = SecureSessionStorage(cache, config, backgroundScope)
            sessions.saveSession(UserSession("expired", "refresh", "owner"))
            val id = Uuid.random()
            var refreshes = 0
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        when {
                            request.url.encodedPath.endsWith("/auth/token/refresh") -> {
                                refreshes++
                                respond("""{"success":true,"data":{"accessToken":"fresh"}}""")
                            }

                            request.headers[HttpHeaders.Authorization] == "Bearer expired" -> {
                                respond("", HttpStatusCode.Unauthorized)
                            }

                            else -> {
                                respond(
                                    """[{"id":"$id","name":"Test device","platform":"MACOS",
                    "appVersion":"1","createdAt":1000,"lastActive":2000}]""",
                                )
                            }
                        }
                    }
                }
            }.use { client ->
                val application =
                    koinApplication {
                        modules(
                            deviceIdentityModule,
                            module {
                                single<HttpClient> { client }
                                single<SessionStorage> { sessions }
                                single<SecureStorage> { cache }
                            },
                        )
                    }
                try {
                    val repository = application.koin.get<DeviceRepository>()
                    val devices = withTimeout(1_000) { repository.getAssociatedDevices().first { it.isNotEmpty() } }
                    assertEquals(id, devices.single().id)
                } finally {
                    application.close()
                }
            }
            assertEquals(1, refreshes)
            assertEquals("fresh", sessions.getSession()?.accessToken)
        }

    @Test
    fun `device list refreshes an expired token and preserves the account and origin`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://journal.example.com")
            val sessions = SecureSessionStorage(FakeSecureStorage(), config, backgroundScope)
            sessions.saveSession(UserSession("expired", "refresh", "owner"))
            val captured = sessions.getOriginBoundSession()!!
            val requests = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    assertEquals("journal.example.com", request.url.host)
                    requests += request.url.encodedPath
                    when {
                        request.url.encodedPath.endsWith(
                            "/auth/token/refresh",
                        ) -> {
                            respond("""{"success":true,"data":{"accessToken":"fresh"}}""")
                        }

                        request.headers[HttpHeaders.Authorization] == "Bearer expired" -> {
                            respond("", HttpStatusCode.Unauthorized)
                        }

                        else -> {
                            assertEquals("Bearer fresh", request.headers[HttpHeaders.Authorization])
                            respond("[]")
                        }
                    }
                },
            ).use { client ->
                assertEquals(emptyList(), AccountDeviceApi(client, sessions).list(captured))
            }
            assertEquals(listOf("/api/v1/devices", "/api/v1/auth/token/refresh", "/api/v1/devices"), requests)
            assertEquals(captured.copy(session = captured.session.copy(accessToken = "fresh")), sessions.getOriginBoundSession())
        }

    @Test
    fun `idempotent registration retries once after a token refresh`() =
        runTest {
            val sessions = SecureSessionStorage(FakeSecureStorage(), DefaultLogDateConfigRepository(), backgroundScope)
            sessions.saveSession(UserSession("expired", "refresh", "owner"))
            val captured = sessions.getOriginBoundSession()!!
            val id = Uuid.random()
            var attempts = 0
            HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/token/refresh")) {
                        respond("""{"success":true,"data":{"accessToken":"fresh"}}""")
                    } else {
                        assertEquals("/api/v1/devices/$id", request.url.encodedPath)
                        attempts++
                        respond("{}", if (attempts == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK)
                    }
                },
            ).use { client ->
                assertTrue(AccountDeviceApi(client, sessions).register(captured, id, RegisterDeviceRequest("Test device", "MACOS", "1")))
            }
            assertEquals(2, attempts)
        }

    @Test
    fun `refresh never overwrites a newly selected account or retries against another origin`() =
        runTest {
            for (switchOrigin in listOf(false, true)) {
                val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://journal.example.com")
                val sessions = SecureSessionStorage(FakeSecureStorage(), config, backgroundScope)
                sessions.saveSession(UserSession("expired", "refresh", "owner"))
                val captured = sessions.getOriginBoundSession()!!
                var requests = 0
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals("journal.example.com", request.url.host)
                        if (request.url.encodedPath.endsWith("/auth/token/refresh")) {
                            if (switchOrigin) config.updateBackendUrl("https://other.example.com")
                            sessions.saveSession(UserSession("other-access", "other-refresh", "other-owner"))
                            respond("""{"success":true,"data":{"accessToken":"stale-response"}}""")
                        } else {
                            respond("", HttpStatusCode.Unauthorized)
                        }
                    },
                ).use { client ->
                    assertFailsWith<CancellationException> { AccountDeviceApi(client, sessions).list(captured) }
                }
                assertEquals(2, requests)
                assertEquals("other-owner", sessions.getOriginBoundSession()?.session?.accountId)
                assertEquals("other-access", sessions.getOriginBoundSession()?.session?.accessToken)
            }
        }

    @Test
    fun `a rejected refresh or repeated unauthorized response cannot create a retry loop`() =
        runTest {
            for (refreshSucceeds in listOf(false, true)) {
                val sessions = SecureSessionStorage(FakeSecureStorage(), DefaultLogDateConfigRepository(), backgroundScope)
                sessions.saveSession(UserSession("expired", "refresh", "owner"))
                val captured = sessions.getOriginBoundSession()!!
                var requests = 0
                HttpClient(
                    MockEngine { request ->
                        requests++
                        if (request.url.encodedPath.endsWith("/auth/token/refresh") && refreshSucceeds) {
                            respond("""{"success":true,"data":{"accessToken":"fresh"}}""")
                        } else {
                            respond("", HttpStatusCode.Unauthorized)
                        }
                    },
                ).use { client ->
                    assertEquals(
                        false,
                        AccountDeviceApi(client, sessions).register(captured, Uuid.random(), RegisterDeviceRequest("Test", "MACOS", "1")),
                    )
                }
                assertEquals(if (refreshSucceeds) 3 else 2, requests)
            }
        }
}
