package app.logdate.client.sync.peer.devices

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.networking.DeviceLinkApiClient
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.RefreshedSession
import app.logdate.client.sync.cloud.SessionRefreshingAccountRepository
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.peer.removal.WatchRemovalAccess
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.client.sync.peer.removal.WatchRemovalTarget
import app.logdate.shared.config.PinnedLogDateConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AccountLinkedDevicesRepositoryTest {
    private val origin = "https://cloud.logdate.test"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val sessions = TestSessions(OriginBoundSession(origin, UserSession("phone-access", "phone-refresh", "account-1")))
    private val requests = mutableListOf<String>()

    @Test
    fun `linked devices are listed with their names platforms and times`() =
        runTest {
            val repository = repository { respondList(LINKED_WATCH, THIS_SESSION) }

            val loaded = repository.load()

            assertEquals(
                LinkedDevicesLoad.Loaded(
                    listOf(
                        LinkedDeviceSession(
                            id = "watch-session",
                            name = "Pixel Watch 3",
                            platform = "wear_os",
                            linkedAt = Instant.fromEpochMilliseconds(1_000),
                            lastSeenAt = Instant.fromEpochMilliseconds(2_000),
                        ),
                    ),
                ),
                loaded,
            )
            assertEquals(listOf("GET /api/v1/auth/devices Bearer phone-access"), requests)
        }

    @Test
    fun `an account without linked devices lists none`() =
        runTest {
            assertEquals(LinkedDevicesLoad.Loaded(emptyList()), repository { respondList() }.load())
        }

    @Test
    fun `nothing is asked of the server while linking is unavailable`() =
        runTest {
            val repository = repository(available = false) { respondList(LINKED_WATCH) }

            assertEquals(LinkedDevicesLoad.Unavailable, repository.load())
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a signed out phone lists nothing`() =
        runTest {
            sessions.current.value = null

            assertEquals(LinkedDevicesLoad.Unavailable, repository { respondList(LINKED_WATCH) }.load())
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `the watch paired with this phone is marked so it appears once`() =
        runTest {
            val removal = FakeWatchRemoval(paired = target(sessionId = "watch-session"))

            val loaded = repository(watchRemoval = removal) { respondList(LINKED_WATCH) }.load()

            assertEquals(true, (loaded as LinkedDevicesLoad.Loaded).devices.single().pairedWatch)
        }

    @Test
    fun `a watch connection that never answers leaves the devices listed`() =
        runTest {
            val removal = FakeWatchRemoval(paired = target(sessionId = "watch-session"), answersTarget = false)

            val loaded = repository(watchRemoval = removal) { respondList(LINKED_WATCH) }.load()

            assertEquals(false, (loaded as LinkedDevicesLoad.Loaded).devices.single().pairedWatch)
        }

    @Test
    fun `an unreachable server is reported as unreachable`() =
        runTest {
            val loaded = repository { throw IOException("Unable to resolve host") }.load()

            assertEquals(LinkedDevicesLoad.Failed(LinkedDevicesFailure.Unreachable), loaded)
        }

    @Test
    fun `a refused list keeps the server status`() =
        runTest {
            val loaded = repository { respondError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR") }.load()

            assertEquals(LinkedDevicesLoad.Failed(LinkedDevicesFailure.Refused(500)), loaded)
        }

    @Test
    fun `an unreadable list is reported as unreadable`() =
        runTest {
            val loaded = repository { respond("""{"devices":"not a list"}""", HttpStatusCode.OK, jsonHeaders) }.load()

            assertEquals(LinkedDevicesLoad.Failed(LinkedDevicesFailure.UnreadableAnswer), loaded)
        }

    @Test
    fun `an expired access token is refreshed once and the list retried`() =
        runTest {
            val refresher = FakeRefresher(Result.success(RefreshedSession("fresh-access", "fresh-refresh")))
            val repository =
                repository(refresher = refresher) { request ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer phone-access") {
                        respondError(HttpStatusCode.Unauthorized, "TOKEN_EXPIRED")
                    } else {
                        respondList(LINKED_WATCH)
                    }
                }

            val loaded = repository.load()

            assertEquals(1, (loaded as LinkedDevicesLoad.Loaded).devices.size)
            assertEquals(listOf("phone-refresh"), refresher.refreshed)
            assertEquals(UserSession("fresh-access", "fresh-refresh", "account-1"), sessions.current.value)
        }

    @Test
    fun `tokens the app refreshes during a request are not an account change`() =
        runTest {
            val repository =
                repository { _ ->
                    sessions.current.value = UserSession("rotated-access", "rotated-refresh", "account-1")
                    respondList(LINKED_WATCH)
                }

            assertEquals(1, (repository.load() as LinkedDevicesLoad.Loaded).devices.size)
        }

    @Test
    fun `an expired token the app already refreshed is retried without refreshing again`() =
        runTest {
            val refresher = FakeRefresher(Result.success(RefreshedSession("fresh-access", "fresh-refresh")))
            val repository =
                repository(refresher = refresher) { request ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer phone-access") {
                        sessions.current.value = UserSession("rotated-access", "rotated-refresh", "account-1")
                        respondError(HttpStatusCode.Unauthorized, "TOKEN_EXPIRED")
                    } else {
                        respondList(LINKED_WATCH)
                    }
                }

            val loaded = repository.load()

            assertEquals(1, (loaded as LinkedDevicesLoad.Loaded).devices.size)
            assertTrue(refresher.refreshed.isEmpty())
            assertEquals("GET /api/v1/auth/devices Bearer rotated-access", requests.last())
        }

    @Test
    fun `another account signing in during a request is an account change`() =
        runTest {
            val repository =
                repository { _ ->
                    sessions.current.value = UserSession("other-access", "other-refresh", "account-2")
                    respondList(LINKED_WATCH)
                }

            assertEquals(LinkedDevicesLoad.Failed(LinkedDevicesFailure.AccountChanged), repository.load())
        }

    @Test
    fun `a session the server no longer refreshes asks to sign in again`() =
        runTest {
            val refresher = FakeRefresher(Result.failure(CloudApiException("UNAUTHORIZED", "Refresh refused", 401)))
            val repository = repository(refresher = refresher) { respondError(HttpStatusCode.Unauthorized, "TOKEN_EXPIRED") }

            assertEquals(LinkedDevicesLoad.Failed(LinkedDevicesFailure.SignInRequired), repository.load())
        }

    @Test
    fun `removing a linked device revokes its session`() =
        runTest {
            val repository = repository { respond("", HttpStatusCode.NoContent) }

            assertEquals(LinkedDeviceRemoval.Removed(), repository.remove(watch()))
            assertEquals(listOf("DELETE /api/v1/auth/devices/watch-session Bearer phone-access"), requests)
        }

    @Test
    fun `a device the server no longer lists counts as removed`() =
        runTest {
            val repository =
                repository { request ->
                    if (request.method == HttpMethod.Delete) respondError(HttpStatusCode.NotFound, "DEVICE_NOT_FOUND") else respondList()
                }

            assertEquals(LinkedDeviceRemoval.Removed(), repository.remove(watch()))
        }

    @Test
    fun `a refused removal keeps the server status`() =
        runTest {
            val repository = repository { respondError(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE") }

            assertEquals(LinkedDeviceRemoval.Failed(LinkedDevicesFailure.Refused(503)), repository.remove(watch()))
        }

    @Test
    fun `a removal while offline is reported as unreachable`() =
        runTest {
            val repository = repository { throw IOException("Network is unreachable") }

            assertEquals(LinkedDeviceRemoval.Failed(LinkedDevicesFailure.Unreachable), repository.remove(watch()))
        }

    @Test
    fun `a removal while signed out asks to sign in`() =
        runTest {
            sessions.current.value = null

            assertEquals(LinkedDeviceRemoval.Failed(LinkedDevicesFailure.SignInRequired), repository { respondList() }.remove(watch()))
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a watch this phone set up is removed through the watch removal`() =
        runTest {
            val watchTarget = target(sessionId = "watch-session")
            val removal = FakeWatchRemoval(bySession = watchTarget, result = WatchRemovalResult.RemovedWillWipeOnNextConnect)

            val removed = repository(watchRemoval = removal) { respondList() }.remove(watch())

            assertEquals(LinkedDeviceRemoval.RemovedWillWipeOnNextConnect, removed)
            assertEquals(listOf(watchTarget), removal.removed)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `moments a watch could not sync are reported with its removal`() =
        runTest {
            val removal =
                FakeWatchRemoval(bySession = target(sessionId = "watch-session"), result = WatchRemovalResult.Removed(discarded = 3))

            val removed = repository(watchRemoval = removal) { respondList() }.remove(watch())

            assertEquals(LinkedDeviceRemoval.Removed(discarded = 3), removed)
        }

    @Test
    fun `a watch removal that stops names its step`() =
        runTest {
            val removal = FakeWatchRemoval(bySession = target(sessionId = "watch-session"), result = WatchRemovalResult.Failed("revoke"))

            val removed = repository(watchRemoval = removal) { respondList() }.remove(watch())

            assertEquals(LinkedDeviceRemoval.Failed(LinkedDevicesFailure.WatchRemovalStopped("revoke")), removed)
        }

    private fun watch() =
        LinkedDeviceSession(
            id = "watch-session",
            name = "Pixel Watch 3",
            platform = "wear_os",
            linkedAt = Instant.fromEpochMilliseconds(1_000),
            lastSeenAt = Instant.fromEpochMilliseconds(2_000),
        )

    private fun target(sessionId: String) =
        WatchRemovalTarget(
            PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.CLOUD, "account-1", origin),
            "Pixel Watch 3",
            pending = 0,
            sessionId = sessionId,
        )

    private fun repository(
        available: Boolean = true,
        refresher: SessionRefreshingAccountRepository? = null,
        watchRemoval: WatchRemovalAccess? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): LinkedDevicesRepository {
        val http =
            HttpClient(
                MockEngine { request ->
                    requests += "${request.method.value} ${request.url.encodedPath} ${request.headers[HttpHeaders.Authorization]}"
                    handler(request)
                },
            )
        return AccountLinkedDevicesRepository(
            sessions = sessions,
            linkingAvailable = { available },
            clientsFor = { bound ->
                LinkedDeviceClients(DeviceLinkApiClient(http, PinnedLogDateConfigRepository(bound.origin, descriptor = null)), refresher)
            },
            watchRemoval = watchRemoval,
        )
    }

    private fun MockRequestHandleScope.respondList(vararg devices: String) =
        respond("""{"devices":[${devices.joinToString(",")}]}""", HttpStatusCode.OK, jsonHeaders)

    private fun MockRequestHandleScope.respondError(
        status: HttpStatusCode,
        code: String,
    ) = respond("""{"error":{"code":"$code"}}""", status, jsonHeaders)

    private class TestSessions(
        private val bound: OriginBoundSession,
    ) : SessionStorage {
        val current = MutableStateFlow<UserSession?>(bound.session)

        override fun getSession(): UserSession? = current.value

        override fun getOriginBoundSession(): OriginBoundSession? = current.value?.let { bound.copy(session = it) }

        override fun getSessionFlow() = current

        override suspend fun hasValidSession(): Boolean = current.value != null

        override suspend fun saveSession(session: UserSession) {
            current.value = session
        }

        override suspend fun clearSession() {
            current.value = null
        }

        override suspend fun replaceSessionIfCurrent(
            expected: OriginBoundSession,
            updated: UserSession,
        ): Boolean = expected.origin == bound.origin && current.compareAndSet(expected.session, updated)
    }

    private class FakeRefresher(
        private val answer: Result<RefreshedSession>,
    ) : SessionRefreshingAccountRepository {
        val refreshed = mutableListOf<String>()

        override suspend fun refreshSession(refreshToken: String): Result<RefreshedSession> {
            refreshed += refreshToken
            return answer
        }
    }

    private class FakeWatchRemoval(
        private val paired: WatchRemovalTarget? = null,
        private val bySession: WatchRemovalTarget? = null,
        private val result: WatchRemovalResult = WatchRemovalResult.Removed(),
        private val answersTarget: Boolean = true,
    ) : WatchRemovalAccess {
        val removed = mutableListOf<WatchRemovalTarget>()

        override suspend fun target(): WatchRemovalTarget? {
            if (!answersTarget) awaitCancellation()
            return paired
        }

        override suspend fun targetForSession(sessionId: String): WatchRemovalTarget? = bySession?.takeIf { it.sessionId == sessionId }

        override suspend fun remove(target: WatchRemovalTarget): WatchRemovalResult {
            removed += target
            return result
        }
    }

    private companion object {
        const val LINKED_WATCH =
            """{"id":"watch-session","deviceName":"Pixel Watch 3","platform":"wear_os","createdAt":1000,""" +
                """"lastRefreshedAt":2000,"expiresAt":3000}"""
        const val THIS_SESSION =
            """{"id":"phone-session","deviceName":"Pixel 9","platform":"android","createdAt":1000,""" +
                """"lastRefreshedAt":2000,"expiresAt":3000,"current":true}"""
    }
}
