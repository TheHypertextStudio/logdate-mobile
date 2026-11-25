package app.logdate.client.watch

import app.logdate.client.sync.v2.link.PhoneLinkFailure
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchLinking

private const val FALLBACK_WATCH_NAME = "Wear OS watch"

/** Snapshot of a Wear node used by the phone-side watch connection resolver. */
data class WatchNodeSnapshot(
    val id: String,
    val displayName: String,
    val isNearby: Boolean,
)

/** Combined view of connected Wear nodes and nodes advertising the LogDate watch capability. */
data class WatchTransportSnapshot(
    val connectedNodes: List<WatchNodeSnapshot> = emptyList(),
    val appNodes: List<WatchNodeSnapshot> = emptyList(),
    val available: Boolean = true,
    val knownAppNodes: List<WatchNodeSnapshot> = emptyList(),
)

/** Phone-side association status reported by Companion Device Manager. */
sealed interface WatchAssociationSnapshot {
    data object Unsupported : WatchAssociationSnapshot

    data object Unassociated : WatchAssociationSnapshot

    data object Pending : WatchAssociationSnapshot

    data class Associated(
        val displayName: String? = null,
        val macAddress: String? = null,
        val nodeId: String? = null,
        val associationId: Int? = null,
    ) : WatchAssociationSnapshot
}

/**
 * Resolves the user-facing watch connection state by combining Companion Device
 * association status with the current Wear transport snapshot.
 */
fun resolveWatchConnectionState(
    association: WatchAssociationSnapshot,
    transport: WatchTransportSnapshot,
): WatchConnectionState =
    if (!transport.available) {
        WatchConnectionState.Unavailable
    } else {
        when (association) {
            WatchAssociationSnapshot.Unsupported -> {
                resolveLegacyWatchConnectionState(transport)
            }

            WatchAssociationSnapshot.Pending -> {
                WatchConnectionState.AssociationPending
            }

            is WatchAssociationSnapshot.Associated -> {
                resolveAssociatedWatchConnectionState(association, transport)
            }

            WatchAssociationSnapshot.Unassociated -> {
                val connectedWatch = transport.connectedNodes.firstOrNull()
                if (connectedWatch == null) {
                    WatchConnectionState.NoPairedWatch
                } else {
                    WatchConnectionState.NeedsAssociation(connectedWatch.displayName, connectedWatch.id)
                }
            }
        }
    }

private fun resolveLegacyWatchConnectionState(transport: WatchTransportSnapshot): WatchConnectionState {
    val connectedWatch = transport.connectedNodes.firstOrNull() ?: return WatchConnectionState.NoPairedWatch
    val installedNodes = installedConnectedNodes(transport)
    if (installedNodes.isEmpty()) {
        return WatchConnectionState.AppUnknown(
            connectedWatch.displayName,
            connectedWatch.id,
            transport.knownAppNodes.any {
                it.id ==
                    connectedWatch.id
            },
        )
    }
    val watch = installedNodes.first()
    return WatchConnectionState.Connected(watch.displayName, watch.id)
}

/** Resolves watch state once LogDate already has an app-level companion association. */
private fun resolveAssociatedWatchConnectionState(
    association: WatchAssociationSnapshot.Associated,
    transport: WatchTransportSnapshot,
): WatchConnectionState {
    val boundTransport =
        association.nodeId?.let { node ->
            transport.copy(connectedNodes = transport.connectedNodes.filter { it.id == node })
        } ?: transport
    val installedNodes = installedConnectedNodes(boundTransport)
    val connectedWatch = boundTransport.connectedNodes.firstOrNull()
    val watchName =
        installedNodes.firstOrNull()?.displayName ?: connectedWatch?.displayName ?: association.displayName ?: FALLBACK_WATCH_NAME

    if (connectedWatch == null) return WatchConnectionState.OutOfRange(watchName, association.nodeId)
    if (installedNodes.isEmpty()) {
        return WatchConnectionState.AppUnknown(
            watchName,
            connectedWatch.id,
            transport.knownAppNodes.any {
                it.id ==
                    connectedWatch.id
            },
        )
    }
    val watch = installedNodes.first()
    return WatchConnectionState.Connected(watch.displayName, watch.id)
}

/** Returns the connected nodes that also advertise the LogDate watch capability. */
private fun installedConnectedNodes(transport: WatchTransportSnapshot): List<WatchNodeSnapshot> {
    val installedNodeIds = transport.appNodes.map(WatchNodeSnapshot::id).toSet()
    return transport.connectedNodes.filter { it.id in installedNodeIds }
}

/** Wear app pairing is verified by the same exact peer audience used for captures and playback. */
fun resolveWearAppConnection(
    transport: WatchTransportSnapshot,
    verifiedNodes: Set<String>,
): WatchConnectionState {
    if (!transport.available) return WatchConnectionState.Unavailable
    val watch =
        transport.connectedNodes.firstOrNull { it.id in verifiedNodes }
            ?: transport.connectedNodes.firstOrNull()
    if (watch == null) {
        val known = (transport.appNodes + transport.knownAppNodes).firstOrNull { it.id in verifiedNodes }
        val id = known?.id ?: verifiedNodes.firstOrNull()
        return if (id != null) {
            WatchConnectionState.OutOfRange(known?.displayName ?: FALLBACK_WATCH_NAME, id)
        } else {
            WatchConnectionState.NoPairedWatch
        }
    }
    if (watch.id in verifiedNodes) return WatchConnectionState.Connected(watch.displayName, watch.id)
    if (transport.appNodes.none { it.id == watch.id }) {
        return WatchConnectionState.AppUnknown(
            watch.displayName,
            watch.id,
            transport.knownAppNodes.any {
                it.id ==
                    watch.id
            },
        )
    }
    // LogDate links a watch that has the app by itself; the system's pairing prompt is never needed.
    return WatchConnectionState.NeedsAssociation(watch.displayName, watch.id, WatchLinking())
}

/** What the Watch page can say about a failed link, in terms of what the person can do about it. */
fun PhoneLinkFailure.toWatchLinkProblem(): WatchLinkProblem =
    when (this) {
        PhoneLinkFailure.WATCH_BOUND_TO_ANOTHER_ACCOUNT -> WatchLinkProblem.ANOTHER_ACCOUNT

        PhoneLinkFailure.DEVICE_LIMIT_REACHED -> WatchLinkProblem.DEVICE_LIMIT

        PhoneLinkFailure.WATCH_KEY_CONFLICT, PhoneLinkFailure.WATCH_INSTALL_FAILED -> WatchLinkProblem.SET_UP_AGAIN

        // SIGNED_OUT means the phone's account changed or signed out while a link was being prepared; the next
        // attempt plans for whatever account the phone has then, so it passes like the transport failures do.
        PhoneLinkFailure.PHONE_NODE_UNKNOWN,
        PhoneLinkFailure.WATCH_UNREACHABLE,
        PhoneLinkFailure.WATCH_CANNOT_LINK,
        PhoneLinkFailure.OFFER_EXPIRED,
        PhoneLinkFailure.ANSWER_UNREADABLE,
        PhoneLinkFailure.CONFIRMATION_MISMATCH,
        PhoneLinkFailure.BUNDLE_UNAVAILABLE,
        PhoneLinkFailure.SIGNED_OUT,
        PhoneLinkFailure.LINK_CODE_UNAVAILABLE,
        PhoneLinkFailure.KEYS_UNAVAILABLE,
        -> WatchLinkProblem.RETRYING
    }
