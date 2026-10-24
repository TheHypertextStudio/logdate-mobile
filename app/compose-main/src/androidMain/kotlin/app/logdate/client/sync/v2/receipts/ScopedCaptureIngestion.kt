package app.logdate.client.sync.v2.receipts

import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordEnvelopeCodec
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.peer.delivery.CaptureIdentity
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import java.io.InputStream

/** Binds each event to its current source and keeps overlapping work on the same per-link ingestor. */
class ScopedCaptureIngestion(
    private val peers: PhoneDeliveryPeers,
    private val removals: WatchCaptureRemovals? = null,
    private val moments: WatchMomentIngestor? = null,
    private val create: (PeerDeliveryContext, String?) -> WatchRecordIngestorV2,
) {
    private class Lease(
        val ingestor: WatchRecordIngestorV2,
        var users: Int = 0,
    )

    private val leases = mutableMapOf<Pair<PeerDeliveryContext, String?>, Lease>()

    suspend fun onRecord(
        sourceNodeId: String?,
        path: String,
        data: Map<String, String>,
    ) {
        val source = sourceNodeId ?: return
        val peer = peers.current(source) ?: return
        val envelope = (EnvelopeDataMap.fromDataMap(data) as? Decoded.Supported)?.value ?: return
        if (envelope.context != peer) return
        val record = WearPaths.parseWatchRecord(path) ?: return
        if (record.linkId != null && record.linkId != peer.linkId) return
        if (record.kind != envelope.kind ||
            record.id.toString() != envelope.id ||
            (
                record.operationId != null &&
                    record.operationId != envelope.operationId
            )
        ) {
            return
        }
        if (envelope.operationId != null && CaptureIdentity.of(envelope.id, envelope.operationId) == null) return
        if (envelope.kind == RecordKind.MOMENT) {
            moments?.onRecord(source, path, data)
            return
        }
        if (envelope.removed) {
            removals?.remove(peer, envelope)
            return
        }
        if (removals?.isRemoved(peer, record.id) == true) return
        using(peer, envelope.operationId) { ingestor ->
            val unbound = data + (EnvelopeDataMap.KEY_ENVELOPE to RecordEnvelopeCodec.encode(envelope.copy(context = null)))
            ingestor.onRecord(WearPaths.watchRecord(record.kind, record.id), unbound)
        }
    }

    suspend fun onMedia(
        sourceNodeId: String?,
        path: String,
        input: InputStream,
    ) {
        val source = sourceNodeId ?: return
        val peer = peers.current(source) ?: return
        val record = WearPaths.parseRecordMedia(path) ?: return
        if (record.linkId != peer.linkId || removals?.isRemoved(peer, record.id) == true) return
        using(peer, record.operationId) { it.onMedia(WearPaths.recordMedia(record.kind, record.id), input) }
    }

    private suspend fun using(
        peer: PeerDeliveryContext,
        operationId: String?,
        block: suspend (WatchRecordIngestorV2) -> Unit,
    ) {
        val key = peer to operationId
        val lease = synchronized(leases) { leases.getOrPut(key) { Lease(create(peer, operationId)) }.also { it.users++ } }
        try {
            if (peers.isCurrent(peer)) block(lease.ingestor)
        } finally {
            synchronized(leases) { if (--lease.users == 0) leases.remove(key) }
        }
    }
}
