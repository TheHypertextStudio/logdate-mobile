package app.logdate.client.sync.recovery

import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.test.FakeCloudAccountRepository
import app.logdate.client.sync.test.FakeCloudApiClient
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.MomentChange
import app.logdate.shared.model.sync.MomentChangesResponse
import app.logdate.shared.model.sync.MomentDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MomentDurableCloudApiClientTest {
    private val db = DownloadInboxTest.Database()
    private var scope = DownloadScope("account-a", "server-a")
    private val inbox = DownloadInbox(db, db, { scope }, { 1000 })
    private val api = Api()
    private val id = "10000000-0000-0000-0000-000000000001"

    private fun mark(version: Long = 7) = MomentChange(id, 1, 2, DeviceId("watch"), "LDSE2:private-wire", 1, version)

    @Test
    fun `moment page is staged before conversion and survives a restart until exact version acknowledgement`() =
        runTest {
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7, true)
            val first = DurableCloudApiClient(api, inbox).getMomentChanges("token", 999, 1).getOrThrow()
            assertEquals(0L, api.since)
            assertTrue(first.hasMore)
            assertEquals(7L, inbox.cursor("MOMENT"))
            assertTrue(
                inbox
                    .pending("MOMENT")
                    .single()
                    .payload
                    .contains("LDSE2:private-wire"),
            )
            api.page = MomentChangesResponse(emptyList(), emptyList(), 7)
            val restartedInbox = DownloadInbox(db, db, { scope }, { 1000 })
            val restarted = DurableCloudApiClient(api, restartedInbox)
            assertEquals(listOf(mark()), restarted.getMomentChanges("token", 0).getOrThrow().changes)
            assertEquals(7L, api.since)
            restartedInbox.applied("MOMENT", id, 6)
            assertEquals(listOf(mark()), restarted.getMomentChanges("token", 0).getOrThrow().changes)
            restartedInbox.applied("MOMENT", id, 7)
            assertTrue(
                restarted
                    .getMomentChanges("token", 0)
                    .getOrThrow()
                    .changes
                    .isEmpty(),
            )
        }

    @Test
    fun `fetch failure drains retained moments without advancing the fetched checkpoint`() =
        runTest {
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            val durable = DurableCloudApiClient(api, inbox)
            durable.getMomentChanges("token", 0).getOrThrow()
            api.failure = CloudApiException("NETWORK_ERROR", "Offline")
            assertEquals(listOf(mark()), durable.getMomentChanges("token", 0).getOrThrow().changes)
            assertEquals(7L, inbox.cursor("MOMENT"))
            val emptyDb = DownloadInboxTest.Database()
            val empty = DownloadInbox(emptyDb, emptyDb, { scope }, { 1000 })
            assertTrue(DurableCloudApiClient(api, empty).getMomentChanges("token", 0).isFailure)
        }

    @Test
    fun `newer tombstone supersedes unacknowledged capture and survives an older replay`() =
        runTest {
            val durable = DurableCloudApiClient(api, inbox)
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            durable.getMomentChanges("token", 0).getOrThrow()
            val deleted = MomentDeletion(id, 1, 8)
            api.page = MomentChangesResponse(emptyList(), listOf(deleted), 8)
            val deletion = durable.getMomentChanges("token", 0).getOrThrow()
            assertTrue(deletion.changes.isEmpty())
            assertEquals(listOf(deleted), deletion.deletions)
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            assertEquals(listOf(deleted), durable.getMomentChanges("token", 0).getOrThrow().deletions)
            assertEquals(8L, inbox.cursor("MOMENT"))
        }

    @Test
    fun `malformed retained wire record stays failed without hiding a valid mark`() =
        runTest {
            inbox.stage("MOMENT", 6, listOf(WireDownload("broken", 6, false, "private-corrupt-wire")))
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            assertEquals(listOf(mark()), DurableCloudApiClient(api, inbox).getMomentChanges("token", 0).getOrThrow().changes)
            val retained = db.get(scope.owner, scope.origin, "MOMENT", "broken")!!
            assertEquals("FAILED", retained.state)
            assertEquals("CORRUPT_PAYLOAD", retained.reason)
            assertEquals("private-corrupt-wire", retained.payload)
        }

    @Test
    fun `account switch during fetch cannot stage or return the old account page`() =
        runTest {
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            api.afterFetch = { scope = DownloadScope("account-b", "server-b") }
            assertTrue(DurableCloudApiClient(api, inbox).getMomentChanges("token", 0).isFailure)
            assertEquals(0L, inbox.cursor("MOMENT"))
            assertTrue(inbox.pending("MOMENT").isEmpty())
        }

    @Test
    fun `cancelled fetch never becomes a success from retained work`() =
        runTest {
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            val durable = DurableCloudApiClient(api, inbox)
            durable.getMomentChanges("token", 0).getOrThrow()
            api.failure = CancellationException("Stopped")
            assertFailsWith<CancellationException> { durable.getMomentChanges("token", 0) }
            assertEquals(7L, inbox.cursor("MOMENT"))
        }

    @Test
    fun `authentication refusal is never hidden by cached moments`() =
        runTest {
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            val durable = DurableCloudApiClient(api, inbox)
            durable.getMomentChanges("token", 0).getOrThrow()
            for (status in listOf(401, 403)) {
                val refusal = CloudApiException("DEVICE_REVOKED", "Access refused", status)
                api.failure = refusal
                assertEquals(refusal, durable.getMomentChanges("token", 0).exceptionOrNull())
                assertEquals(7L, inbox.cursor("MOMENT"))
            }
        }

    @Test
    fun `cached moment download still refreshes an expired token before returning retained work`() =
        runTest {
            api.page = MomentChangesResponse(listOf(mark()), emptyList(), 7)
            val durable = DurableCloudApiClient(api, inbox)
            durable.getMomentChanges("test-access-token", 0).getOrThrow()
            api.page = MomentChangesResponse(emptyList(), emptyList(), 7)
            api.failure = CloudApiException("EXPIRED", "Token expired", 401)
            val tokens = mutableListOf<String>()
            api.onToken = { token ->
                tokens += token
                if (token == "new-token") api.failure = null
            }
            val sessions = FakeSessionStorage()
            val result =
                SyncTokenRefresher(sessions, FakeCloudAccountRepository())
                    .withFreshToken(
                        { token -> durable.getMomentChanges(token, 0) },
                        "moment download",
                    ).getOrThrow()
            assertEquals(listOf("test-access-token", "new-token"), tokens)
            assertEquals("new-token", sessions.getSession()?.accessToken)
            assertEquals(listOf(mark()), result.changes)
        }

    private class Api : FakeCloudApiClient() {
        var page = MomentChangesResponse(emptyList(), emptyList(), 0)
        var since: Long? = null
        var failure: Throwable? = null
        var afterFetch: () -> Unit = {}
        var onToken: (String) -> Unit = {}

        override suspend fun getMomentChanges(
            accessToken: String,
            since: Long,
            limit: Int?,
        ): Result<MomentChangesResponse> {
            this.since = since
            onToken(accessToken)
            afterFetch()
            return failure?.let { Result.failure(it) } ?: Result.success(page)
        }
    }
}
