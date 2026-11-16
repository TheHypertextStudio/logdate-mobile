package app.logdate.feature.core.settings.ui.watch

import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.StateDocument
import app.logdate.client.sync.peer.state.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests [PeerStateWatchSnapshotSource], which reads the watch's report from the peer state the phone received. */
class PeerStateWatchSnapshotSourceTest {
    private val peerStates = PeerStateStore()
    private val flag = MutableStateFlow(true)
    private val source =
        PeerStateWatchSnapshotSource(
            gate =
                WearProtocolGate(
                    flagEnabled = flag,
                    peerHasV2Capability = MutableStateFlow(true),
                    peerFeatures = peerStates.features(PeerRole.WATCH),
                ),
            peerStates = peerStates,
        )

    @Test
    fun `before the watch reports anything there is no report`() =
        runTest {
            val snapshot = source.observe().first()

            assertTrue(snapshot.v2Active)
            assertNull(snapshot.watch)
            assertEquals(emptySet(), snapshot.features)
        }

    @Test
    fun `the watch report and its features reach the snapshot`() =
        runTest {
            val watch = WatchState(outboxCount = 2, features = setOf(WearFeatures.RECEIPTS))
            peerStates.accept(
                "watch-node",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 1, watch = watch)),
                PeerRole.WATCH,
            )

            val snapshot = source.observe().first()

            assertEquals(2, snapshot.watch?.outboxCount)
            assertEquals(setOf(WearFeatures.RECEIPTS), snapshot.features)
        }

    @Test
    fun `with the Labs switch off version 2 is inactive`() =
        runTest {
            flag.value = false

            assertFalse(source.observe().first().v2Active)
        }

    @Test
    fun `a newer report from another watch cannot replace the selected watch`() =
        runTest {
            val selected = WatchState(outboxCount = 2, features = setOf(WearFeatures.RECEIPTS))
            val other = WatchState(outboxCount = 99, features = setOf(WearFeatures.WINDOW))
            peerStates.accept(
                "selected",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 1, watch = selected)),
                PeerRole.WATCH,
            )
            peerStates.accept(
                "other",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 2, watch = other)),
                PeerRole.WATCH,
            )
            val snapshot = source.observeFor("selected").first()
            assertEquals(2, snapshot.watch?.outboxCount)
            assertEquals(setOf(WearFeatures.RECEIPTS), snapshot.features)
        }

    @Test
    fun `a previous link report never supplies current setup or sync claims`() =
        runTest {
            val retained = StateDocument(role = PeerRole.WATCH, writtenAt = 1, watch = WatchState(outboxCount = 9))
            peerStates.accept("selected", Decoded.Supported(retained), PeerRole.WATCH)
            val scoped =
                PeerStateWatchSnapshotSource(
                    gate = WearProtocolGate(flag, MutableStateFlow(true), peerStates.features(PeerRole.WATCH)),
                    peerStates = peerStates,
                    accepts = { _, _ -> false },
                )
            assertNull(scoped.observeFor("selected").first().watch)
            assertEquals(emptySet(), scoped.observeFor("selected").first().features)
        }
}
