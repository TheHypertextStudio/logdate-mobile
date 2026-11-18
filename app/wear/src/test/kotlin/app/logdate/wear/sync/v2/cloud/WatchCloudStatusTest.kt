package app.logdate.wear.sync.v2.cloud

import app.cash.turbine.test
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncPausedReason
import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.peer.link.LinkProblem
import app.logdate.wear.sync.v2.link.WatchLinkRecord
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchCloudStatusTest {
    private val cloudLink = WatchLinkRecord(mode = LinkMode.CLOUD, phoneNode = "phone", linkedAt = 1L, accountId = "account")

    @Test
    fun `the cloud path applies only to a watch linked to an account under the new app`() {
        assertEquals(WatchCloudPath.UNUSED, watchCloudPathOf(newAppOn = false, link = cloudLink))
        assertEquals(WatchCloudPath.UNUSED, watchCloudPathOf(newAppOn = true, link = null))
        assertEquals(WatchCloudPath.UNUSED, watchCloudPathOf(newAppOn = true, link = cloudLink.copy(mode = LinkMode.PAIR)))
        assertEquals(WatchCloudPath.UNUSED, watchCloudPathOf(newAppOn = true, link = cloudLink.copy(installing = true)))
        assertEquals(WatchCloudPath.ACTIVE, watchCloudPathOf(newAppOn = true, link = cloudLink))
    }

    @Test
    fun `a link that moved or failed needs the phone to link the watch again`() {
        val revoked = cloudLink.copy(problem = LinkProblem.SESSION_REVOKED.name)
        val movedPair = cloudLink.copy(mode = LinkMode.PAIR, stale = true)

        assertEquals(WatchCloudPath.NEEDS_LINK, watchCloudPathOf(newAppOn = true, link = cloudLink.copy(stale = true)))
        assertEquals(WatchCloudPath.NEEDS_LINK, watchCloudPathOf(newAppOn = true, link = revoked))
        assertEquals(WatchCloudPath.NEEDS_LINK, watchCloudPathOf(newAppOn = true, link = movedPair))
        assertEquals(WatchCloudPath.UNUSED, watchCloudPathOf(newAppOn = false, link = cloudLink.copy(stale = true)))
    }

    @Test
    fun `a sign-in that ran out is not reported as a problem while the phone renews it`() {
        val expired = cloudLink.copy(problem = LinkProblem.SESSION_EXPIRED.name)

        assertEquals(WatchCloudPath.UNUSED, watchCloudPathOf(newAppOn = true, link = expired))
    }

    @Test
    fun `the engine is not built while the cloud path does not apply`() =
        runTest {
            var built = 0
            val path = MutableStateFlow(WatchCloudPath.UNUSED)

            watchCloudStatus(path) {
                built++
                engine(MutableStateFlow(status()))
            }.test {
                assertNull(awaitItem())
                assertEquals(0, built)
            }
        }

    @Test
    fun `the engine's own status is followed while the watch is linked`() =
        runTest {
            val engineStatus = MutableStateFlow(status(pausedReason = SyncPausedReason.NEEDS_DEVICE_LINK))
            val path = MutableStateFlow(WatchCloudPath.UNUSED)

            watchCloudStatus(path) { engine(engineStatus) }.test {
                assertNull(awaitItem())

                path.value = WatchCloudPath.ACTIVE
                assertEquals(SyncPausedReason.NEEDS_DEVICE_LINK, awaitItem()?.pausedReason)

                engineStatus.value = status(pending = 2)
                assertEquals(2, awaitItem()?.pendingUploads)

                path.value = WatchCloudPath.UNUSED
                assertNull(awaitItem())
            }
        }

    @Test
    fun `a link that needs redoing reports a device link pause`() =
        runTest {
            watchCloudStatus(MutableStateFlow(WatchCloudPath.NEEDS_LINK)) { engine(MutableStateFlow(status())) }.test {
                assertEquals(SyncPausedReason.NEEDS_DEVICE_LINK, awaitItem()?.pausedReason)
            }
        }

    @Test
    fun `an engine that cannot be built is reported as a failed backup`() =
        runTest {
            watchCloudStatus(MutableStateFlow(WatchCloudPath.ACTIVE)) { error("The cloud engine has no database") }.test {
                val failed = awaitItem()
                assertTrue(failed?.hasErrors == true)
                assertEquals(false, failed?.lastError?.retryable)
            }
        }

    @Test
    fun `a failed engine is built again when the watch is linked again`() =
        runTest {
            var builds = 0
            val path = MutableStateFlow(WatchCloudPath.ACTIVE)

            watchCloudStatus(path) {
                builds++
                if (builds == 1) error("Not ready yet")
                engine(MutableStateFlow(status(pending = 1)))
            }.test {
                assertTrue(awaitItem()?.hasErrors == true)

                path.value = WatchCloudPath.NEEDS_LINK
                assertEquals(SyncPausedReason.NEEDS_DEVICE_LINK, awaitItem()?.pausedReason)
                path.value = WatchCloudPath.ACTIVE
                assertEquals(1, awaitItem()?.pendingUploads)
            }
        }

    @Test
    fun `a path that cannot be read is reported as a failed backup`() =
        runTest {
            watchCloudStatus(flow { throw IllegalStateException("Flags unreadable") }) { engine(MutableStateFlow(status())) }.test {
                assertTrue(awaitItem()?.hasErrors == true)
                awaitComplete()
            }
        }

    private fun engine(statusFlow: MutableStateFlow<SyncStatus>): SyncManager = mockk { every { syncStatusFlow } returns statusFlow }

    private fun status(
        pending: Int = 0,
        pausedReason: SyncPausedReason? = null,
    ) = SyncStatus(
        isEnabled = true,
        lastSyncTime = null,
        pendingUploads = pending,
        isSyncing = false,
        hasErrors = false,
        pausedReason = pausedReason,
    )
}
