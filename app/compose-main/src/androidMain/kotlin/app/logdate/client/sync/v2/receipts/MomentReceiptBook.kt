package app.logdate.client.sync.v2.receipts

import app.logdate.client.database.dao.moments.MarkedMomentDao
import app.logdate.client.database.dao.sync.PeerDeliveryDao
import app.logdate.client.database.entities.moments.MarkedMomentState
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.Receipt
import app.logdate.client.sync.datalayer.v2.ReceiptQuery
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.CaptureIdentity
import kotlin.uuid.Uuid

class MomentReceiptBook(
    private val marks: MarkedMomentDao,
    private val deliveries: PeerDeliveryDao,
    private val outbox: SyncMetadataService,
    private val peers: PhoneDeliveryPeers,
    private val accepts: suspend (PeerDeliveryContext) -> Boolean,
) {
    suspend fun receiptFor(
        context: PeerDeliveryContext,
        query: ReceiptQuery,
    ): Receipt? {
        if (query.kind != RecordKind.MOMENT || !current(context)) return null
        val id = Uuid.parseOrNull(query.id)?.takeIf { it.toString() == query.id } ?: return null
        val identity = query.operationId?.let { CaptureIdentity.of(query.id, it) } ?: return null
        if (query.removed && query.contentVersion != 0L) return null
        val key = if (query.removed) identity.removalLedgerKey else identity.ledgerKey
        val proof = deliveries.get(context.ledgerScope, "FROM_WATCH", EntityType.MOMENT.name, key) ?: return null
        if (proof.contentVersion < query.contentVersion || proof.state !in receiptStates) return null
        val row = marks.getById(id) ?: return null
        val receipt =
            if (query.removed || row.deletedAt != null) {
                if (row.deletedAt == null || (query.removed && proof.state != "DELETED_ON_PHONE")) return null
                Receipt(
                    RecordKind.MOMENT,
                    query.id,
                    if (query.removed) 0 else proof.contentVersion,
                    ReceiptState.DELETED_ON_PHONE,
                    operationId = identity.operationId,
                    removed = query.removed,
                )
            } else {
                if (row.state !in knownStates || row.lastUpdated.toEpochMilliseconds() < query.contentVersion) return null
                val backedUp = context.mode == LinkMode.CLOUD && row.syncVersion > 0 && !outbox.hasPending(EntityType.MOMENT, query.id)
                Receipt(
                    RecordKind.MOMENT,
                    query.id,
                    proof.contentVersion,
                    if (backedUp) ReceiptState.BACKED_UP else ReceiptState.ON_PHONE,
                    serverVersion = row.syncVersion.takeIf { backedUp },
                    operationId = identity.operationId,
                )
            }
        return receipt.takeIf { current(context) }
    }

    private suspend fun current(context: PeerDeliveryContext): Boolean = accepts(context) && peers.isCurrent(context)

    private val knownStates = setOf(MarkedMomentState.OPEN, MarkedMomentState.DISMISSED, MarkedMomentState.FILLED)
    private val receiptStates = setOf("ON_PHONE", "BACKED_UP", "DELETED_ON_PHONE")
}
