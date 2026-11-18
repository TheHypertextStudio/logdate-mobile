package app.logdate.feature.core.settings.ui.watch

import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests [WatchStatusResolver]: the phone's own view of the connection decides the status, and each
 * line comes only from what the watch reported under a feature it listed while version 2 is active.
 */
class WatchStatusResolverTest {
    private val connected = WatchConnectionState.Connected(watchName = NAME)
    private val outOfRange = WatchConnectionState.OutOfRange(watchName = NAME)

    private fun reporting(
        watch: WatchState,
        vararg features: String,
    ) = WatchPeerSnapshot(v2Active = true, watch = watch, features = features.toSet())

    private fun resolve(
        connection: WatchConnectionState,
        peer: WatchPeerSnapshot = WatchPeerSnapshot.Inactive,
        windowDays: Int = 14,
    ) = WatchStatusResolver.resolve(connection, peer, windowDays)

    @Test
    fun `nothing is known while the phone looks for the watch`() {
        assertNull(resolve(WatchConnectionState.Loading))
    }

    @Test
    fun `a phone without a paired watch has no watch`() {
        assertEquals(WatchStatus.NoWatch, resolve(WatchConnectionState.NoPairedWatch))
    }

    @Test
    fun `a watch LogDate is not linked with yet is being set up`() {
        assertEquals(
            WatchStatus.SettingUp(WatchSetupStep.PairWithPhone(NAME)),
            resolve(WatchConnectionState.NeedsAssociation(NAME)),
        )
        assertEquals(WatchStatus.SettingUp(WatchSetupStep.ConfirmOnPhone), resolve(WatchConnectionState.AssociationPending))
    }

    @Test
    fun `a watch LogDate links by itself never asks for the system pairing prompt`() {
        assertEquals(
            WatchStatus.SettingUp(WatchSetupStep.Linking(NAME), "node"),
            resolve(WatchConnectionState.NeedsAssociation(NAME, "node", WatchLinking())),
        )
        assertEquals(
            WatchStatus.SettingUp(WatchSetupStep.Linking(NAME, WatchLinkProblem.DEVICE_LIMIT), "node"),
            resolve(WatchConnectionState.NeedsAssociation(NAME, "node", WatchLinking(WatchLinkProblem.DEVICE_LIMIT))),
        )
    }

    @Test
    fun `a watch without LogDate is not installed`() {
        assertEquals(WatchStatus.NotInstalled(NAME), resolve(WatchConnectionState.AppNotInstalled(NAME)))
    }

    @Test
    fun `a connected watch that reports nothing shows only its name`() {
        assertEquals(WatchStatus.Ready(watchName = NAME, nearby = true), resolve(connected))
    }

    @Test
    fun `a watch out of range is not nearby`() {
        assertEquals(WatchStatus.Ready(watchName = NAME, nearby = false), resolve(outOfRange))
    }

    @Test
    fun `a watch that lists no features shows no lines`() {
        assertEquals(WatchStatus.Ready(watchName = NAME, nearby = true), resolve(connected, reporting(WatchState(outboxCount = 5))))
    }

    @Test
    fun `a watch that lists receipts shows how many moments wait`() {
        assertEquals(
            WatchStatus.Ready(
                watchName = NAME,
                nearby = true,
                sync = WatchSyncLine(waiting = 3),
                capabilities = setOf(WearFeatures.RECEIPTS),
            ),
            resolve(connected, reporting(WatchState(outboxCount = 3), WearFeatures.RECEIPTS)),
        )
    }

    @Test
    fun `a watch that lists the window shows how many days it covers`() {
        assertEquals(
            WatchStatus.Ready(
                watchName = NAME,
                nearby = true,
                window = WatchWindowLine(days = 30),
                capabilities = setOf(WearFeatures.WINDOW),
            ),
            resolve(connected, reporting(WatchState(), WearFeatures.WINDOW), windowDays = 30),
        )
    }

    @Test
    fun `the link line follows the link the watch reports`() {
        fun link(state: PeerLinkState) =
            (resolve(connected, reporting(WatchState(link = state), WearFeatures.LINK_CLOUD)) as WatchStatus.Ready).link

        assertEquals(WatchLinkLine.THROUGH_PHONE, link(PeerLinkState.PAIRED))
        assertEquals(WatchLinkLine.ON_ITS_OWN, link(PeerLinkState.LINKED))
        assertNull(link(PeerLinkState.UNLINKED))
    }

    @Test
    fun `features count only while version 2 is active`() {
        val inactive =
            WatchPeerSnapshot(
                v2Active = false,
                watch = WatchState(outboxCount = 3, deadLetterCount = 2),
                features = setOf(WearFeatures.RECEIPTS, WearFeatures.WINDOW),
            )

        assertEquals(WatchStatus.Ready(watchName = NAME, nearby = true), resolve(connected, inactive))
    }

    @Test
    fun `a watch that lost its link needs to be set up again`() {
        assertEquals(
            WatchStatus.NeedsAttention(watchName = NAME, nearby = true, issue = WatchIssue.SetUpAgain),
            resolve(connected, reporting(WatchState(link = PeerLinkState.NEEDS_RELINK), WearFeatures.LINK_CLOUD)),
        )
        assertEquals(
            WatchStatus.Ready(watchName = NAME, nearby = true),
            resolve(connected, reporting(WatchState(link = PeerLinkState.NEEDS_RELINK))),
        )
    }

    @Test
    fun `a watch whose sign-in ran out needs nothing from the person while the phone renews it`() {
        val expired = WatchState(link = PeerLinkState.NEEDS_RELINK, linkProblem = "SESSION_EXPIRED")

        assertIs<WatchStatus.Ready>(resolve(connected, reporting(expired, WearFeatures.LINK_CLOUD)))
    }

    @Test
    fun `moments that did not send need attention`() {
        assertEquals(
            WatchStatus.NeedsAttention(watchName = NAME, nearby = false, issue = WatchIssue.CapturesNotSent(count = 2)),
            resolve(outOfRange, reporting(WatchState(deadLetterCount = 2), WearFeatures.RECEIPTS)),
        )
    }

    @Test
    fun `a lost link comes before moments that did not send`() {
        val both = WatchState(link = PeerLinkState.NEEDS_RELINK, deadLetterCount = 2)

        assertEquals(
            WatchStatus.NeedsAttention(watchName = NAME, nearby = true, issue = WatchIssue.SetUpAgain),
            resolve(connected, reporting(both, WearFeatures.LINK_CLOUD, WearFeatures.RECEIPTS)),
        )
    }

    private companion object {
        const val NAME = "Pixel Watch"
    }
}
