package app.logdate.wear.sync.v2.delivery

import app.logdate.client.repository.moments.MarkedMoment
import app.logdate.client.repository.moments.MarkedMomentRepository
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.ReceiptQuery
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.datalayer.v2.ReceiptsRequest
import app.logdate.client.sync.datalayer.v2.ReceiptsResponse
import app.logdate.client.sync.datalayer.v2.RecordEnvelope
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.CaptureIdentity
import app.logdate.client.sync.peer.delivery.DeliveryDirection
import app.logdate.client.sync.peer.delivery.DeliveryState
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.HoldReason
import app.logdate.client.sync.peer.delivery.MomentEnvelopeBody
import app.logdate.client.sync.peer.delivery.PeerDelivery
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.delivery.PhoneReach
import app.logdate.client.sync.peer.delivery.Route
import app.logdate.client.sync.peer.delivery.RouteConditions
import app.logdate.client.sync.peer.delivery.RouteInput
import app.logdate.client.sync.peer.delivery.WatchSyncRouter
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.uuid.Uuid

class WatchMomentDelivery(
    private val active: suspend () -> Boolean,
    private val peers: WatchDeliveryPeer,
    private val outbox: SyncMetadataService,
    private val moments: MarkedMomentRepository,
    private val ledger: PeerDeliveryLedger,
    private val transportFor: (PeerDeliveryContext) -> DataLayerTransportV2,
    private val conditions: suspend (PeerDeliveryContext, DataLayerTransportV2) -> RouteConditions,
    private val receipts: WatchReceiptApplier,
    private val released: ReleasedToCloud,
    private val cloud: CloudRelease,
    private val accepts: suspend (PeerDeliveryContext) -> Boolean,
    private val clock: Clock = Clock.System,
) {
    private data class Capture(
        val pending: PendingUpload,
        val moment: MarkedMoment?,
        val identity: CaptureIdentity,
    ) {
        val removed: Boolean get() = pending.operation == PendingOperation.DELETE
        val version: Long get() = if (removed) 0 else checkNotNull(moment).lastUpdated.toEpochMilliseconds()
        val key: String get() = if (removed) identity.removalLedgerKey else identity.ledgerKey
        val id: Uuid get() = Uuid.parse(pending.entityId)

        fun path(peer: PeerDeliveryContext) = WearPaths.watchRecord(RecordKind.MOMENT, id, peer, identity.operationId)
    }

    private val router = WatchSyncRouter()

    suspend fun schedule(): NoteSchedule {
        if (!active()) return NoteSchedule.Ledger(null)
        val peer = peers.current() ?: return NoteSchedule.Ledger(null)
        if (!accepts(peer)) return NoteSchedule.Ledger(null)
        val due = mutableListOf<Long>()
        var routing: RouteConditions? = null
        for (capture in captures(peer)) {
            val entry = ledger.get(peer.ledgerScope, DeliveryDirection.TO_PHONE, EntityType.MOMENT, capture.key)
            if (entry == null || (entry.holdReason == null && entry.nextAttemptAt == null)) {
                due += now()
            } else if (entry.nextAttemptAt != null) {
                entry.nextAttemptAt?.let(due::add)
            } else {
                val routeConditions = routing ?: conditions(peer, transportFor(peer)).also { routing = it }
                val since = if (entry.state == DeliveryState.SENT) entry.lastAttemptAt ?: entry.stateChangedAt else entry.stateChangedAt
                val route =
                    router.route(
                        RouteInput(capture.pending.operation, false, entry.state, (now() - since).coerceAtLeast(0), routeConditions),
                    )
                if (route !is Route.Wait) due += now()
            }
        }
        return NoteSchedule.Ledger(if (current(peer)) due.minOrNull() else null)
    }

    suspend fun deliverIfActive(): SyncResult? {
        if (!active()) return null
        val peer = peers.current() ?: return SyncResult(success = true)
        val transport = transportFor(peer)
        val captures = captures(peer)
        if (!prepare(peer, transport, captures)) return SyncResult(success = true)
        val conditions = conditions(peer, transport)
        val proof = if (conditions.phone == PhoneReach.UNREACHABLE) null else ask(peer, transport, captures)
        var settled = 0
        val errors = mutableListOf<SyncError>()
        val toCloud = mutableListOf<CloudCapture>()
        for (capture in captures) {
            if (!usable(peer)) break
            if (!outbox.isCurrentOperation(EntityType.MOMENT, capture.pending)) continue
            val entry = ledger.get(peer.ledgerScope, DeliveryDirection.TO_PHONE, EntityType.MOMENT, capture.key) ?: continue
            if (entry.holdReason == HoldReason.SEND_FAILED && (entry.nextAttemptAt ?: 0) > now()) continue
            val fresh = routingConditions(conditions, proof, capture)
            val since = if (entry.state == DeliveryState.SENT) entry.lastAttemptAt ?: entry.stateChangedAt else entry.stateChangedAt
            when (
                val route =
                    router.route(
                        RouteInput(capture.pending.operation, false, entry.state, (now() - since).coerceAtLeast(0), fresh),
                    )
            ) {
                Route.Settle -> {
                    if (settle(peer, transport, capture, entry)) settled++
                }

                Route.SendToPhone -> {
                    send(peer, transport, capture, entry)?.let(errors::add)
                }

                Route.UploadDirect -> {
                    val moved = ledger.moveTo(entry, DeliveryState.CLOUD_PENDING, nextAttemptAt = now() + HoldTimes.RECHECK_MS)
                    if (moved.state == DeliveryState.CLOUD_PENDING && moved.contentVersion == capture.version) {
                        toCloud += CloudCapture(capture.pending, capture.version, EntityType.MOMENT)
                    }
                }

                is Route.Wait -> {
                    ledger.moveTo(entry, entry.state, route.reason, HoldTimes.recheckAt(route.reason, entry, now()))
                }
            }
        }
        if (toCloud.isNotEmpty() && usable(peer)) {
            val result = upload(peer, toCloud)
            settled += result.uploadedItems
            errors += result.errors
        }
        if (usable(peer)) cleanup(peer, transport)
        return SyncResult(success = errors.isEmpty(), uploadedItems = settled, errors = errors, lastSyncTime = clock.now())
    }

    private fun routingConditions(
        conditions: RouteConditions,
        proof: ReceiptsResponse?,
        capture: Capture,
    ): RouteConditions {
        val localProof = hasLocalProof(proof, capture)
        val cloudPresence = proof?.cloud ?: conditions.phoneCloud
        return conditions.copy(
            phoneCloud =
                if (cloudPresence == CloudPresence.NO_ACCOUNT && !localProof) {
                    CloudPresence.UNKNOWN
                } else {
                    cloudPresence
                },
        )
    }

    private fun hasLocalProof(
        proof: ReceiptsResponse?,
        capture: Capture,
    ): Boolean =
        proof?.receipts?.any {
            it.kind == RecordKind.MOMENT &&
                it.id == capture.pending.entityId &&
                it.operationId == capture.identity.operationId &&
                it.removed == capture.removed &&
                it.covers(capture.version) &&
                it.state == ReceiptState.ON_PHONE
        } == true

    private suspend fun prepare(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        captures: List<Capture>,
    ): Boolean {
        if (!current(peer)) return false
        if (!accepts(peer)) {
            for (capture in captures) {
                if (!current(peer)) break
                val entry = ledger.track(peer.ledgerScope, DeliveryDirection.TO_PHONE, EntityType.MOMENT, capture.key, capture.version)
                ledger.moveTo(entry, entry.state, HoldReason.PHONE_CANNOT_READ)
            }
            return false
        }
        cleanup(peer, transport)
        for (capture in captures) {
            if (!usable(peer)) return false
            ledger.track(peer.ledgerScope, DeliveryDirection.TO_PHONE, EntityType.MOMENT, capture.key, capture.version)
        }
        return true
    }

    private suspend fun captures(peer: PeerDeliveryContext): List<Capture> {
        val captures = mutableListOf<Capture>()
        for (pending in outbox.getPendingUploads(EntityType.MOMENT)) {
            if (!current(peer)) break
            val scope = pending.scope ?: continue
            if (peer.mode == LinkMode.CLOUD && (scope.ownerId != peer.accountId || scope.serverOrigin != peer.serverOrigin)) continue
            val id = Uuid.parseOrNull(pending.entityId)?.takeIf { it.toString() == pending.entityId } ?: continue
            val identity = CaptureIdentity.of(pending.entityId, pending.operationId) ?: continue
            val moment = if (pending.operation == PendingOperation.DELETE) null else moments.getMark(id) ?: continue
            if (current(peer) && outbox.isCurrentOperation(EntityType.MOMENT, pending)) captures += Capture(pending, moment, identity)
        }
        return captures
    }

    private suspend fun send(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        capture: Capture,
        entry: PeerDelivery,
    ): SyncError? {
        try {
            val envelope =
                if (capture.removed) {
                    RecordEnvelope(
                        kind = RecordKind.MOMENT,
                        id = capture.pending.entityId,
                        contentVersion = 0,
                        syncVersion = capture.pending.expectedServerVersion ?: 0,
                        origin = RecordOrigin.WATCH,
                        removed = true,
                    )
                } else {
                    MomentEnvelopeBody.envelope(checkNotNull(capture.moment), RecordOrigin.WATCH)
                }
            val data =
                try {
                    EnvelopeDataMap.toDataMap(envelope.copy(context = peer, operationId = capture.identity.operationId), now())
                } catch (_: IllegalArgumentException) {
                    ledger.moveTo(entry, DeliveryState.REJECTED, nextAttemptAt = now())
                    return null
                }
            if (!usable(peer) || !outbox.isCurrentOperation(EntityType.MOMENT, capture.pending)) return null
            if (!transport.put(capture.path(peer), data)) return failed(entry)
            if (usable(peer) && outbox.isCurrentOperation(EntityType.MOMENT, capture.pending)) {
                val attempted = ledger.recordAttempt(entry, nextAttemptAt = null)
                ledger.moveTo(attempted, DeliveryState.SENT, nextAttemptAt = now() + WatchSyncRouter.RECEIPT_WAIT_MS)
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not send a marked moment to the phone", e)
            return failed(entry)
        }
    }

    private suspend fun settle(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        capture: Capture,
        entry: PeerDelivery,
    ): Boolean {
        if (!usable(peer) ||
            !outbox.settleIfCurrent(
                EntityType.MOMENT,
                capture.pending,
                clock.now(),
                capture.moment?.syncVersion ?: capture.pending.expectedServerVersion ?: 0,
            )
        ) {
            return false
        }
        if (usable(peer) &&
            transport.deleteOwn(capture.path(peer))
        ) {
            ledger.forget(entry.scope, entry.direction, entry.entityType, entry.entityId)
        }
        return true
    }

    private suspend fun ask(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        captures: List<Capture>,
    ): ReceiptsResponse? {
        try {
            val request =
                ReceiptsRequest(
                    items =
                        captures.map {
                            ReceiptQuery(RecordKind.MOMENT, it.pending.entityId, it.version, it.identity.operationId, it.removed)
                        },
                    context = peer,
                )
            val bytes =
                transport.request(WearPaths.RECEIPTS, WearJson.encodeToBytes(ReceiptsRequest.serializer(), request)).getOrNull()
                    ?: return null
            if (!usable(peer)) return null
            val proof =
                (WearJson.decode(bytes, WearProtocol.RECEIPTS_SCHEMA, ReceiptsResponse.serializer()) as? Decoded.Supported)?.value
                    ?: return null
            val compatible =
                proof.cloud == CloudPresence.UNKNOWN ||
                    (
                        proof.cloud == CloudPresence.SIGNED_IN &&
                            peer.mode == LinkMode.CLOUD
                    ) ||
                    (
                        proof.cloud == CloudPresence.NO_ACCOUNT &&
                            peer.mode == LinkMode.PAIR
                    )
            if (proof.context != peer || !compatible) return null
            receipts.apply(peer.phoneNode, bytes)
            return proof.takeIf { usable(peer) }
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (
            e: Exception,
        ) {
            Napier.w("Marked-moment receipts could not be read; the watch will ask again", e)
            return null
        }
    }

    private suspend fun cleanup(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
    ) {
        if (!usable(peer)) return
        val keep =
            outbox
                .getPendingUploads(EntityType.MOMENT)
                .mapNotNull { pending ->
                    CaptureIdentity.of(pending.entityId, pending.operationId)?.let {
                        if (pending.operation ==
                            PendingOperation.DELETE
                        ) {
                            it.removalLedgerKey
                        } else {
                            it.ledgerKey
                        }
                    }
                }.toSet()
        for (entry in ledger.observe(peer.ledgerScope, DeliveryDirection.TO_PHONE).first()) {
            if (!usable(peer)) return
            if (entry.entityType != EntityType.MOMENT || entry.entityId in keep) continue
            val identity = CaptureIdentity.fromLedgerKey(entry.entityId.removeSuffix(":remove")) ?: continue
            val id = Uuid.parseOrNull(identity.recordId) ?: continue
            try {
                if (transport.deleteOwn(WearPaths.watchRecord(RecordKind.MOMENT, id, peer, identity.operationId)) && usable(peer)) {
                    ledger.forget(entry.scope, entry.direction, entry.entityType, entry.entityId)
                }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (e: Exception) {
                Napier.w("A completed marked-moment data item will be removed on the next sync", e)
            }
        }
    }

    private suspend fun failed(entry: PeerDelivery): SyncError {
        val attempted = ledger.recordAttempt(entry, nextAttemptAt = null)
        ledger.moveTo(attempted, attempted.state, HoldReason.SEND_FAILED, now() + HoldTimes.sendRetryDelay(attempted.attempts))
        return SyncError(SyncErrorType.NETWORK_ERROR, "Could not send a marked moment to the phone", retryable = true)
    }

    private suspend fun upload(
        peer: PeerDeliveryContext,
        captures: List<CloudCapture>,
    ): SyncResult {
        try {
            val result = released.withCaptures(peer, captures) { cloud.upload() }
            return if (result.success || result.errors.isNotEmpty()) {
                result
            } else {
                result.copy(
                    errors =
                        listOf(
                            SyncError(SyncErrorType.UNKNOWN_ERROR, "The watch could not back up its marked moments", retryable = true),
                        ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncResult(
                success = false,
                errors =
                    listOf(
                        SyncError(
                            SyncErrorType.UNKNOWN_ERROR,
                            "The watch could not back up its marked moments",
                            e,
                            retryable = true,
                        ),
                    ),
            )
        }
    }

    private suspend fun current(peer: PeerDeliveryContext): Boolean = active() && peers.isCurrent(peer)

    private suspend fun usable(peer: PeerDeliveryContext): Boolean = current(peer) && accepts(peer)

    private fun now(): Long = clock.now().toEpochMilliseconds()
}
