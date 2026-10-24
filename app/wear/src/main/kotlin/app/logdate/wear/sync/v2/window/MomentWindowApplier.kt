package app.logdate.wear.sync.v2.window

import app.logdate.client.repository.moments.MarkedMoment
import app.logdate.client.repository.moments.SyncableMarkedMomentRepository
import app.logdate.client.repository.moments.atMillisecondPrecision
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordEnvelope
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.datalayer.v2.WindowMemberRef
import app.logdate.client.sync.datalayer.v2.WindowResyncResponse
import app.logdate.client.sync.datalayer.v2.WindowTombstoneDataMap
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.MomentEnvelopeBody
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import io.github.aakira.napier.Napier
import java.security.MessageDigest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Called under WindowApplier's serialization lock; moment ownership never aliases note ownership. */
class MomentWindowApplier(
    private val peers: WatchDeliveryPeer,
    private val moments: SyncableMarkedMomentRepository,
    private val outbox: SyncMetadataService,
    private val members: WindowMembershipStore,
    private val transactions: SyncTransactionManager,
    private val accepts: suspend (PeerDeliveryContext) -> Boolean,
) {
    private data class Copy(
        val moment: MarkedMoment,
        val ref: WindowMemberRef,
    )

    private class GuardChanged : IllegalStateException()

    suspend fun complete(
        peer: PeerDeliveryContext,
        response: WindowResyncResponse,
        items: List<WindowItem>,
    ): Boolean {
        if (!peers.isCurrent(peer) || response.context != peer || response.schema != WearProtocol.WINDOW_SCHEMA) return false
        val previous = members.read(peer)
        if (response.revision < (previous.snapshotRevision ?: 0)) return false
        val expected = response.members.filter { it.kind == RecordKind.MOMENT }
        if (expected.isNotEmpty() && !accepts(peer)) return false
        val copies = items.mapNotNull { decode(peer, it) }.associateBy { it.ref }
        for (ref in expected) {
            if (ref in copies) continue
            val saved = previous.entries[ref.id]
            if (saved?.revision != ref.revision ||
                saved.contentVersion != ref.contentVersion ||
                moments.getMark(Uuid.parse(ref.id)) == null
            ) {
                return false
            }
        }
        return peers.isCurrent(peer)
    }

    /** Explicit deletions are independent of live-window completeness and must replay after restart. */
    suspend fun applyDeletions(
        peer: PeerDeliveryContext,
        items: List<WindowItem>,
    ): Set<String>? {
        val deleted = mutableSetOf<String>()
        for (item in items) {
            val path = WearPaths.parseWindowTombstone(item.path) ?: continue
            if (path.kind != RecordKind.MOMENT || WindowTombstoneDataMap.KEY_DELETED_AT !in item.data) continue
            if (!allowed(peer)) return null
            if (onTombstone(item)) deleted += path.id.toString()
            if (!allowed(peer)) return null
        }
        return deleted.takeIf { peers.isCurrent(peer) }
    }

    suspend fun reconcile(
        peer: PeerDeliveryContext,
        response: WindowResyncResponse,
        items: List<WindowItem>,
    ): WindowApplied? {
        if (!complete(peer, response, items)) return null
        val expected = response.members.filter { it.kind == RecordKind.MOMENT }.associateBy { it.id }
        val copies = items.mapNotNull { decode(peer, it) }.associateBy { it.ref }
        var applied = 0
        for (ref in expected.values) {
            if (!allowed(peer)) return null
            val copy = copies[ref] ?: continue
            if (apply(peer, copy)) applied++
            if (!allowed(peer)) return null
        }
        var removed = 0
        for (id in members.read(peer).entries.keys - expected.keys) {
            if (!peers.isCurrent(peer)) return null
            if (retire(peer, id, response.revision)) removed++
        }
        if (!peers.isCurrent(peer)) return null
        val settled = members.read(peer)
        members.write(
            peer,
            settled.copy(
                snapshotRevision = response.revision,
                expected = expected,
                retired = settled.retired.filterValues { it > response.revision },
            ),
        )
        if (expected.isNotEmpty() && !allowed(peer)) return null
        return WindowApplied(applied, removed)
    }

    suspend fun onItem(item: WindowItem): Boolean {
        val peer = peers.current() ?: return false
        if (!allowed(peer)) return false
        val copy = decode(peer, item) ?: return false
        val state = members.read(peer)
        val snapshot = state.snapshotRevision ?: return false
        if (copy.ref.revision <= snapshot && state.expected[copy.ref.id] != copy.ref) return false
        return apply(peer, copy)
    }

    suspend fun onTombstone(item: WindowItem): Boolean {
        val peer = peers.current() ?: return false
        if (!allowed(peer)) return false
        val path = WearPaths.parseWindowTombstone(item.path) ?: return false
        if (item.sourceNodeId != peer.phoneNode || path.kind != RecordKind.MOMENT || path.linkId != peer.linkId) return false
        val context = item.data[WindowTombstoneDataMap.KEY_CONTEXT] ?: return false
        if ((WearJson.decode(context, WearProtocol.WINDOW_SCHEMA, PeerDeliveryContext.serializer()) as? Decoded.Supported)?.value !=
            peer
        ) {
            return false
        }
        if (item.data[WindowTombstoneDataMap.KEY_REMOVED_AT]?.toLongOrNull()?.takeIf { it >= 0 } == null) return false
        val revision = item.data[WindowTombstoneDataMap.KEY_REVISION]?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        if (WindowTombstoneDataMap.KEY_DELETED_AT in item.data) {
            val deletedAt = item.data[WindowTombstoneDataMap.KEY_DELETED_AT]?.toLongOrNull()?.takeIf { it >= 0 } ?: return false
            return delete(peer, path.id, Instant.fromEpochMilliseconds(deletedAt), revision)
        }
        val state = members.read(peer)
        val snapshot = state.snapshotRevision ?: return false
        val id = path.id.toString()
        if ((revision <= snapshot && id in state.expected) || (state.entries[id]?.revision ?: 0) >= revision) return false
        return retire(peer, id, revision)
    }

    private fun decode(
        peer: PeerDeliveryContext,
        item: WindowItem,
    ): Copy? =
        try {
            val path = WearPaths.parseWindowItem(item.path)
            val envelope = (EnvelopeDataMap.fromDataMap(item.data) as? Decoded.Supported)?.value
            val revision = item.data[WindowTombstoneDataMap.KEY_REVISION]?.toLongOrNull() ?: 0
            if (path == null ||
                envelope == null ||
                item.sourceNodeId != peer.phoneNode ||
                path.linkId != peer.linkId ||
                path.kind != RecordKind.MOMENT ||
                envelope.kind != RecordKind.MOMENT ||
                envelope.context != peer ||
                envelope.origin != RecordOrigin.PHONE ||
                envelope.id != path.id.toString() ||
                envelope.contentVersion < 0 ||
                revision <= 0
            ) {
                null
            } else {
                val value = MomentEnvelopeBody.decode(envelope).atMillisecondPrecision()
                Copy(value, WindowMemberRef(RecordKind.MOMENT, value.id.toString(), envelope.contentVersion, revision))
            }
        } catch (e: IllegalArgumentException) {
            Napier.w("An unreadable returned mark keeps the phone window incomplete", e)
            null
        }

    private suspend fun apply(
        peer: PeerDeliveryContext,
        copy: Copy,
    ): Boolean {
        if (!allowed(peer)) return false
        val id = copy.ref.id
        val state = members.read(peer)
        val entry = state.entries[id]
        val wanted = fingerprint(copy.moment)
        if ((state.retired[id] ?: 0) >= copy.ref.revision ||
            (entry?.revision ?: 0) > copy.ref.revision ||
            (
                entry?.revision == copy.ref.revision &&
                    entry.bodyFingerprint != wanted
            )
        ) {
            return false
        }
        val merged =
            try {
                transactions.withTransaction {
                    guard(peer)
                    val pending = outbox.getPendingUploads(EntityType.MOMENT).singleOrNull { it.entityId == id }
                    if (pending?.operation == PendingOperation.DELETE) return@withTransaction null
                    val before = moments.getMark(copy.moment.id)
                    val accepted = moments.applyFromSync(copy.moment) ?: return@withTransaction null
                    guard(peer)
                    val changedPendingBody = pending != null && accepted.copy(syncVersion = before?.syncVersion ?: 0) != before
                    val needsReturn = pending == null && accepted.copy(syncVersion = copy.moment.syncVersion) != copy.moment
                    if (changedPendingBody ||
                        needsReturn
                    ) {
                        outbox.enqueuePending(id, EntityType.MOMENT, pending?.operation ?: PendingOperation.UPDATE)
                    }
                    guard(peer)
                    accepted
                }
            } catch (_: GuardChanged) {
                return false
            } ?: return false
        if (!allowed(peer)) return false
        val current = members.read(peer)
        val projection = WindowProjection(copy.ref.revision, copy.ref.contentVersion, setOf(fingerprint(merged)), wanted)
        members.write(peer, current.copy(entries = current.entries + (id to projection), retired = current.retired - id))
        return true
    }

    private suspend fun delete(
        peer: PeerDeliveryContext,
        id: Uuid,
        deletedAt: Instant,
        revision: Long,
    ): Boolean {
        try {
            transactions.withTransaction {
                guard(peer)
                moments.applyDeletionFromPeer(id, deletedAt)
                guard(peer)
            }
        } catch (_: GuardChanged) {
            return false
        }
        if (!allowed(peer)) return false
        retire(peer, id.toString(), revision)
        return allowed(peer)
    }

    private suspend fun retire(
        peer: PeerDeliveryContext,
        id: String,
        revision: Long,
    ): Boolean {
        if (!peers.isCurrent(peer)) return false
        val state = members.read(peer)
        if ((state.entries[id]?.revision ?: 0) > revision || (state.retired[id] ?: 0) >= revision) return false
        // Window eviction retires projection ownership; durable marks and their outbox belong to the capture repository.
        members.write(peer, state.copy(entries = state.entries - id, retired = state.retired + (id to revision)))
        return true
    }

    private suspend fun allowed(peer: PeerDeliveryContext) = peers.isCurrent(peer) && accepts(peer)

    private suspend fun guard(peer: PeerDeliveryContext) {
        if (!allowed(peer)) throw GuardChanged()
    }

    private fun fingerprint(mark: MarkedMoment): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                WearJson.encode(RecordEnvelope.serializer(), MomentEnvelopeBody.envelope(mark, RecordOrigin.PHONE)).encodeToByteArray(),
            ).joinToString("") { "%02x".format(it) }
}
