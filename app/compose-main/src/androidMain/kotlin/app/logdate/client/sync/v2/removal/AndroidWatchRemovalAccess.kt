package app.logdate.client.sync.v2.removal

import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.DeviceLinkApiClient
import app.logdate.client.networking.DeviceLinkApiException
import app.logdate.client.networking.httpClient
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.peer.removal.WatchRemovalAccess
import app.logdate.client.sync.peer.removal.WatchRemovalCoordinator
import app.logdate.client.sync.peer.removal.WatchRemovalProtocol
import app.logdate.client.sync.peer.removal.WatchRemovalReply
import app.logdate.client.sync.peer.removal.WatchRemovalRequest
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.client.sync.peer.removal.WatchRemovalTarget
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.v2.link.PhoneWatchLinkStore
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.shared.config.PinnedLogDateConfigRepository
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class AndroidWatchRemovalAccess(
    private val context: Context,
    private val manager: WatchConnectionManager,
    private val peers: PhoneDeliveryPeers,
    private val states: PeerStateStore,
    private val links: PhoneWatchLinkStore,
    private val sessions: SessionStorage,
    private val associations: app.logdate.client.watch.WatchAssociationBindings,
) : WatchRemovalAccess {
    private val coordinator =
        WatchRemovalCoordinator(
            current = peers::isCurrentRemoval,
            request = ::request,
            deferWipe = ::deferWipe,
            revoke = ::revoke,
            disassociate = ::disassociate,
            forget = {
                links.forget(it.context.watchNode)
                true
            },
        )

    override suspend fun target(): WatchRemovalTarget? {
        val connection = manager.observeConnectionState().first { it != WatchConnectionState.Loading }
        val node = connection.nodeId ?: return null
        val peer = peers.forRemoval(node) ?: return null
        val record = links.get(node) ?: return null
        val state =
            states
                .documents(PeerRole.WATCH)
                .first()[node]
                ?.watch
                ?.takeIf { it.linkId == peer.linkId }
        return WatchRemovalTarget(peer, record.displayName, state?.outboxCount, state?.sessionId ?: record.sessionId)
    }

    override suspend fun targetForSession(sessionId: String): WatchRemovalTarget? {
        val nodes = links.observe().first().keys
        return nodes.firstNotNullOfOrNull { node -> linkedTarget(node)?.takeIf { it.sessionId == sessionId } }
    }

    override suspend fun removeAll(): Boolean {
        val records = links.observe().first()
        for (node in records.keys) {
            val target = linkedTarget(node) ?: return false
            if (remove(target) is WatchRemovalResult.Failed) return false
        }
        return true
    }

    override suspend fun unsentOnLinkedWatches(): List<Int?> =
        links
            .observe()
            .first()
            .keys
            .map { node -> linkedTarget(node)?.pending }

    /** The removal target for the watch linked as [node], whether or not it is in reach. */
    private suspend fun linkedTarget(node: String): WatchRemovalTarget? {
        val peer = peers.forRemoval(node) ?: return null
        val record = links.get(peer.watchNode) ?: return null
        val state =
            states
                .documents(PeerRole.WATCH)
                .first()[peer.watchNode]
                ?.watch
                ?.takeIf { it.linkId == peer.linkId }
        return WatchRemovalTarget(peer, record.displayName, state?.outboxCount, state?.sessionId ?: record.sessionId)
    }

    override suspend fun remove(target: WatchRemovalTarget): WatchRemovalResult =
        try {
            coordinator.remove(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Watch removal did not finish; the link is retained for retry", error)
            WatchRemovalResult.Failed("removal")
        }

    private suspend fun request(request: WatchRemovalRequest): WatchRemovalReply? =
        try {
            withTimeoutOrNull(10_000) {
                val payload =
                    Wearable
                        .getMessageClient(context)
                        .sendRequest(
                            request.context.watchNode,
                            WatchRemovalProtocol.PATH,
                            WearJson.encodeToBytes(WatchRemovalRequest.serializer(), request),
                        ).await()
                (WearJson.decode(payload, 1, WatchRemovalReply.serializer()) as? Decoded.Supported)?.value
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("Watch is unavailable; removal stopped before erasure could be confirmed", error)
            null
        }

    private suspend fun deferWipe(request: WatchRemovalRequest): Boolean =
        try {
            val path = "${WatchRemovalProtocol.PATH}/${request.context.watchNode}/${request.context.linkPathSegment}"
            val item = PutDataMapRequest.create(path)
            item.dataMap.putString("request", WearJson.encode(WatchRemovalRequest.serializer(), request))
            Wearable.getDataClient(context).putDataItem(item.asPutDataRequest().setUrgent()).await()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not queue watch erasure after revocation; retaining link for retry", error)
            false
        }

    private suspend fun revoke(target: WatchRemovalTarget): Boolean {
        if (target.context.mode == LinkMode.PAIR) return true
        val id = target.sessionId ?: return false
        val bound = sessions.getOriginBoundSession() ?: return false
        if (bound.origin != target.context.serverOrigin || bound.session.accountId != target.context.accountId) return false
        val client = DeviceLinkApiClient(httpClient, PinnedLogDateConfigRepository(bound.origin, null))
        val result = client.revoke(id, bound.session.accessToken)
        if (sessions.getOriginBoundSession() != bound) return false
        if (result.isSuccess) return true
        val error = result.exceptionOrNull() as? DeviceLinkApiException ?: return false
        if (error.status != 404) return false
        val remaining = client.listDevices(bound.session.accessToken).getOrNull() ?: return false
        return sessions.getOriginBoundSession() == bound && remaining.none { it.id == id }
    }

    @Suppress("DEPRECATION")
    private suspend fun disassociate(target: WatchRemovalTarget): Boolean {
        val platform = context.getSystemService(CompanionDeviceManager::class.java) ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val associations = platform.myAssociations
            if (associations.isEmpty()) return true
            val boundAssociationId = this.associations.current()[target.context.watchNode]?.associationId
            val exact =
                associations
                    .filter { association ->
                        boundAssociationId == association.id ||
                            association.deviceMacAddress?.toString()?.let { nodeForAddress(it) } == target.context.watchNode
                    }.singleOrNull() ?: return false
            platform.disassociate(exact.id)
            val removed = platform.myAssociations.none { it.id == exact.id }
            if (removed) this.associations.forget(target.context.watchNode)
            return removed
        }
        val addresses = platform.associations
        if (addresses.isEmpty()) return true
        val only = addresses.filter { nodeForAddress(it) == target.context.watchNode }.singleOrNull() ?: return false
        platform.disassociate(only)
        return only !in platform.associations
    }

    private suspend fun nodeForAddress(address: String): String? =
        try {
            withTimeoutOrNull(5_000) { Wearable.getNodeClient(context).getNodeId(address).await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("The companion association could not be matched to the selected watch", error)
            null
        }
}
