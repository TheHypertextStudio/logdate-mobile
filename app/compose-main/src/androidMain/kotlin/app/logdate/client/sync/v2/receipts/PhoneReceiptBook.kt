package app.logdate.client.sync.v2.receipts

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.Receipt
import app.logdate.client.sync.datalayer.v2.ReceiptQuery
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.CaptureIdentity
import app.logdate.client.sync.peer.delivery.DeliveryDirection
import app.logdate.client.sync.peer.delivery.DeliveryState
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.delivery.PeerScopes
import kotlin.uuid.Uuid

/**
 * What the phone has of each record a watch sent, as a receipt. A note is backed up only when the phone
 * is signed in, the note has a server version, and no change to it is waiting in the outbox.
 */
class PhoneReceiptBook(
    private val notes: JournalNotesRepository,
    private val outbox: SyncMetadataService,
    private val sessions: SessionStorage,
    private val ledger: PeerDeliveryLedger,
    private val peers: PhoneDeliveryPeers? = null,
    private val momentReceipts: MomentReceiptBook? = null,
) {
    fun cloudPresence(): CloudPresence = if (sessions.getSession() == null) CloudPresence.NO_ACCOUNT else CloudPresence.SIGNED_IN

    /** Null when the phone has no copy of that version yet, so the watch sends it again. */
    suspend fun receiptFor(query: ReceiptQuery): Receipt? {
        if (query.operationId != null || query.removed) return null
        val session = sessions.getOriginBoundSession()
        val id = Uuid.parseOrNull(query.id)
        if (query.kind != RecordKind.NOTE || id == null) return rejected(query.kind, query.id, query.operationId)
        val note = notes.getNoteById(id)
        if (note == null) {
            val received = ledger.get(PeerScopes.WATCHES, DeliveryDirection.FROM_WATCH, EntityType.NOTE, query.id)
            if (sessions.getOriginBoundSession() != session) return null
            return received?.takeIf { it.contentVersion >= query.contentVersion }?.let {
                Receipt(RecordKind.NOTE, query.id, it.contentVersion, ReceiptState.DELETED_ON_PHONE)
            }
        }
        val version = note.lastUpdated.toEpochMilliseconds()
        if (version < query.contentVersion) return null
        val backedUp = cloudPresence() == CloudPresence.SIGNED_IN && note.syncVersion > 0 && !outbox.hasPending(EntityType.NOTE, query.id)
        if (sessions.getOriginBoundSession() != session) return null
        return Receipt(
            kind = RecordKind.NOTE,
            id = query.id,
            contentVersion = version,
            state = if (backedUp) ReceiptState.BACKED_UP else ReceiptState.ON_PHONE,
            serverVersion = note.syncVersion.takeIf { backedUp },
        )
    }

    suspend fun isCurrent(context: PeerDeliveryContext): Boolean = peers?.isCurrent(context) == true

    suspend fun receiptFor(
        context: PeerDeliveryContext,
        query: ReceiptQuery,
    ): Receipt? {
        if (!isCurrent(context)) return null
        if (query.kind == RecordKind.MOMENT) {
            return momentReceipts?.receiptFor(context, query)
                ?: if (momentReceipts == null) rejected(query.kind, query.id, query.operationId) else null
        }
        val session = sessions.getOriginBoundSession()
        val id = Uuid.parseOrNull(query.id)
        if (query.kind != RecordKind.NOTE || id == null) return rejected(query.kind, query.id, query.operationId)
        val identity = query.operationId?.let { CaptureIdentity.of(query.id, it) ?: return null }
        if (query.removed && (identity == null || query.contentVersion != 0L)) return null
        val key = if (query.removed) identity!!.removalLedgerKey else identity?.ledgerKey ?: query.id
        val received =
            ledger
                .get(context.ledgerScope, DeliveryDirection.FROM_WATCH, EntityType.NOTE, key)
                ?.takeIf { it.contentVersion >= query.contentVersion } ?: return null
        val note = notes.getNoteById(id)
        if (query.removed) {
            if (note != null || received.state != DeliveryState.DELETED_ON_PHONE) return null
            return Receipt(RecordKind.NOTE, query.id, 0, ReceiptState.DELETED_ON_PHONE, operationId = query.operationId, removed = true)
                .takeIf { sessions.getOriginBoundSession() == session && isCurrent(context) }
        }
        val receipt =
            if (note == null) {
                Receipt(RecordKind.NOTE, query.id, received.contentVersion, ReceiptState.DELETED_ON_PHONE)
            } else {
                val version = note.lastUpdated.toEpochMilliseconds()
                if (version < query.contentVersion) return null
                val backedUp =
                    context.mode == LinkMode.CLOUD &&
                        note.syncVersion > 0 &&
                        !outbox.hasPending(EntityType.NOTE, query.id)
                Receipt(
                    kind = RecordKind.NOTE,
                    id = query.id,
                    contentVersion = version,
                    state = if (backedUp) ReceiptState.BACKED_UP else ReceiptState.ON_PHONE,
                    serverVersion = note.syncVersion.takeIf { backedUp },
                )
            }
        return receipt.copy(operationId = query.operationId).takeIf { sessions.getOriginBoundSession() == session && isCurrent(context) }
    }

    companion object {
        /** A rejection names the highest version, so it covers every version of the record. */
        const val ANY_VERSION = Long.MAX_VALUE

        fun rejected(
            kind: RecordKind,
            id: String,
            operationId: String? = null,
        ): Receipt = Receipt(kind, id, ANY_VERSION, ReceiptState.REJECTED_UNSUPPORTED, operationId = operationId)
    }
}
