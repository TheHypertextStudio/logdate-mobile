package app.logdate.wear.sync.v2.delivery

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.DeliveryPriority
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.MediaRef
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
import app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
import app.logdate.client.sync.peer.delivery.PeerDelivery
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.delivery.PhoneReach
import app.logdate.client.sync.peer.delivery.Route
import app.logdate.client.sync.peer.delivery.RouteConditions
import app.logdate.client.sync.peer.delivery.RouteInput
import app.logdate.client.sync.peer.delivery.WatchSyncRouter
import app.logdate.wear.sync.AudioFileDigester
import app.logdate.wear.sync.FileAudioDigester
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun interface WatchNotesV2 {
    suspend fun deliverIfActive(): SyncResult?
}

fun interface CloudRelease {
    suspend fun upload(): SyncResult
}

/** Capture delivery under the manager's run lock. Removal integration precedes enabling this runner. */
class WatchNoteDelivery(
    private val active: suspend () -> Boolean,
    private val peers: WatchDeliveryPeer,
    private val outbox: SyncMetadataService,
    private val notes: JournalNotesRepository,
    private val ledger: PeerDeliveryLedger,
    private val transportFor: (PeerDeliveryContext) -> DataLayerTransportV2,
    private val conditions: suspend (PeerDeliveryContext, DataLayerTransportV2) -> RouteConditions,
    private val receipts: WatchReceiptApplier,
    private val released: ReleasedToCloud,
    private val cloud: CloudRelease,
    private val digester: AudioFileDigester = FileAudioDigester(),
    private val clock: Clock = Clock.System,
    private val mediaFilePath: (String) -> String? = { it },
) : WatchNotesV2 {
    private data class Capture(
        val pending: PendingUpload,
        val note: JournalNote?,
        val identity: CaptureIdentity,
    ) {
        val removed: Boolean get() = pending.operation == PendingOperation.DELETE
        val version: Long get() = note?.lastUpdated?.toEpochMilliseconds() ?: 0
        val ledgerKey: String get() = if (removed) identity.removalLedgerKey else identity.ledgerKey
        val id: Uuid get() = Uuid.parse(pending.entityId)
    }

    private data class Step(
        val settled: Int = 0,
        val release: CloudCapture? = null,
        val error: SyncError? = null,
    )

    private val router = WatchSyncRouter()
    private val cleanup = WatchCaptureCleanup(peers, outbox, ledger)

    suspend fun schedule(): NoteSchedule {
        if (!active()) return NoteSchedule.Outbox
        val peer = peers.current() ?: return NoteSchedule.Ledger(null)
        val deadlines = mutableListOf<Long>()
        for (pending in outbox.getPendingUploads(EntityType.NOTE)) {
            if (pending.scope == null) continue
            val id = Uuid.parseOrNull(pending.entityId) ?: continue
            if (pending.operation != PendingOperation.DELETE && notes.getNoteById(id) == null) continue
            val identity = CaptureIdentity.of(pending.entityId, pending.operationId ?: continue) ?: continue
            val key = if (pending.operation == PendingOperation.DELETE) identity.removalLedgerKey else identity.ledgerKey
            val entry = ledger.get(peer.ledgerScope, DeliveryDirection.TO_PHONE, EntityType.NOTE, key)
            if (entry == null || (entry.holdReason == null && entry.nextAttemptAt == null)) {
                deadlines += entry?.nextAttemptAt ?: now()
            } else {
                entry.nextAttemptAt?.let(deadlines::add)
            }
        }
        return NoteSchedule.Ledger(if (isCurrent(peer)) deadlines.minOrNull() else null)
    }

    override suspend fun deliverIfActive(): SyncResult? {
        if (!active()) return null
        val peer = peers.current() ?: return SyncResult(success = true)
        val transport = transportFor(peer)
        cleanup.prune(peer, transport)
        val pending = outbox.getPendingUploads(EntityType.NOTE)
        val captures = captures(peer, pending)
        if (!isCurrent(peer)) return SyncResult(success = true)
        val route = conditions(peer, transport)
        val proof = if (route.phone != PhoneReach.UNREACHABLE) askForReceipts(peer, transport, captures) else null
        val errors = mutableListOf<SyncError>()
        val toCloud = mutableListOf<CloudCapture>()
        var settled = 0
        for (capture in captures) {
            if (!isCurrent(peer)) break
            if (!outbox.isCurrentOperation(EntityType.NOTE, capture.pending)) continue
            val step = deliverOne(peer, transport, capture, route, proof)
            settled += step.settled
            step.release?.let(toCloud::add)
            step.error?.let(errors::add)
        }
        if (toCloud.isNotEmpty() && isCurrent(peer)) {
            val result = uploadReleased(peer, toCloud)
            settled += result.uploadedItems
            errors += result.errors
        }
        if (isCurrent(peer)) cleanup.prune(peer, transport)
        val finishedAt = clock.now()
        if (isCurrent(peer)) outbox.updateLastSyncTime(EntityType.NOTE, finishedAt)
        return SyncResult(success = errors.isEmpty(), uploadedItems = settled, errors = errors, lastSyncTime = finishedAt)
    }

    private suspend fun captures(
        peer: PeerDeliveryContext,
        pending: List<PendingUpload>,
    ): List<Capture> {
        val captures = mutableListOf<Capture>()
        for (operation in pending) {
            if (!isCurrent(peer)) break
            if (operation.scope == null || operation.operationId.isNullOrBlank()) continue
            val identity = CaptureIdentity.of(operation.entityId, operation.operationId) ?: continue
            val id = Uuid.parseOrNull(operation.entityId) ?: continue
            val note = if (operation.operation == PendingOperation.DELETE) null else notes.getNoteById(id) ?: continue
            val capture = Capture(operation, note, identity)
            if (!isCurrent(peer) || !outbox.isCurrentOperation(EntityType.NOTE, operation)) continue
            ledger.track(
                peer.ledgerScope,
                DeliveryDirection.TO_PHONE,
                EntityType.NOTE,
                capture.ledgerKey,
                capture.version,
            )
            captures += capture
        }
        return captures
    }

    private suspend fun deliverOne(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        capture: Capture,
        conditions: RouteConditions,
        proof: ReceiptsResponse?,
    ): Step {
        val entry = ledger.get(peer.ledgerScope, DeliveryDirection.TO_PHONE, EntityType.NOTE, capture.ledgerKey) ?: return Step()
        if (entry.holdReason == HoldReason.SEND_FAILED && (entry.nextAttemptAt ?: 0L) > now()) return Step()
        val freshLocalProof =
            proof?.receipts?.any {
                it.kind == RecordKind.NOTE &&
                    it.id == capture.pending.entityId &&
                    it.operationId == capture.identity.operationId &&
                    it.removed == capture.removed &&
                    it.covers(entry.contentVersion) &&
                    it.state == ReceiptState.ON_PHONE
            } == true
        val phoneCloud = proof?.cloud ?: conditions.phoneCloud
        val routeConditions =
            conditions.copy(
                phoneCloud = if (phoneCloud == CloudPresence.NO_ACCOUNT && !freshLocalProof) CloudPresence.UNKNOWN else phoneCloud,
            )
        val since = if (entry.state == DeliveryState.SENT) entry.lastAttemptAt ?: entry.stateChangedAt else entry.stateChangedAt
        val input =
            RouteInput(
                capture.pending.operation,
                capture.note is JournalNote.Audio,
                entry.state,
                (now() - since).coerceAtLeast(0),
                routeConditions,
            )
        return when (val route = router.route(input)) {
            Route.Settle -> {
                settle(peer, transport, capture, entry)
            }

            Route.SendToPhone -> {
                send(peer, transport, capture, entry)
            }

            Route.UploadDirect -> {
                if (!isCurrent(peer) || !outbox.isCurrentOperation(EntityType.NOTE, capture.pending)) return Step()
                val moved = ledger.moveTo(entry, DeliveryState.CLOUD_PENDING, nextAttemptAt = now() + HoldTimes.RECHECK_MS)
                if (moved.contentVersion == entry.contentVersion && moved.state == DeliveryState.CLOUD_PENDING) {
                    Step(release = CloudCapture(capture.pending, entry.contentVersion))
                } else {
                    Step()
                }
            }

            is Route.Wait -> {
                ledger.moveTo(entry, entry.state, route.reason, HoldTimes.recheckAt(route.reason, entry, now()))
                Step()
            }
        }
    }

    private suspend fun settle(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        capture: Capture,
        entry: PeerDelivery,
    ): Step {
        if (!isCurrent(peer)) return Step()
        if (!outbox.settleIfCurrent(
                EntityType.NOTE,
                capture.pending,
                clock.now(),
                (
                    capture.note?.syncVersion
                        ?: capture.pending.expectedServerVersion
                        ?: 0
                ),
            )
        ) {
            return Step()
        }
        if (isCurrent(peer) &&
            transport.deleteOwn(WearPaths.watchRecord(RecordKind.NOTE, capture.id, peer, capture.identity.operationId))
        ) {
            ledger.forget(entry.scope, entry.direction, entry.entityType, entry.entityId)
        }
        return Step(settled = 1)
    }

    private suspend fun send(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        capture: Capture,
        entry: PeerDelivery,
    ): Step {
        return try {
            val audio = capture.note as? JournalNote.Audio
            val audioPath = audio?.let { mediaFilePath(it.mediaRef) ?: return failed(entry, "its recording could not be read") }
            val media =
                if (audio == null) {
                    emptyList()
                } else {
                    val digest = digester.digest(checkNotNull(audioPath)) ?: return failed(entry, "its recording could not be read")
                    listOf(MediaRef(sha256 = digest.sha256Hex, sizeBytes = digest.sizeBytes, mimeType = "audio/mp4"))
                }
            if (!isCurrent(peer) || !outbox.isCurrentOperation(EntityType.NOTE, capture.pending)) return Step()
            val envelope = captureEnvelope(peer, capture, media)
            val data =
                try {
                    EnvelopeDataMap.toDataMap(envelope, sentAt = now())
                } catch (e: IllegalArgumentException) {
                    Napier.w("The capture needs a larger-body transport; it remains on the watch", e)
                    ledger.moveTo(entry, DeliveryState.REJECTED, nextAttemptAt = now())
                    return Step()
                }
            if (!transport.put(
                    WearPaths.watchRecord(RecordKind.NOTE, capture.id, peer, capture.identity.operationId),
                    data,
                )
            ) {
                return failed(entry, "its data could not be sent")
            }
            if (!isCurrent(peer) || !outbox.isCurrentOperation(EntityType.NOTE, capture.pending)) return Step()
            if (audio != null &&
                !transport.sendFile(
                    WearPaths.recordMedia(RecordKind.NOTE, audio.uid, peer, capture.identity.operationId),
                    checkNotNull(audioPath),
                )
            ) {
                return failed(entry, "its recording could not be sent")
            }
            if (isCurrent(peer) && outbox.isCurrentOperation(EntityType.NOTE, capture.pending)) {
                val attempted = ledger.recordAttempt(entry, nextAttemptAt = null)
                ledger.moveTo(attempted, DeliveryState.SENT, nextAttemptAt = now() + WatchSyncRouter.RECEIPT_WAIT_MS)
            }
            Step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not send a capture to the phone", e)
            failed(entry, "the transport failed")
        }
    }

    private fun captureEnvelope(
        peer: PeerDeliveryContext,
        capture: Capture,
        media: List<MediaRef>,
    ): RecordEnvelope =
        if (capture.removed) {
            RecordEnvelope(
                kind = RecordKind.NOTE,
                id = capture.pending.entityId,
                contentVersion = 0,
                syncVersion = capture.pending.expectedServerVersion ?: 0,
                origin = RecordOrigin.WATCH,
                priority = DeliveryPriority.URGENT,
                context = peer,
                operationId = capture.identity.operationId,
                removed = true,
            )
        } else {
            NoteEnvelopeBody
                .envelope(checkNotNull(capture.note), RecordOrigin.WATCH, DeliveryPriority.URGENT, media = media)
                .copy(context = peer, operationId = capture.identity.operationId)
        }

    private suspend fun failed(
        entry: PeerDelivery,
        why: String,
    ): Step {
        val attempted = ledger.recordAttempt(entry, nextAttemptAt = null)
        ledger.moveTo(attempted, attempted.state, HoldReason.SEND_FAILED, now() + HoldTimes.sendRetryDelay(attempted.attempts))
        return Step(error = SyncError(SyncErrorType.NETWORK_ERROR, "Could not send a capture: $why", retryable = true))
    }

    private suspend fun askForReceipts(
        peer: PeerDeliveryContext,
        transport: DataLayerTransportV2,
        captures: List<Capture>,
    ): ReceiptsResponse? {
        return try {
            val request =
                ReceiptsRequest(
                    items =
                        captures.map {
                            ReceiptQuery(
                                RecordKind.NOTE,
                                it.pending.entityId,
                                it.version,
                                it.identity.operationId,
                                removed = it.removed,
                            )
                        },
                    context = peer,
                )
            val answer = transport.request(WearPaths.RECEIPTS, WearJson.encodeToBytes(ReceiptsRequest.serializer(), request)).getOrNull()
            if (answer == null || !isCurrent(peer)) return null
            val response =
                (WearJson.decode(answer, WearProtocol.RECEIPTS_SCHEMA, ReceiptsResponse.serializer()) as? Decoded.Supported)?.value
                    ?: return null
            val compatible =
                response.cloud == CloudPresence.UNKNOWN ||
                    (
                        response.cloud == CloudPresence.NO_ACCOUNT &&
                            peer.mode == LinkMode.PAIR
                    ) ||
                    (
                        response.cloud == CloudPresence.SIGNED_IN &&
                            peer.mode == LinkMode.CLOUD
                    )
            if (response.context != peer || !compatible) return null
            receipts.apply(peer.phoneNode, answer)
            if (isCurrent(peer)) response else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("The phone's capture receipts could not be read; they will be requested again", e)
            null
        }
    }

    private suspend fun uploadReleased(
        peer: PeerDeliveryContext,
        captures: List<CloudCapture>,
    ): SyncResult =
        try {
            val result = released.withCaptures(peer, captures) { cloud.upload() }
            if (!result.success && result.errors.isEmpty()) {
                result.copy(
                    errors =
                        listOf(
                            SyncError(SyncErrorType.UNKNOWN_ERROR, "The watch could not finish backing up its captures", retryable = true),
                        ),
                )
            } else {
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult(
                success = false,
                errors = listOf(SyncError(SyncErrorType.UNKNOWN_ERROR, "The watch could not back up its captures", e, retryable = true)),
            )
        }

    private suspend fun isCurrent(peer: PeerDeliveryContext): Boolean = active() && peers.isCurrent(peer)

    private fun now(): Long = clock.now().toEpochMilliseconds()
}

internal object HoldTimes {
    const val RECHECK_MS = 15 * 60_000L

    fun sendRetryDelay(attempts: Int): Long = (30_000L shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(6 * 60 * 60_000L)

    fun recheckAt(
        reason: HoldReason,
        entry: PeerDelivery,
        now: Long,
    ): Long? =
        when (reason) {
            HoldReason.AWAITING_RECEIPT -> later((entry.lastAttemptAt ?: entry.stateChangedAt) + WatchSyncRouter.RECEIPT_WAIT_MS, now)
            HoldReason.PHONE_BACKING_UP -> later(entry.stateChangedAt + WatchSyncRouter.BACKUP_GRACE_MS, now)
            HoldReason.NO_INTERNET, HoldReason.BLUETOOTH_ONLY, HoldReason.AUDIO_WAITS_FOR_WIFI -> now + RECHECK_MS
            HoldReason.SEND_FAILED -> now + sendRetryDelay(entry.attempts)
            else -> null
        }

    private fun later(
        at: Long,
        now: Long,
    ) = if (at > now) at else now + RECHECK_MS
}
