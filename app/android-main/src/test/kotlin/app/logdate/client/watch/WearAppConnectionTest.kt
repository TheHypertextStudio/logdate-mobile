package app.logdate.client.watch

import app.logdate.client.sync.v2.link.PhoneLinkFailure
import app.logdate.feature.core.settings.ui.watch.DiscoveredWatch
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchLinking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WearAppConnectionTest {
    private val watch = WatchNodeSnapshot("watch-a", "Pixel Watch", true)
    private val discovered = listOf(DiscoveredWatch("watch-a", "Pixel Watch", isNearby = true, appInstalled = true))
    private val transport = WatchTransportSnapshot(listOf(watch), listOf(watch))

    @Test
    fun `reachable installed app still requires verified link`() {
        assertEquals(
            WatchConnectionState.NeedsAssociation("Pixel Watch", "watch-a", WatchLinking(), availableWatches = discovered),
            resolveWearAppConnection(transport, emptySet()),
        )
    }

    @Test
    fun `verified exact peer is connected without manufacturer CDM privileges`() {
        assertEquals(WatchConnectionState.Connected("Pixel Watch", "watch-a"), resolveWearAppConnection(transport, setOf("watch-a")))
    }

    @Test
    fun `another watch cannot satisfy selected peers link`() {
        assertEquals(
            WatchConnectionState.NeedsAssociation("Pixel Watch", "watch-a", WatchLinking(), availableWatches = discovered),
            resolveWearAppConnection(transport, setOf("watch-b")),
        )
    }

    @Test
    fun `installed watch never becomes an install prompt when capability disappears`() {
        val missingAdvertisement = WatchTransportSnapshot(connectedNodes = listOf(watch))
        assertEquals(
            WatchConnectionState.Connected("Pixel Watch", "watch-a"),
            resolveWearAppConnection(missingAdvertisement, setOf("watch-a")),
        )
    }

    @Test
    fun `verified disconnected watch remains known instead of returning to setup`() {
        val disconnected = WatchTransportSnapshot(appNodes = listOf(watch))
        assertEquals(
            WatchConnectionState.OutOfRange("Pixel Watch", "watch-a"),
            resolveWearAppConnection(disconnected, setOf("watch-a")),
        )
    }

    @Test
    fun `missing advertisement is not proof an app is absent`() {
        val unconfirmed = WatchTransportSnapshot(connectedNodes = listOf(watch))
        assertFalse(resolveWearAppConnection(unconfirmed, emptySet()) is WatchConnectionState.AppNotInstalled)
    }

    @Test
    fun `only failures the person can act on get their own explanation`() {
        assertEquals(WatchLinkProblem.ANOTHER_ACCOUNT, PhoneLinkFailure.WATCH_BOUND_TO_ANOTHER_ACCOUNT.toWatchLinkProblem())
        assertEquals(WatchLinkProblem.DEVICE_LIMIT, PhoneLinkFailure.DEVICE_LIMIT_REACHED.toWatchLinkProblem())
        assertEquals(WatchLinkProblem.SET_UP_AGAIN, PhoneLinkFailure.WATCH_KEY_CONFLICT.toWatchLinkProblem())
        assertEquals(WatchLinkProblem.SET_UP_AGAIN, PhoneLinkFailure.WATCH_INSTALL_FAILED.toWatchLinkProblem())
        assertEquals(WatchLinkProblem.RETRYING, PhoneLinkFailure.WATCH_UNREACHABLE.toWatchLinkProblem())
        assertEquals(WatchLinkProblem.RETRYING, PhoneLinkFailure.SIGNED_OUT.toWatchLinkProblem())
    }
}
