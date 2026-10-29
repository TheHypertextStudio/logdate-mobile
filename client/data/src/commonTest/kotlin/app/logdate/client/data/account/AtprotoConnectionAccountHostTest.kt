package app.logdate.client.data.account

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.storage.SecureSessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AtprotoConnectionAccountHostTest {
    @Test
    fun `ordinary token renewal retains authentication generation and binding never contains the refresh credential`() =
        runTest {
            val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
            val sessions = SecureSessionStorage(RenewalStorage(), config, backgroundScope)
            sessions.saveExplicitSession(SESSION)
            val host = AtprotoConnectionAccountHost(sessions, config::getCurrentBackendUrl) { Result.success(Unit) }
            val original = assertNotNull(host.binding())
            assertFalse(original.authenticationGeneration.contains(SESSION.refreshToken))
            assertEquals(43, original.authenticationGeneration.length)
            val captured = assertNotNull(sessions.captureAuthenticationSnapshot())
            sessions.replaceSessionIfAuthorized(captured.session!!, SESSION.copy(accessToken = "renewed-access"), captured.guard)
            assertEquals(original, host.binding())
            assertEquals("renewed-access", host.accessToken(original, null))
            sessions.saveExplicitSession(SESSION.copy(refreshToken = "different-refresh-credential"))
            assertNotEquals(original, host.binding())
            assertFailsWith<CancellationException> { host.accessToken(original, null) }
        }

    @Test
    fun `rejected access token renews only its captured origin account and grant`() =
        runTest {
            val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
            val sessions = SecureSessionStorage(RenewalStorage(), config, backgroundScope)
            sessions.saveExplicitSession(SESSION)
            var requested: OriginBoundSession? = null
            val host =
                AtprotoConnectionAccountHost(sessions, config::getCurrentBackendUrl) { expected ->
                    requested = expected
                    val captured = assertNotNull(sessions.captureAuthenticationSnapshot())
                    sessions.replaceSessionIfAuthorized(expected, expected.session.copy(accessToken = "renewed-access"), captured.guard)
                    Result.success(Unit)
                }
            val binding = assertNotNull(host.binding())
            assertEquals("renewed-access", host.accessToken(binding, "initial-access"))
            assertEquals(SESSION, requested?.session)
            assertEquals(config.getCurrentBackendUrl(), requested?.origin)
            assertEquals(binding, host.binding())
            assertEquals("renewed-access", host.accessToken(binding, "initial-access"))
        }

    @Test
    fun `a token returned after another explicit sign in can never escape to the old controller`() =
        runTest {
            val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
            val sessions = SecureSessionStorage(RenewalStorage(), config, backgroundScope)
            sessions.saveExplicitSession(SESSION)
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val host =
                AtprotoConnectionAccountHost(sessions, config::getCurrentBackendUrl) {
                    entered.complete(Unit)
                    released.await()
                    Result.success(Unit)
                }
            val binding = assertNotNull(host.binding())
            val request = async { host.accessToken(binding, SESSION.accessToken) }
            withTimeout(1000) { entered.await() }
            val replacement = SESSION.copy(accountId = OTHER_OWNER, refreshToken = "replacement-grant")
            sessions.saveExplicitSession(replacement)
            released.complete(Unit)
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(replacement, sessions.getSession())
        }

    @Test
    fun `missing provenance sign out and backend change have no usable connection authorization`() =
        runTest {
            val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
            val sessions = SecureSessionStorage(RenewalStorage(), config, backgroundScope)
            sessions.saveExplicitSession(SESSION)
            val host = AtprotoConnectionAccountHost(sessions, config::getCurrentBackendUrl) { Result.success(Unit) }
            val binding = assertNotNull(host.binding())
            sessions.clearSession()
            assertNull(host.binding())
            assertFailsWith<CancellationException> { host.accessToken(binding, null) }
            sessions.saveExplicitSession(SESSION)
            config.updateBackendUrl("https://other.example")
            assertNull(host.binding())
            val unguarded = DefaultPasskeyAccountRepositoryTest.FakeSessionStorage()
            unguarded.saveSession(SESSION)
            assertNull(AtprotoConnectionAccountHost(unguarded, config::getCurrentBackendUrl) { Result.success(Unit) }.binding())
        }

    private companion object {
        const val OTHER_OWNER = "00000000-0000-4000-8000-000000000002"
        val SESSION = UserSession("initial-access", "private-refresh-credential", "00000000-0000-4000-8000-000000000001")
    }
}
