package app.logdate.wear.v2.settings

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.wear.v2.phone.PhoneLink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchConnectionDetailsTest {
    private val phone = PhoneLink.Connected("phone", "My phone")
    private val pair = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 7 }), "phone", "watch", LinkMode.PAIR)

    @Test fun `phone reachability alone proves neither peer nor account connection`() {
        val state = watchConnectionDetails(phone, null)
        assertFalse(state.verifiedPhone)
        assertFalse(state.accountLinked)
    }

    @Test fun `verified pair is not misrepresented as an independent account link`() {
        val state = watchConnectionDetails(phone, pair)
        assertTrue(state.verifiedPhone)
        assertTrue(state.pairedThroughPhone)
        assertFalse(state.accountLinked)
    }

    @Test fun `offline verified peer retains link evidence without claiming connectivity`() {
        val state = watchConnectionDetails(PhoneLink.NotConnected, pair)
        assertTrue(state.linkedPhoneOffline)
        assertFalse(state.verifiedPhone)
    }

    @Test fun `a different reachable phone cannot satisfy the saved phone link`() {
        val state = watchConnectionDetails(PhoneLink.Connected("other", "Same display name"), pair)
        assertFalse(state.verifiedPhone)
        assertTrue(state.linkedPhoneOffline)
    }

    @Test fun `only an authenticated valid cloud audience supplies account link evidence`() {
        val cloud = pair.copy(mode = LinkMode.CLOUD, accountId = "account", serverOrigin = "https://cloud.logdate.app")
        assertTrue(watchConnectionDetails(phone, cloud).accountLinked)
        assertFalse(watchConnectionDetails(phone, cloud.copy(accountId = null)).accountLinked)
    }
}
