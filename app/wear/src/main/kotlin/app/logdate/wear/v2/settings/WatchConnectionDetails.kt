package app.logdate.wear.v2.settings

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.wear.v2.phone.PhoneLink
import kotlin.time.Instant

/** Transport and a stored authenticated LogDate audience are separate from upload completion. */
data class WatchConnectionDetails(
    val phone: PhoneLink = PhoneLink.Unknown,
    val verifiedPhone: Boolean = false,
    val linkedPhoneOffline: Boolean = false,
    val accountLinked: Boolean = false,
    val pairedThroughPhone: Boolean = false,
    val pending: Int? = null,
    val lastSync: Instant? = null,
)

/** [current] is supplied only by WatchDeliveryPeer.current, which checks the active identity scope. */
fun watchConnectionDetails(
    phone: PhoneLink,
    current: PeerDeliveryContext?,
): WatchConnectionDetails {
    val peer = current?.takeIf { it.isValid() }
    val reachable = phone as? PhoneLink.Connected
    return WatchConnectionDetails(
        phone = phone,
        verifiedPhone = peer != null && reachable?.nodeId == peer.phoneNode,
        linkedPhoneOffline = peer != null && reachable?.nodeId != peer.phoneNode,
        accountLinked = peer?.mode == LinkMode.CLOUD,
        pairedThroughPhone = peer?.mode == LinkMode.PAIR,
    )
}
