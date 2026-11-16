package app.logdate.client.watch

import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the logic for resolving the unified watch connection state.
 *
 * This class verifies how disparate signals—such as system-level device association,
 * Bluetooth connectivity, and app installation status—are mapped into a single,
 * high-level state used to drive the wearable settings and status UI.
 */
class WatchConnectionStateResolverTest {
    @Test
    fun `connected associated watch resolves to connected`() {
        val state =
            resolveWatchConnectionState(
                association =
                    WatchAssociationSnapshot.Associated(
                        displayName = "Pixel Watch",
                    ),
                transport =
                    WatchTransportSnapshot(
                        connectedNodes =
                            listOf(
                                WatchNodeSnapshot(
                                    id = "watch-1",
                                    displayName = "Pixel Watch",
                                    isNearby = true,
                                ),
                            ),
                        appNodes =
                            listOf(
                                WatchNodeSnapshot(
                                    id = "watch-1",
                                    displayName = "Pixel Watch",
                                    isNearby = true,
                                ),
                            ),
                    ),
            )

        assertEquals(
            WatchConnectionState.Connected(watchName = "Pixel Watch", nodeId = "watch-1"),
            state,
        )
    }

    @Test
    fun `connected unassociated watch resolves to needs association`() {
        val state =
            resolveWatchConnectionState(
                association = WatchAssociationSnapshot.Unassociated,
                transport =
                    WatchTransportSnapshot(
                        connectedNodes =
                            listOf(
                                WatchNodeSnapshot(
                                    id = "watch-1",
                                    displayName = "Galaxy Watch",
                                    isNearby = true,
                                ),
                            ),
                    ),
            )

        assertEquals(
            WatchConnectionState.NeedsAssociation("Galaxy Watch", nodeId = "watch-1"),
            state,
        )
    }

    @Test
    fun `associated watch without reachable node resolves to out of range`() {
        val state =
            resolveWatchConnectionState(
                association = WatchAssociationSnapshot.Associated(displayName = "Pixel Watch"),
                transport = WatchTransportSnapshot(),
            )

        assertEquals(
            WatchConnectionState.OutOfRange(watchName = "Pixel Watch"),
            state,
        )
    }

    @Test
    fun `legacy unsupported association falls back to app install state`() {
        val state =
            resolveWatchConnectionState(
                association = WatchAssociationSnapshot.Unsupported,
                transport =
                    WatchTransportSnapshot(
                        connectedNodes =
                            listOf(
                                WatchNodeSnapshot(
                                    id = "watch-1",
                                    displayName = "Pixel Watch",
                                    isNearby = true,
                                ),
                            ),
                        appNodes = emptyList(),
                    ),
            )

        assertEquals(
            WatchConnectionState.AppUnknown("Pixel Watch", nodeId = "watch-1"),
            state,
        )
    }

    @Test
    fun `reachable cloud watch keeps its identity with multiple watches`() {
        val first = WatchNodeSnapshot("watch-a", "Watch A", false)
        val second = WatchNodeSnapshot("watch-b", "Watch B", true)
        val state =
            resolveWatchConnectionState(
                WatchAssociationSnapshot.Associated(),
                WatchTransportSnapshot(listOf(first, second), listOf(first, second)),
            )
        assertEquals(WatchConnectionState.Connected("Watch A", "watch-a"), state)
    }

    @Test
    fun `verified watch reachable through cloud is connected`() {
        val cloudWatch = WatchNodeSnapshot("watch-cloud", "Pixel Watch", false)
        val state =
            resolveWearAppConnection(
                WatchTransportSnapshot(listOf(cloudWatch), listOf(cloudWatch)),
                verifiedNodes = setOf(cloudWatch.id),
            )

        assertEquals(WatchConnectionState.Connected("Pixel Watch", "watch-cloud"), state)
    }

    @Test
    fun `pending association always resolves to pending state`() {
        val state =
            resolveWatchConnectionState(
                association = WatchAssociationSnapshot.Pending,
                transport =
                    WatchTransportSnapshot(
                        connectedNodes =
                            listOf(
                                WatchNodeSnapshot(
                                    id = "watch-1",
                                    displayName = "Pixel Watch",
                                    isNearby = true,
                                ),
                            ),
                    ),
            )

        assertEquals(WatchConnectionState.AssociationPending, state)
    }
}
