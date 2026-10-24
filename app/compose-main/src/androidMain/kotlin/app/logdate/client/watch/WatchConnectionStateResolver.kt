package app.logdate.client.watch

import app.logdate.feature.core.settings.ui.watch.WatchConnectionState

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

private fun resolveLegacyWatchConnectionState(transport: WatchTransportSnapshot): WatchConnectionState {
    val connectedWatch = transport.connectedNodes.firstOrNull() ?: return WatchConnectionState.NoPairedWatch
    val installedNodes = installedConnectedNodes(transport)
    if (installedNodes.isEmpty()) {
        return WatchConnectionState.AppNotInstalled(connectedWatch.displayName, connectedWatch.id)
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
    if (installedNodes.isEmpty()) return WatchConnectionState.AppNotInstalled(watchName, connectedWatch.id)
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
    val watch =
        transport.connectedNodes.firstOrNull { it.id in verifiedNodes }
            ?: transport.connectedNodes.firstOrNull() ?: return WatchConnectionState.NoPairedWatch
    if (transport.appNodes.none { it.id == watch.id }) return WatchConnectionState.AppNotInstalled(watch.displayName, watch.id)
    if (watch.id !in verifiedNodes) return WatchConnectionState.NeedsAssociation(watch.displayName, watch.id)
    return WatchConnectionState.Connected(watch.displayName, watch.id)
}
