package app.logdate.wear.sync.v2.window

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.datalayer.v2.DeliveryPriority
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WindowMemberRef
import app.logdate.client.sync.datalayer.v2.WindowResyncResponse
import app.logdate.client.sync.datalayer.v2.WindowTombstoneDataMap
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.wear.sync.v2.MemoryKeyValueStorage
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WindowFixture {
    val peer = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.PAIR)
    var current: PeerDeliveryContext? = peer
    val peers =
        mockk<WatchDeliveryPeer> {
            coEvery { current() } answers { current }
            coEvery { isCurrent(any()) } answers { firstArg<PeerDeliveryContext>() == current }
        }
    val stored = mutableMapOf<Uuid, JournalNote>()
    val pending = mutableSetOf<String>()
    val backing = MemoryKeyValueStorage()
    var beforeStoreWrite: () -> Unit = {}
    val storage =
        object : KeyValueStorage by backing {
            override suspend fun putString(
                key: String,
                value: String,
            ) {
                beforeStoreWrite()
                backing.putString(key, value)
            }
        }
    val members = WindowMembershipStore(storage)
    val forgotten = mutableListOf<Uuid>()
    var afterWrite: () -> Unit = {}
    var beforeTransaction: () -> Unit = {}
    var afterTransaction: () -> Unit = {}
    val notes =
        mockk<SyncableJournalNotesRepository> {
            coEvery { getNoteById(any()) } answers { stored[firstArg()] }
            coEvery { createFromSync(any()) } answers {
                val note = firstArg<JournalNote>()
                stored[note.uid] = note
                afterWrite()
            }
            coEvery { deleteFromSync(any()) } answers {
                stored.remove(firstArg<Uuid>())
                afterWrite()
            }
        }
    val outbox =
        mockk<SyncMetadataService> {
            coEvery { hasPending(any(), any()) } answers { secondArg<String>() in pending }
        }
    val transactions =
        object : SyncTransactionManager {
            override suspend fun <T> withTransaction(block: suspend () -> T): T {
                beforeTransaction()
                val before = stored.toMap()
                return try {
                    block().also { afterTransaction() }
                } catch (e: Exception) {
                    stored.clear()
                    stored.putAll(before)
                    throw e
                }
            }
        }
    val applier = WindowApplier(peers, notes, outbox, members, transactions, { forgotten += it })
    val note =
        JournalNote.Text(
            uid = Uuid.random(),
            creationTimestamp = Instant.fromEpochMilliseconds(1),
            lastUpdated = Instant.fromEpochMilliseconds(5),
            content = "phone copy",
        )

    fun item(
        note: JournalNote = this.note,
        revision: Long = 1,
        context: PeerDeliveryContext = peer,
        reasons: Set<app.logdate.client.sync.datalayer.v2.WindowReason> = emptySet(),
    ): WindowItem {
        val envelope = NoteEnvelopeBody.envelope(note, RecordOrigin.PHONE, DeliveryPriority.BULK).copy(context = context, reasons = reasons)
        return WindowItem(
            context.phoneNode,
            WearPaths.windowItem(RecordKind.NOTE, note.uid, context),
            EnvelopeDataMap.toDataMap(envelope, 1) + (WindowTombstoneDataMap.KEY_REVISION to revision.toString()),
        )
    }

    fun tombstone(revision: Long = 2) =
        WindowItem("phone", WearPaths.windowTombstone(RecordKind.NOTE, note.uid, peer), WindowTombstoneDataMap.toDataMap(9, peer, revision))

    fun response(
        notes: List<JournalNote> = listOf(note),
        revision: Long = 1,
    ) = WindowResyncResponse(
        context = peer,
        members =
            notes.map {
                WindowMemberRef(RecordKind.NOTE, it.uid.toString(), it.lastUpdated.toEpochMilliseconds(), revision)
            },
        publishedAt = 9,
        revision = revision,
    )

    suspend fun seed() = applier.reconcile(peer, response(), listOf(item()))
}
