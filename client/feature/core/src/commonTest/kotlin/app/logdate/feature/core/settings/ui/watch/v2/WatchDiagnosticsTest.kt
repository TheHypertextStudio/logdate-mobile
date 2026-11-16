package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsResponse
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.WatchState
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchPeerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchDiagnosticsTest {
    private val snapshot =
        WatchDiagnosticSnapshot(
            WatchConnectionState.Connected("Watch", "watch-node"),
            WatchPeerSnapshot(true, WatchState(link = PeerLinkState.PAIRED), setOf(WearFeatures.RECEIPTS, WearFeatures.LINK_CLOUD)),
            true,
            WatchDiagnosticsResponse(
                context = PeerDeliveryContext("key", "phone", "watch", LinkMode.PAIR),
                nonce = "echo",
                notificationsAllowed = true,
                microphoneAllowed = true,
            ),
        )

    @Test fun `a missing real node exposes no dependent diagnostic checks or repairs`() {
        assertEquals(emptyList(), WatchDiagnostics.evaluate(snapshot.copy(connection = WatchConnectionState.NoPairedWatch)))
        assertEquals(emptyList(), WatchDiagnostics.evaluate(snapshot.copy(connection = WatchConnectionState.Connected("Watch"))))
    }

    @Test fun `verified healthy snapshot passes all eight ordered checks`() {
        val checks = WatchDiagnostics.evaluate(snapshot)
        assertEquals(WatchDiagnosticCheck.entries, checks.map { it.check })
        assertEquals(List(8) { WatchDiagnosticOutcome.PASSED }, checks.map { it.outcome })
    }

    @Test fun `missing reply and unreported sync never claim success`() {
        val checks = WatchDiagnostics.evaluate(snapshot.copy(reply = null, peer = WatchPeerSnapshot.Inactive))
        assertEquals(WatchDiagnosticOutcome.FAILED, checks.single { it.check == WatchDiagnosticCheck.PING }.outcome)
        assertEquals(WatchDiagnosticOutcome.FAILED, checks.single { it.check == WatchDiagnosticCheck.SYNC }.outcome)
        assertEquals(WatchDiagnosticOutcome.FAILED, checks.single { it.check == WatchDiagnosticCheck.NOTIFICATIONS }.outcome)
    }

    @Test fun `local rollout alone does not verify an unreported watch protocol`() {
        val checks = WatchDiagnostics.evaluate(snapshot.copy(peer = snapshot.peer.copy(watch = null), reply = null))
        assertEquals(WatchDiagnosticOutcome.FIXABLE, checks.single { it.check == WatchDiagnosticCheck.PROTOCOL }.outcome)
    }

    @Test fun `pending captures and disabled notifications offer actual repair actions`() {
        val checks =
            WatchDiagnostics.evaluate(
                snapshot.copy(
                    peer = snapshot.peer.copy(watch = snapshot.peer.watch!!.copy(outboxCount = 2)),
                    reply = snapshot.reply!!.copy(notificationsAllowed = false),
                ),
            )
        assertEquals(WatchDiagnosticFix.SYNC, checks.single { it.check == WatchDiagnosticCheck.SYNC }.fix)
        assertEquals(WatchDiagnosticFix.OPEN_WATCH, checks.single { it.check == WatchDiagnosticCheck.NOTIFICATIONS }.fix)
    }

    @Test fun `an installed watch with no reply never recommends install or update`() {
        val checks = WatchDiagnostics.evaluate(snapshot.copy(reply = null, peer = WatchPeerSnapshot.Inactive))
        assertTrue(checks.none { it.fix == WatchDiagnosticFix.INSTALL || it.fix == WatchDiagnosticFix.UPDATE })
        assertEquals(WatchDiagnosticFix.OPEN_WATCH, checks.single { it.check == WatchDiagnosticCheck.PROTOCOL }.fix)
    }

    @Test fun `unknown installation cannot be inferred from an absent reply`() {
        val checks =
            WatchDiagnostics.evaluate(
                snapshot.copy(connection = WatchConnectionState.AppUnknown("Watch", "watch-node"), reply = null),
            )
        assertTrue(checks.none { it.fix == WatchDiagnosticFix.INSTALL || it.fix == WatchDiagnosticFix.UPDATE })
    }
}
