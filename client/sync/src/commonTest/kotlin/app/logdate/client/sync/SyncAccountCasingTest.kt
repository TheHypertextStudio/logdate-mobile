package app.logdate.client.sync

import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudRequestLocation
import app.logdate.client.sync.cloud.CloudRequestLocationProvider
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.test.FakeCloudAccountRepository
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.shared.model.CloudAccountRepository
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncAccountCasingTest {
    private val raw = "A8A3400A-9F3C-4FCA-9A7A-7C8CBE5CA24E"
    private val origin = "https://server.test"

    private fun accounts() =
        object : CloudAccountRepository by FakeCloudAccountRepository(), CloudRequestLocationProvider {
            override fun captureLocation() = CloudRequestLocation(origin, "$origin/api/v1")

            override fun isCurrentOrigin(origin: String) = origin == this@SyncAccountCasingTest.origin
        }

    @Test
    fun `retained upload scope admits the same account and refreshes without rewriting its owner`() =
        runTest {
            val sessions = FakeSessionStorage().apply { currentOrigin = origin }
            sessions.saveSession(UserSession("expired", "refresh", raw.lowercase()))
            val expected = UploadScope(raw, origin)
            val refresher = SyncTokenRefresher(sessions, accounts())
            val seen = mutableListOf<String>()
            val result =
                withContext(FullSyncScope(refresher.currentUploadScope())) {
                    refresher.withFreshToken({ token ->
                        seen += token
                        if (token == "expired") Result.failure(CloudApiException("EXPIRED", "expired", 401)) else Result.success("uploaded")
                    }, "retained upload", expected)
                }
            assertEquals("uploaded", result.getOrThrow())
            assertEquals(listOf("expired", "new-token"), seen)
            assertEquals(raw, expected.ownerId)
            assertEquals(raw.lowercase(), sessions.getSession()?.accountId)
        }

    @Test
    fun `a different account or origin cannot authorize the retained scope`() =
        runTest {
            val sessions = FakeSessionStorage().apply { currentOrigin = origin }
            sessions.saveSession(UserSession("access", "refresh", raw.lowercase()))
            val refresher = SyncTokenRefresher(sessions, accounts())
            for (scope in listOf(UploadScope("b930bf49-d1e9-4a65-b84b-8e74af45012b", origin), UploadScope(raw, "https://other.test"))) {
                assertTrue(refresher.withFreshToken<Unit>({ error("Wrong scope must never dispatch") }, "refused", scope).isFailure)
            }
        }

    @Test
    fun `case equivalence does not weaken the captured session fence`() =
        runTest {
            val sessions = FakeSessionStorage().apply { currentOrigin = origin }
            val captured = UserSession("access", "refresh", raw.lowercase())
            sessions.saveSession(captured)
            val result =
                SyncTokenRefresher(sessions, accounts()).withFreshToken({
                    sessions.saveSession(captured.copy(accountId = raw))
                    Result.success("stale response")
                }, "captured session", UploadScope(raw, origin))
            assertTrue(result.isFailure)
            assertEquals(raw, sessions.getSession()?.accountId)
        }
}
