package app.logdate.wear.sync.v2.delivery

import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.Receipt
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.datalayer.v2.ReceiptsResponse
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.peer.delivery.CaptureIdentity
import app.logdate.client.sync.peer.delivery.DeliveryDirection
import app.logdate.client.sync.peer.delivery.DeliveryState
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import io.github.aakira.napier.Napier
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** Applies the phone's receipts, pushed or answered, to the watch's ledger. Receipts advance only their typed operation. */
class WatchReceiptApplier(
    private val ledger: PeerDeliveryLedger,
    private val peers: WatchDeliveryPeer,
) {
    private data class VerifiedCloud(
        val peer: PeerDeliveryContext,
        val cloud: CloudPresence,
    )

    private val lastCloud = AtomicReference<VerifiedCloud?>(null)

    /** A current-link routing hint; settlement still requires a receipt for the captured version. */
    suspend fun phoneCloud(): CloudPresence? {
        val cached = lastCloud.get() ?: return null
        if (!peers.isCurrent(cached.peer)) {
            lastCloud.compareAndSet(cached, null)
            return null
        }
        return lastCloud.get()?.takeIf { it.peer == cached.peer }?.cloud
    }

    /** Only the current source and exchange may advance their own delivery history. */
    suspend fun apply(
        sourceNodeId: String?,
        payload: ByteArray,
    ): Int {
        val beforeCloud = lastCloud.get()
        val peer = peers.current() ?: return 0
        if (sourceNodeId != peer.phoneNode) return 0
        val response =
            when (val decoded = WearJson.decode(payload, WearProtocol.RECEIPTS_SCHEMA, ReceiptsResponse.serializer())) {
                is Decoded.Supported -> {
                    decoded.value
                }

                is Decoded.Unsupported -> {
                    Napier.w("The phone's receipts use schema ${decoded.schema}; the watch waits and asks again")
                    return 0
                }

                is Decoded.Malformed -> {
                    Napier.e("The phone's receipts could not be read: ${decoded.reason}; the watch asks again")
                    return 0
                }
            }
        if (response.context != peer || !validCloud(peer, response.cloud) || !peers.isCurrent(peer)) return 0
        var applied = 0
        for (receipt in response.receipts) {
            if (!peers.isCurrent(peer)) return 0
            if (applyOne(peer, receipt)) applied++
        }
        if (!peers.isCurrent(peer)) return 0
        lastCloud.compareAndSet(beforeCloud, VerifiedCloud(peer, response.cloud))
        return applied
    }

    private fun validCloud(
        peer: PeerDeliveryContext,
        cloud: CloudPresence,
    ): Boolean =
        when (cloud) {
            CloudPresence.UNKNOWN -> true
            CloudPresence.NO_ACCOUNT -> peer.mode == LinkMode.PAIR
            CloudPresence.SIGNED_IN -> peer.mode == LinkMode.CLOUD
        }

    private suspend fun applyOne(
        peer: PeerDeliveryContext,
        receipt: Receipt,
    ): Boolean {
        val type =
            when (receipt.kind) {
                RecordKind.NOTE -> EntityType.NOTE
                RecordKind.MOMENT -> if (receipt.operationId != null) EntityType.MOMENT else return false
                else -> return false
            }
        if (receipt.state == ReceiptState.BACKED_UP && (receipt.serverVersion ?: 0L) <= 0L) {
            Napier.w("Ignoring a backup receipt with no positive server version")
            return false
        }
        if (Uuid.parseOrNull(receipt.id)?.toString() != receipt.id) return false
        val operation = receipt.operationId
        if (receipt.removed &&
            (receipt.state != ReceiptState.DELETED_ON_PHONE || receipt.contentVersion != 0L || operation == null)
        ) {
            return false
        }
        val identity = operation?.let { CaptureIdentity.of(receipt.id, it) ?: return false }
        val key = if (receipt.removed) identity!!.removalLedgerKey else identity?.ledgerKey ?: receipt.id
        val moved =
            ledger.applyReceipt(
                scope = peer.ledgerScope,
                direction = DeliveryDirection.TO_PHONE,
                entityType = type,
                entityId = key,
                receiptVersion = receipt.contentVersion,
                state = DeliveryState.of(receipt.state),
            )
        return moved != null
    }
}
