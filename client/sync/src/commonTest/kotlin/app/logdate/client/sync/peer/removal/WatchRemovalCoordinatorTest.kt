package app.logdate.client.sync.peer.removal

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.link.LinkWire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchRemovalCoordinatorTest {
    private val peer = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32)), "phone", "watch", LinkMode.PAIR)
    private val target = WatchRemovalTarget(peer, "My watch", 2)
    private val calls = mutableListOf<String>()
    private var pending = 0
    private var reachable = true
    private var revoked = true
    private var queued = true
    private var pendingAfterDrain = 0
    private var wipeReachable = true
    private var forcedReachable = true
    private var recording = false
    private var drainSchema = 1
    private val subject =
        WatchRemovalCoordinator(
            current = { it == peer },
            request = { request ->
                calls += request.action.name
                val answers =
                    when (request.action) {
                        RemovalAction.DRAIN -> reachable
                        RemovalAction.WIPE -> reachable && wipeReachable
                        RemovalAction.REVOKED_WIPE -> reachable && forcedReachable
                    }
                if (answers) {
                    val reportedPending =
                        when (request.action) {
                            RemovalAction.DRAIN -> pending
                            RemovalAction.WIPE -> pendingAfterDrain
                            RemovalAction.REVOKED_WIPE -> 0
                        }
                    val busy = recording && request.action != RemovalAction.REVOKED_WIPE
                    WatchRemovalReply(
                        schema = if (request.action == RemovalAction.DRAIN) drainSchema else 1,
                        context = peer,
                        action = request.action,
                        pending = reportedPending,
                        completed = reportedPending == 0 && !busy,
                    )
                } else {
                    null
                }
            },
            deferWipe = {
                calls += "queue wipe"
                queued
            },
            revoke = {
                calls += "revoke"
                revoked
            },
            disassociate = {
                calls += "disassociate"
                true
            },
            forget = {
                calls += "forget"
                true
            },
        )

    @Test
    fun `reachable watch drains before wiping and only then clears phone link`() =
        runTest {
            assertEquals(WatchRemovalResult.Removed(), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "WIPE", "disassociate", "forget"), calls)
        }

    @Test
    fun `moments that could not sync are dropped instead of blocking removal`() =
        runTest {
            pending = 2
            assertEquals(WatchRemovalResult.Removed(discarded = 2), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "REVOKED_WIPE", "disassociate", "forget"), calls)
        }

    @Test
    fun `a watch that stops answering before dropping its moments erases itself when it reconnects`() =
        runTest {
            pending = 2
            forcedReachable = false
            assertEquals(WatchRemovalResult.RemovedWillWipeOnNextConnect, subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "REVOKED_WIPE", "queue wipe", "forget"), calls)
        }

    @Test
    fun `offline watch has durable wipe queued before local link is forgotten`() =
        runTest {
            reachable = false
            assertEquals(WatchRemovalResult.RemovedWillWipeOnNextConnect, subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "queue wipe", "forget"), calls)
        }

    @Test
    fun `lost wipe reply queues erasure after revocation`() =
        runTest {
            wipeReachable = false
            assertEquals(WatchRemovalResult.RemovedWillWipeOnNextConnect, subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "WIPE", "queue wipe", "forget"), calls)
        }

    @Test
    fun `failed deferred queue retains the link for a retry after revocation`() =
        runTest {
            reachable = false
            queued = false
            assertEquals(WatchRemovalResult.Failed("queue wipe"), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "queue wipe"), calls)
        }

    @Test
    fun `failed revocation preserves link and does not erase watch`() =
        runTest {
            revoked = false
            assertEquals(WatchRemovalResult.Failed("revoke"), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke"), calls)
        }

    @Test
    fun `a moment captured after syncing is dropped with the rest of the watch`() =
        runTest {
            pendingAfterDrain = 1
            assertEquals(WatchRemovalResult.Removed(discarded = 1), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "WIPE", "REVOKED_WIPE", "disassociate", "forget"), calls)
        }

    @Test
    fun `a recording in progress is dropped without claiming nothing was lost`() =
        runTest {
            recording = true
            assertEquals(WatchRemovalResult.Removed(discarded = null), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "REVOKED_WIPE", "disassociate", "forget"), calls)
        }

    @Test
    fun `an unreadable drain reply still erases the watch without inventing a count`() =
        runTest {
            drainSchema = 2
            assertEquals(WatchRemovalResult.Removed(discarded = null), subject.remove(target))
            assertEquals(listOf("DRAIN", "revoke", "REVOKED_WIPE", "disassociate", "forget"), calls)
        }
}
