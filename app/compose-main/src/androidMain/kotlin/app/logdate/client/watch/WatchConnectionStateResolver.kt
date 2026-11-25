package app.logdate.client.watch

import app.logdate.client.sync.v2.link.PhoneLinkFailure
import app.logdate.feature.core.settings.ui.watch.DiscoveredWatch
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
    selectedNodeId: String? = null,
): WatchConnectionState =
    if (!transport.available) {
        WatchConnectionState.Unavailable
    } else {
        when (association) {
            WatchAssociationSnapshot.Unsupported -> {
                resolveLegacyWatchConnectionState(transport, selectedNodeId)
            }

            WatchAssociationSnapshot.Pending -> {
                WatchConnectionState.AssociationPending
            }

            is WatchAssociationSnapshot.Associated -> {
                resolveAssociatedWatchConnectionState(association, transport)
            }

            WatchAssociationSnapshot.Unassociated -> {
                val discovered = transport.discoveredWatches()
                val connectedWatch = selectedOrFirst(transport.connectedNodes, selectedNodeId)
                if (connectedWatch == null) {
                    WatchConnectionState.NoPairedWatch
                } else {
                    WatchConnectionState.NeedsAssociation(
                        watchName = connectedWatch.displayName,
                        nodeId = connectedWatch.id,
                        isNearby = connectedWatch.isNearby,
                        availableWatches = discovered,
                    )
                }
            }
        }
    }

private fun resolveLegacyWatchConnectionState(
    transport: WatchTransportSnapshot,
    selectedNodeId: String?,
): WatchConnectionState {
    val installedNodes = installedConnectedNodes(transport)
    val connectedWatch =
        selectedOrFirst(transport.connectedNodes, selectedNodeId)
            ?: return WatchConnectionState.NoPairedWatch
    val discovered = transport.discoveredWatches()
    val installedWatch = installedNodes.firstOrNull { it.id == connectedWatch.id }
    if (installedWatch != null) {
        return WatchConnectionState.Connected(installedWatch.displayName, installedWatch.id)
    }
    if (installedNodes.isNotEmpty() && selectedNodeId == null) {
        val watch = installedNodes.first()
        return WatchConnectionState.Connected(watch.displayName, watch.id)
    }
    return WatchConnectionState.AppUnknown(
        watchName = connectedWatch.displayName,
        nodeId = connectedWatch.id,
        knownInstalled = transport.knownAppNodes.any { it.id == connectedWatch.id },
        isNearby = connectedWatch.isNearby,
        availableWatches = discovered,
    )
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
            watchName = watchName,
            nodeId = connectedWatch.id,
            knownInstalled = transport.knownAppNodes.any { it.id == connectedWatch.id },
            isNearby = connectedWatch.isNearby,
            availableWatches = transport.discoveredWatches(),
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

private fun WatchTransportSnapshot.discoveredWatches(): List<DiscoveredWatch> {
    val knownIds = (appNodes + knownAppNodes).mapTo(mutableSetOf()) { it.id }
    return connectedNodes.map { node ->
        DiscoveredWatch(
            nodeId = node.id,
            displayName = node.displayName,
            isNearby = node.isNearby,
            appInstalled = node.id in knownIds,
        )
    }
}

private fun selectedOrFirst(
    nodes: List<WatchNodeSnapshot>,
    selectedNodeId: String?,
): WatchNodeSnapshot? = selectedNodeId?.let { id -> nodes.firstOrNull { it.id == id } } ?: nodes.firstOrNull()

/** Wear app pairing is verified by the same exact peer audience used for captures and playback. */
fun resolveWearAppConnection(
    transport: WatchTransportSnapshot,
    verifiedNodes: Set<String>,
    selectedNodeId: String? = null,
): WatchConnectionState {
    if (!transport.available) return WatchConnectionState.Unavailable
    val discovered = transport.discoveredWatches()
    val preferred = selectedNodeId?.let { id -> transport.connectedNodes.firstOrNull { it.id == id } }
    val watch =
        preferred
            ?: transport.connectedNodes.firstOrNull { it.id in verifiedNodes }
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
            watchName = watch.displayName,
            nodeId = watch.id,
            knownInstalled = transport.knownAppNodes.any { it.id == watch.id },
            isNearby = watch.isNearby,
            availableWatches = discovered,
        )
    }
    // LogDate links a watch that has the app by itself; the system's pairing prompt is never needed.
    return WatchConnectionState.NeedsAssociation(
        watchName = watch.displayName,
        nodeId = watch.id,
        linking = WatchLinking(),
        isNearby = watch.isNearby,
        availableWatches = discovered,
    )
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
