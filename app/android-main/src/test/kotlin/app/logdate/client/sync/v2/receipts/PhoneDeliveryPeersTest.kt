package app.logdate.client.sync.v2.receipts

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.v2.FakeSessions
import app.logdate.client.sync.v2.MemoryKeyValueStorage
import app.logdate.client.sync.v2.MemoryPairKeyVault
import app.logdate.client.sync.v2.link.LinkPlan
import app.logdate.client.sync.v2.link.LinkTarget
import app.logdate.client.sync.v2.link.PhoneLinkFailure
import app.logdate.client.sync.v2.link.PhoneWatchLinkStore
import app.logdate.shared.config.LogDateConfigRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneDeliveryPeersTest {
    private val sessions = FakeSessions(OriginBoundSession(ORIGIN, UserSession("access", "refresh", "account")))
    private val pairKeys = MemoryPairKeyVault()
    private val links = PhoneWatchLinkStore(MemoryKeyValueStorage(), pairKeys)
    private val config = mockk<LogDateConfigRepository>()
    private var active = true
    private var beforeNode: suspend () -> Unit = {}
    private val peers =
        PhoneDeliveryPeers(links, sessions, config, {
            beforeNode()
            "phone"
        }, { active })
    private val linkId = LinkWire.encodeKey(ByteArray(32) { 1 })
    private val plan = LinkPlan(LinkMode.CLOUD, "account", ORIGIN)
    private val context get() = PeerDeliveryContext(linkId, "phone", "watch", LinkMode.CLOUD, "account", ORIGIN)

    init {
        every { config.getCurrentBackendUrl() } returns ORIGIN
    }

    @Test
    fun `a linked watch gets only its own account and server audience`() =
        runTest {
            link()
            assertEquals(context, peers.current("watch"))
            assertTrue(peers.isCurrent(context))
            assertNull(peers.current("another-watch"))
        }

    @Test
    fun `copied metadata without its pair key cannot authorize delivery or removal`() =
        runTest {
            link()
            pairKeys.remove("watch")
            assertNull(peers.current("watch"))
            link()
            pairKeys.remove("watch")
            assertNull(peers.forRemoval("watch"))
            link()
            pairKeys.remove("watch")
            assertTrue(peers.all().isEmpty())
        }

    @Test
    fun `losing the pair key during lookup discards the audience`() =
        runTest {
            link()
            beforeNode = { pairKeys.remove("watch") }
            assertNull(peers.current("watch"))
        }

    @Test
    fun `pair only links resolve while this phone has no account`() =
        runTest {
            sessions.bound = null
            link(plan = LinkPlan(LinkMode.PAIR))
            assertEquals(context.copy(mode = LinkMode.PAIR, accountId = null, serverOrigin = null), peers.current("watch"))
        }

    @Test
    fun `each linked watch has its own receipt namespace`() =
        runTest {
            link()
            val secondId = LinkWire.encodeKey(ByteArray(32) { 2 })
            link(node = "second-watch", id = secondId)
            val all = peers.all()
            assertEquals(2, all.size)
            assertEquals(2, all.map { it.ledgerScope }.toSet().size)
            assertEquals(setOf("watch", "second-watch"), all.map { it.watchNode }.toSet())
        }

    @Test
    fun `sign out and account switches cannot reuse an earlier cloud audience`() =
        runTest {
            link()
            sessions.bound = null
            assertNull(peers.current("watch"))
            sessions.bound = OriginBoundSession(ORIGIN, UserSession("other", "refresh", "different-account"))
            assertNull(peers.current("watch"))
        }

    @Test
    fun `a server switch cannot reuse a same named account audience`() =
        runTest {
            link()
            sessions.bound = OriginBoundSession("https://other.test", UserSession("access", "refresh", "account"))
            assertNull(peers.current("watch"))
        }

    @Test
    fun `a changed backend cannot authorize the old session`() =
        runTest {
            link()
            every { config.getCurrentBackendUrl() } returns "https://other.test"
            assertNull(peers.current("watch"))
        }

    @Test
    fun `pair only links cannot receive a signed in account's proof`() =
        runTest {
            link(plan = LinkPlan(LinkMode.PAIR))
            assertNull(peers.current("watch"))
        }

    @Test
    fun `a pairing that stands in for this account's link resolves while signed in`() =
        runTest {
            link(plan = LinkPlan(LinkMode.PAIR))
            links.deferAccountLink("watch", plan, at = 2)
            assertEquals(context.copy(mode = LinkMode.PAIR, accountId = null, serverOrigin = null), peers.current("watch"))
        }

    @Test
    fun `a pairing that stands in for one account cannot serve another account or server`() =
        runTest {
            link(plan = LinkPlan(LinkMode.PAIR))
            links.deferAccountLink("watch", plan, at = 2)
            sessions.bound = OriginBoundSession(ORIGIN, UserSession("other", "refresh", "different-account"))
            assertNull(peers.current("watch"))
            sessions.bound = OriginBoundSession("https://other.test", UserSession("access", "refresh", "account"))
            assertNull(peers.current("watch"))
        }

    @Test
    fun `originless and pre identity links cannot authorize delivery`() =
        runTest {
            link(id = null)
            assertNull(peers.current("watch"))
            link(plan = plan.copy(serverOrigin = null))
            assertNull(peers.current("watch"))
        }

    @Test
    fun `disabled rollout and failed links cannot authorize receipts`() =
        runTest {
            link()
            active = false
            assertNull(peers.current("watch"))
            active = true
            links.recordFailure(LinkTarget("watch", "Watch"), plan, PhoneLinkFailure.WATCH_UNREACHABLE, 2, 2)
            assertNull(peers.current("watch"))
        }

    @Test
    fun `an account change during node lookup discards the audience`() =
        runTest {
            link()
            beforeNode = { sessions.bound = null }
            assertNull(peers.current("watch"))
        }

    @Test
    fun `a relink during lookup discards the old exchange`() =
        runTest {
            link()
            beforeNode = { link(id = LinkWire.encodeKey(ByteArray(32) { 2 })) }
            assertNull(peers.current("watch"))
        }

    @Test
    fun `a retained context becomes invalid after a fresh link`() =
        runTest {
            link()
            val captured = assertNotNull(peers.current("watch"))
            link(id = LinkWire.encodeKey(ByteArray(32) { 2 }))
            assertFalse(peers.isCurrent(captured))
        }

    @Test
    fun `lookup cancellation propagates and unavailable nodes give no authority`() =
        runTest {
            link()
            beforeNode = { throw CancellationException("Stopped") }
            assertFailsWith<CancellationException> { peers.current("watch") }
            beforeNode = { throw IllegalStateException("Play services unavailable") }
            assertNull(peers.current("watch"))
        }

    @Test
    fun `a stored record under another source key cannot authorize that source`() =
        runTest {
            link()
            val mismatched = mockk<PhoneWatchLinkStore>()
            coEvery { mismatched.getForReconciliation("another-watch") } returns links.get("watch")
            val resolver = PhoneDeliveryPeers(mismatched, sessions, config, { "phone" }, { true })
            assertNull(resolver.current("another-watch"))
        }

    @Test
    fun `a session without origin provenance cannot become pair only`() =
        runTest {
            link(plan = LinkPlan(LinkMode.PAIR))
            val opaque = mockk<SessionStorage>()
            every { opaque.getOriginBoundSession() } returns null
            every { opaque.getSession() } returns UserSession("access", "refresh", "account")
            val resolver = PhoneDeliveryPeers(links, opaque, config, { "phone" }, { true })
            assertNull(resolver.current("watch"))
        }

    @Test
    fun `disabling delivery during lookup discards the captured audience`() =
        runTest {
            link()
            beforeNode = { active = false }
            assertNull(peers.current("watch"))
        }

    private suspend fun link(
        node: String = "watch",
        id: String? = linkId,
        plan: LinkPlan = this.plan,
    ) {
        links.recordSuccess(LinkTarget(node, "Watch"), plan, ByteArray(32), 1, 1, linkId = id)
    }

    private companion object {
        const val ORIGIN = "https://logdate.test"
    }
}
