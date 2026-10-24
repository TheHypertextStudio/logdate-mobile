package app.logdate.wear.sync.v2.window

import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.datalayer.v2.WindowMemberRef
import app.logdate.client.sync.datalayer.v2.WindowReason
import app.logdate.client.sync.datalayer.v2.WindowResyncResponse
import app.logdate.client.sync.datalayer.v2.WindowTombstoneDataMap
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

data class WindowItem(
    val sourceNodeId: String,
    val path: String,
    val data: Map<String, String>,
)

data class WindowApplied(
    val applied: Int,
    val removed: Int,
)

/** Serializes live updates and snapshots, with local edits and the exact link checked inside Room. */
class WindowApplier(
    private val peers: WatchDeliveryPeer,
    private val notes: SyncableJournalNotesRepository,
    private val outbox: SyncMetadataService,
    private val members: WindowMembershipStore,
    private val transactions: SyncTransactionManager,
    private val forgetMedia: suspend (Uuid) -> Unit,
    private val media: StoredMediaReferences = StoredMediaReferences.Unchanged,
    private val prepareNote: suspend (JournalNote) -> Unit = {},
    private val momentWindow: MomentWindowApplier? = null,
) {
    private val applying = Mutex()
    private val mapper = NoteDataMapper()

    private data class Copy(
        val note: JournalNote,
        val ref: WindowMemberRef,
        val reasons: Set<WindowReason>,
    )

    private class GuardChanged : IllegalStateException()

    suspend fun onItem(item: WindowItem): Boolean =
        applying.withLock {
            val peer = peers.current() ?: return@withLock false
            if (WearPaths.parseWindowItem(item.path)?.kind == RecordKind.MOMENT) return@withLock momentWindow?.onItem(item) ?: false
            val copy = decode(peer, item) ?: return@withLock false
            val state = members.read(peer)
            val snapshot = state.snapshotRevision ?: return@withLock false
            if (copy.ref.revision <= snapshot && state.expected[copy.ref.id] != copy.ref) return@withLock false
            apply(peer, copy)
        }

    suspend fun onTombstone(item: WindowItem): Boolean =
        applying.withLock {
            val peer = peers.current() ?: return@withLock false
            val path = WearPaths.parseWindowTombstone(item.path) ?: return@withLock false
            if (path.kind == RecordKind.MOMENT) return@withLock momentWindow?.onTombstone(item) ?: false
            if (item.sourceNodeId != peer.phoneNode || path.linkId != peer.linkId || path.kind != RecordKind.NOTE) return@withLock false
            val context = item.data[WindowTombstoneDataMap.KEY_CONTEXT] ?: return@withLock false
            if ((WearJson.decode(context, WearProtocol.WINDOW_SCHEMA, PeerDeliveryContext.serializer()) as? Decoded.Supported)?.value !=
                peer
            ) {
                return@withLock false
            }
            if (item.data[WindowTombstoneDataMap.KEY_REMOVED_AT]?.toLongOrNull()?.takeIf { it >= 0 } == null) return@withLock false
            val revision = item.data[WindowTombstoneDataMap.KEY_REVISION]?.toLongOrNull()?.takeIf { it > 0 } ?: return@withLock false
            val state = members.read(peer)
            val snapshot = state.snapshotRevision ?: return@withLock false
            if (revision <= snapshot && path.id.toString() in state.expected) return@withLock false
            if ((state.entries[path.id.toString()]?.revision ?: 0) >= revision) return@withLock false
            remove(peer, path.id.toString(), revision)
        }

    suspend fun reconcile(
        peer: PeerDeliveryContext,
        response: WindowResyncResponse,
        items: List<WindowItem>,
    ): WindowApplied? =
        applying.withLock {
            val previous = previousSnapshot(peer, response) ?: return@withLock null
            if (momentWindow != null) {
                val deleted = momentWindow.applyDeletions(peer, items) ?: return@withLock null
                if (response.members.any { it.kind == RecordKind.MOMENT && it.id in deleted }) return@withLock null
            }
            val expected = response.members.filter { it.kind == RecordKind.NOTE }.associateBy { it.id }
            if (momentWindow == null && expected.size != response.members.size) return@withLock null
            if (momentWindow != null && !momentWindow.complete(peer, response, items)) return@withLock null
            val copies = items.mapNotNull { decode(peer, it) }.associateBy { it.ref }
            // A response can reach the watch before its DataItems. Never prune on that partial view.
            for (ref in expected.values) {
                if (ref in copies) continue
                val entry = previous.entries[ref.id]
                if (entry?.revision != ref.revision ||
                    entry.contentVersion != ref.contentVersion ||
                    notes.getNoteById(Uuid.parse(ref.id)) == null
                ) {
                    return@withLock null
                }
            }
            if (!peers.isCurrent(peer)) return@withLock null
            members.write(peer, previous.copy(snapshotRevision = response.revision, expected = expected))
            val momentResult = momentWindow?.reconcile(peer, response, items)
            if (momentWindow != null && momentResult == null) return@withLock null
            var applied = momentResult?.applied ?: 0
            for (ref in expected.values) {
                val copy = copies[ref] ?: continue
                if (apply(peer, copy)) applied++
                if (!peers.isCurrent(peer)) return@withLock null
            }
            if (momentWindow != null && !momentWindow.complete(peer, response, items)) return@withLock null
            var removed = momentResult?.removed ?: 0
            for (id in members.read(peer).entries.keys - expected.keys) {
                if (remove(peer, id, response.revision)) removed++
                if (!peers.isCurrent(peer)) return@withLock null
            }
            val settled = members.read(peer)
            members.write(peer, settled.copy(retired = settled.retired.filterValues { it > response.revision }))
            WindowApplied(applied, removed)
        }

    private suspend fun previousSnapshot(
        peer: PeerDeliveryContext,
        response: WindowResyncResponse,
    ): WindowMembership? {
        if (!peers.isCurrent(peer) ||
            response.context != peer ||
            response.schema != WearProtocol.WINDOW_SCHEMA ||
            response.revision < 0
        ) {
            return null
        }
        val previous = members.read(peer)
        if (response.revision < (previous.snapshotRevision ?: 0)) return null
        if (response.members.size > 400 ||
            response.members
                .map { it.kind to it.id }
                .toSet()
                .size != response.members.size
        ) {
            return null
        }
        if (response.members.any {
                Uuid.parseOrNull(it.id)?.toString() != it.id ||
                    it.contentVersion < 0 ||
                    it.revision <= 0 ||
                    it.revision > response.revision ||
                    it.kind !in setOf(RecordKind.NOTE, RecordKind.MOMENT)
            }
        ) {
            return null
        }
        return previous
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
                path.kind != RecordKind.NOTE ||
                envelope.kind != RecordKind.NOTE ||
                envelope.origin != RecordOrigin.PHONE ||
                envelope.context != peer ||
                envelope.id != path.id.toString() ||
                envelope.removed ||
                envelope.body == null ||
                envelope.bodyRef != null ||
                revision <= 0
            ) {
                null
            } else {
                val decoded = NoteEnvelopeBody.decode(checkNotNull(envelope.body), mapper)
                val note =
                    if (decoded is JournalNote.Audio) {
                        decoded.copy(
                            transcript =
                                decoded.transcript
                                    ?: decoded.transcription.takeIf { it.isNotBlank() }?.let { TranscriptDocument.fromPlainText(it) },
                            transcription = "",
                        )
                    } else {
                        decoded
                    }
                if (note.uid != path.id ||
                    note.lastUpdated.toEpochMilliseconds() != envelope.contentVersion ||
                    envelope.contentVersion < 0 ||
                    note.syncVersion != envelope.syncVersion
                ) {
                    null
                } else {
                    Copy(note, WindowMemberRef(RecordKind.NOTE, note.uid.toString(), envelope.contentVersion, revision), envelope.reasons)
                }
            }
        } catch (e: IllegalArgumentException) {
            Napier.w("Unreadable phone window note; it waits for verified resync", e)
            null
        }

    private suspend fun apply(
        peer: PeerDeliveryContext,
        copy: Copy,
    ): Boolean {
        val id = copy.ref.id
        val state = members.read(peer)
        val entry = state.entries[id]
        if ((state.retired[id] ?: 0) >= copy.ref.revision || (entry?.revision ?: 0) > copy.ref.revision) return false
        if (!peers.isCurrent(peer) || pending(id)) return false
        val before = notes.getNoteById(copy.note.uid)
        val wanted = fingerprint(copy.note)
        // A matching phone echo may settle an edit to a known projection, never adopt an unknown capture.
        if (before != null && (entry == null || (fingerprint(before) !in entry.fingerprints && fingerprint(before) != wanted))) return false
        if (entry?.revision == copy.ref.revision && wanted != entry.bodyFingerprint) return false
        val intent =
            WindowProjection(
                copy.ref.revision,
                copy.ref.contentVersion,
                setOfNotNull(before?.let(::fingerprint), wanted),
                wanted,
                copy.reasons,
            )
        members.write(peer, state.copy(entries = state.entries + (id to intent)))
        var accepted = wanted
        try {
            transactions.withTransaction {
                guard(peer, id)
                val current = notes.getNoteById(copy.note.uid)
                if (current?.let(::fingerprint) != before?.let(::fingerprint)) throw GuardChanged()
                forgetChangedAudio(peer, copy, current, before)
                prepareNote(copy.note)
                guard(peer, id)
                notes.createFromSync(copy.note)
                guard(peer, id)
                accepted = fingerprint(notes.getNoteById(copy.note.uid) ?: throw GuardChanged())
                if (accepted != wanted) {
                    members.write(
                        peer,
                        state.copy(
                            entries =
                                state.entries + (id to intent.copy(fingerprints = setOfNotNull(before?.let(::fingerprint), accepted))),
                        ),
                    )
                    guard(peer, id)
                }
            }
        } catch (_: GuardChanged) {
            return false
        }
        if (!peers.isCurrent(peer)) return false
        val updated = members.read(peer)
        members.write(
            peer,
            updated.copy(
                entries = updated.entries + (id to intent.copy(fingerprints = setOf(accepted))),
                retired =
                    updated.retired - id,
            ),
        )
        return true
    }

    private suspend fun forgetChangedAudio(
        peer: PeerDeliveryContext,
        copy: Copy,
        current: JournalNote?,
        before: JournalNote?,
    ) {
        if (current is JournalNote.Audio &&
            (
                copy.note !is JournalNote.Audio ||
                    current.mediaRef != copy.note.mediaRef ||
                    current.lastUpdated != copy.note.lastUpdated
            )
        ) {
            forgetMedia(current.uid)
            guard(peer, copy.ref.id)
            if (notes.getNoteById(current.uid)?.let(::fingerprint) != before?.let(::fingerprint)) throw GuardChanged()
        }
    }

    private suspend fun remove(
        peer: PeerDeliveryContext,
        id: String,
        revision: Long,
    ): Boolean {
        val state = members.read(peer)
        val entry = state.entries[id]
        if ((entry?.revision ?: 0) > revision || (state.retired[id] ?: 0) > revision || !peers.isCurrent(peer)) return false
        members.write(peer, state.copy(retired = state.retired + (id to revision)))
        if (entry == null) return false
        if (pending(id)) return false
        val uuid = Uuid.parse(id)
        try {
            transactions.withTransaction {
                guard(peer, id)
                val local = notes.getNoteById(uuid)
                if (local != null && fingerprint(local) !in entry.fingerprints) throw GuardChanged()
                notes.deleteFromSync(uuid)
                guard(peer, id)
            }
        } catch (_: GuardChanged) {
            return false
        }
        if (!peers.isCurrent(peer)) return false
        forgetMedia(uuid)
        if (!peers.isCurrent(peer)) return false
        val updated = members.read(peer)
        members.write(peer, updated.copy(entries = updated.entries - id))
        return true
    }

    private suspend fun guard(
        peer: PeerDeliveryContext,
        id: String,
    ) {
        if (!peers.isCurrent(peer) || pending(id)) throw GuardChanged()
    }

    private suspend fun pending(id: String) = outbox.hasPending(EntityType.NOTE, id)

    private fun fingerprint(note: JournalNote): String = windowNoteFingerprint(note, media)
}
