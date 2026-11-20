package app.logdate.client.sync.v2.link

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.StateDocument
import app.logdate.client.sync.peer.state.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests [PhoneLinkDecision], which says per watch whether the phone links it now, waits, or confirms. */
class PhoneLinkDecisionTest {
    @Test
    fun `the same account on a different server needs a new exchange`() {
        val record = linked.copy(mode = LinkMode.CLOUD, accountId = "account", serverOrigin = "https://first.test")
        assertEquals(
            PhoneLinkDecision.Exchange,
            PhoneLinkDecision.decide(record, LinkPlan(LinkMode.CLOUD, "account", "https://second.test"), null, now = 1_000),
        )
    }

    @Test
    fun `a watch that could not install the link records why`() {
        val cloud = linked.copy(mode = LinkMode.CLOUD, accountId = "account", confirmedAt = null)
        val failed =
            StateDocument(
                role = PeerRole.WATCH,
                writtenAt = 200,
                watch = WatchState(link = PeerLinkState.NEEDS_RELINK, linkProblem = "DEVICE_LIMIT_REACHED", linkId = linked.linkId),
            )

        assertEquals(
            PhoneLinkDecision.WatchProblem(PhoneLinkFailure.DEVICE_LIMIT_REACHED),
            PhoneLinkDecision.decide(cloud, LinkPlan(LinkMode.CLOUD, "account"), failed, now = 1_000),
        )
    }

    @Test
    fun `a watch whose own sign-in ran out is linked again at once without a failure`() {
        val cloud = linked.copy(mode = LinkMode.CLOUD, accountId = "account")
        val expired =
            StateDocument(
                role = PeerRole.WATCH,
                writtenAt = 200,
                watch = WatchState(link = PeerLinkState.NEEDS_RELINK, linkProblem = "SESSION_EXPIRED", linkId = linked.linkId),
            )

        assertEquals(
            PhoneLinkDecision.Exchange,
            PhoneLinkDecision.decide(cloud, LinkPlan(LinkMode.CLOUD, "account"), expired, now = 150),
        )
        assertEquals(
            PhoneLinkDecision.Wait,
            PhoneLinkDecision.decide(cloud.copy(seenWatchDocAt = 200), LinkPlan(LinkMode.CLOUD, "account"), expired, now = 150),
            "the same report does not start a second exchange",
        )
    }

    @Test
    fun `a problem this phone does not know is an install failure`() {
        val failed =
            StateDocument(
                role = PeerRole.WATCH,
                writtenAt = 200,
                watch = WatchState(link = PeerLinkState.NEEDS_RELINK, linkProblem = "SOMETHING_NEW", linkId = linked.linkId),
            )

        assertEquals(
            PhoneLinkDecision.WatchProblem(PhoneLinkFailure.WATCH_INSTALL_FAILED),
            PhoneLinkDecision.decide(linked, pair, failed, now = 1_000),
        )
    }

    @Test
    fun `a problem already recorded waits for its next attempt`() {
        val recorded = linked.copy(failure = PhoneLinkFailure.DEVICE_LIMIT_REACHED, nextAttemptAt = 5_000, seenWatchDocAt = 200)
        val failed =
            StateDocument(
                role = PeerRole.WATCH,
                writtenAt = 200,
                watch = WatchState(link = PeerLinkState.NEEDS_RELINK, linkProblem = "DEVICE_LIMIT_REACHED", linkId = linked.linkId),
            )

        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(recorded, pair, failed, now = 1_000))
    }

    private val pair = LinkPlan(LinkMode.PAIR)
    private val linked =
        PhoneWatchLink(
            nodeId = "w",
            displayName = "Watch",
            mode = LinkMode.PAIR,
            linkedAt = 100,
            confirmedAt = 100,
            lastAttemptAt = 100,
            seenWatchDocAt = 100,
            linkId = LinkWire.encodeKey(ByteArray(32) { 1 }),
        )

    @Test
    fun `a watch with no record is linked now`() {
        assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(null, pair, null, now = 1_000))
    }

    @Test
    fun `a linked watch that agrees is left alone`() {
        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(linked, pair, document(PeerLinkState.PAIRED, at = 200), now = 1_000))
    }

    @Test
    fun `a watch that reports losing its link after the grace is linked again`() {
        val now = 100 + PhoneLinkDecision.RELINK_GRACE_MS
        assertEquals(
            PhoneLinkDecision.Exchange,
            PhoneLinkDecision.decide(linked, pair, document(PeerLinkState.NEEDS_RELINK, at = now), now),
        )
    }

    @Test
    fun `a report that arrives within the grace waits`() {
        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(linked, pair, document(PeerLinkState.UNLINKED, at = 150), now = 200))
    }

    @Test
    fun `a failed watch waits until its next attempt`() {
        val failed = linked.copy(failure = PhoneLinkFailure.WATCH_UNREACHABLE, nextAttemptAt = 5_000)

        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(failed, pair, null, now = 4_999))
        assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(failed, pair, null, now = 5_000))
    }

    @Test
    fun `a new plan links the watch again`() {
        assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(linked, LinkPlan(LinkMode.CLOUD, "account"), null, now = 1_000))
    }

    @Test
    fun `an unconfirmed link is confirmed once the watch reports it`() {
        val unconfirmed = linked.copy(mode = LinkMode.CLOUD, accountId = "account", confirmedAt = null)

        assertEquals(
            PhoneLinkDecision.Confirm,
            PhoneLinkDecision.decide(
                unconfirmed,
                LinkPlan(LinkMode.CLOUD, "account"),
                document(PeerLinkState.LINKED, at = 300),
                now = 1_000,
            ),
        )
    }

    @Test
    fun `a changed plan does not inherit another plans retry wait`() {
        val failed = linked.copy(failure = PhoneLinkFailure.WATCH_UNREACHABLE, nextAttemptAt = 5_000)
        assertEquals(
            PhoneLinkDecision.Exchange,
            PhoneLinkDecision.decide(failed, LinkPlan(LinkMode.CLOUD, "account"), null, now = 1_000),
        )
    }

    @Test
    fun `an unlinked report does not bypass the retry wait`() {
        val failed = linked.copy(failure = PhoneLinkFailure.WATCH_UNREACHABLE, nextAttemptAt = 1_000_000)
        val now = 100 + PhoneLinkDecision.RELINK_GRACE_MS
        assertEquals(
            PhoneLinkDecision.Wait,
            PhoneLinkDecision.decide(failed, pair, document(PeerLinkState.UNLINKED, at = now), now),
        )
    }

    @Test
    fun `older links renew before they can authorize scoped delivery`() {
        val old = linked.copy(linkId = null)
        assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(old, pair, document(PeerLinkState.PAIRED, 200), 1_000))
    }

    @Test
    fun `invalid delivery identities request a fresh exchange`() {
        for (id in listOf("", "not-base64", LinkWire.encodeKey(ByteArray(31)))) {
            assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(linked.copy(linkId = id), pair, null, 1_000))
        }
    }

    @Test
    fun `renewing an older link still respects a recorded failure wait`() {
        val old = linked.copy(linkId = null, failure = PhoneLinkFailure.WATCH_UNREACHABLE, nextAttemptAt = 2_000)
        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(old, pair, null, 1_999))
        assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(old, pair, null, 2_000))
    }

    @Test
    fun `a report from an earlier exchange cannot confirm the new installation`() {
        val cloud = linked.copy(mode = LinkMode.CLOUD, accountId = "account", confirmedAt = null)
        for (id in listOf(null, LinkWire.encodeKey(ByteArray(32) { 2 }))) {
            val report = document(PeerLinkState.LINKED, 300).copy(watch = WatchState(link = PeerLinkState.LINKED, linkId = id))
            assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(cloud, LinkPlan(LinkMode.CLOUD, "account"), report, 1_000))
        }
    }

    @Test
    fun `an earlier installation failure cannot fail the current exchange`() {
        val report =
            document(PeerLinkState.NEEDS_RELINK, 300).copy(
                watch = WatchState(link = PeerLinkState.NEEDS_RELINK, linkProblem = "DEVICE_LIMIT_REACHED", linkId = "earlier"),
            )
        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(linked, pair, report, 1_000))
    }

    @Test
    fun `a pairing that stands in for an account link waits before the account is tried again`() {
        val standIn = linked.copy(accountWait = AccountLinkWait("account", null, attempts = 1, nextAttemptAt = 5_000))
        val account = LinkPlan(LinkMode.CLOUD, "account")
        assertEquals(PhoneLinkDecision.Wait, PhoneLinkDecision.decide(standIn, account, null, now = 4_999))
        assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(standIn, account, null, now = 5_000))
    }

    @Test
    fun `a wait for one account does not hold back another account or server`() {
        val standIn = linked.copy(accountWait = AccountLinkWait("account", "https://first.test", attempts = 3, nextAttemptAt = 5_000))
        val others =
            listOf(LinkPlan(LinkMode.CLOUD, "other", "https://first.test"), LinkPlan(LinkMode.CLOUD, "account", "https://second.test"))
        for (plan in others) {
            assertEquals(PhoneLinkDecision.Exchange, PhoneLinkDecision.decide(standIn, plan, null, now = 1_000))
        }
    }

    @Test
    fun `a failed pairing does not wait on an account link`() {
        val failed =
            linked.copy(
                failure = PhoneLinkFailure.WATCH_UNREACHABLE,
                accountWait = AccountLinkWait("account", null, attempts = 1, nextAttemptAt = 5_000),
            )
        assertEquals(
            PhoneLinkDecision.Exchange,
            PhoneLinkDecision.decide(failed, LinkPlan(LinkMode.CLOUD, "account"), null, now = 1_000),
        )
    }

    private fun document(
        link: PeerLinkState,
        at: Long,
    ) = StateDocument(role = PeerRole.WATCH, writtenAt = at, watch = WatchState(link = link, linkId = linked.linkId))
}
