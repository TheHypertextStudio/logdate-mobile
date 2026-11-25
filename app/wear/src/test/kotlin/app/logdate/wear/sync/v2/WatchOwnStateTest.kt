package app.logdate.wear.sync.v2

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.WatchState
import app.logdate.wear.sync.v2.link.WatchLinkRecord
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests [WatchOwnState]: the watch reports its link, counts and features as one section. */
class WatchOwnStateTest {
    @Test
    fun `a failed account link is reported with its problem`() =
        runTest {
            val record = WatchLinkRecord(LinkMode.CLOUD, "phone", 1, problem = "DEVICE_LIMIT_REACHED")

            val state = WatchOwnState(links = flowOf(record)).observe().first()

            assertEquals(PeerLinkState.NEEDS_RELINK, state.link)
            assertEquals("DEVICE_LIMIT_REACHED", state.linkProblem)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a fresh linked exchange publishes even when the visible link state stays linked`() =
        runTest {
            val record = WatchLinkRecord(LinkMode.CLOUD, "phone", 1, accountId = "account", linkId = "first")
            val records = MutableStateFlow<WatchLinkRecord?>(record)
            val emitted = mutableListOf<WatchState>()
            val job = backgroundScope.launch { WatchOwnState(records).observe().take(2).toList(emitted) }
            runCurrent()
            records.value = record.copy(linkId = "second")
            runCurrent()
            assertEquals(listOf("first", "second"), emitted.map { it.linkId })
            assertEquals(listOf(PeerLinkState.LINKED, PeerLinkState.LINKED), emitted.map { it.link })
            job.cancel()
        }

    @Test
    fun `no link record is reported as unlinked`() =
        runTest {
            assertEquals(WatchState(), WatchOwnState(links = flowOf(null)).observe().first())
        }

    @Test
    fun `a pairing is reported with the features this build has`() =
        runTest {
            val own = WatchOwnState(links = flowOf(WatchLinkRecord(LinkMode.PAIR, "phone", 1)), features = setOf("prefs.v1"))

            assertEquals(WatchState(link = PeerLinkState.PAIRED, features = setOf("prefs.v1")), own.observe().first())
        }

    @Test
    fun `a stale pairing asks to be linked again`() =
        runTest {
            val own = WatchOwnState(links = flowOf(WatchLinkRecord(LinkMode.PAIR, "phone", 1, stale = true)))

            assertEquals(PeerLinkState.NEEDS_RELINK, own.observe().first().link)
        }

    @Test
    fun `the outbox counts are reported`() =
        runTest {
            val own = WatchOwnState(links = flowOf(null), counts = flowOf(WatchCounts(outbox = 3, deadLetters = 1, freeBytes = 9)))

            assertEquals(WatchState(outboxCount = 3, deadLetterCount = 1, freeBytes = 9), own.observe().first())
        }

    @Test
    fun `watchCounts combines pending count, dead letters, and positive free space`() =
        runTest {
            val pending = MutableStateFlow(2)
            val deadLetters =
                MutableStateFlow(
                    listOf(
                        SyncDeadLetterRecord(
                            id = "dl-1",
                            entityType = "NOTE",
                            entityId = "n1",
                            operation = "CREATE",
                            retryCount = 3,
                            lastError = "err",
                            failedAt = 1L,
                        ),
                    ),
                )
            val metadata = mockk<SyncMetadataService>()
            val deadLetterStore = mockk<SyncDeadLetterStore>()
            every { metadata.observePendingCount() } returns pending
            every { deadLetterStore.observe() } returns deadLetters

            val counts = watchCounts(metadata, deadLetterStore) { 4096L }
            assertEquals(WatchCounts(outbox = 2, deadLetters = 1, freeBytes = 4096L), counts.first())

            pending.value = 0
            deadLetters.value = emptyList<SyncDeadLetterRecord>()
            val degraded = watchCounts(metadata, deadLetterStore) { throw IllegalStateException("statfs") }
            assertEquals(WatchCounts(outbox = 0, deadLetters = 0, freeBytes = null), degraded.first())
        }
}
