package app.logdate.client.sync.v2.receipts

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.sync.FileWatchAudioStore
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.WatchCaptureReceipt
import app.logdate.client.sync.WatchNoteIngestor
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.audioDigest
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.DeliveryPriority
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.MediaRef
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.ReceiptQuery
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.datalayer.v2.ReceiptsResponse
import app.logdate.client.sync.datalayer.v2.RecordEnvelope
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.v2.FakeSessions
import app.logdate.client.sync.v2.MemoryPeerDeliveryDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OperationCaptureIngestionTest {
    private val root = Files.createTempDirectory("operation-capture").toFile()
    private val peer = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.PAIR)
    private val peers = mockk<PhoneDeliveryPeers>()
    private val notes = mockk<SyncableJournalNotesRepository>()
    private val outbox = mockk<SyncMetadataService>()
    private val dao = MemoryPeerDeliveryDao()
    private val ledger = PeerDeliveryLedger(dao)
    private val book = PhoneReceiptBook(notes, outbox, FakeSessions(null), ledger, peers)
    private val sent = mutableListOf<ReceiptsResponse>()
    private val notifier =
        WatchReceiptNotifier(
            book,
            ledger,
            WatchPeerMessageSender { _, _, bytes ->
                sent += (WearJson.decode(bytes, 1, ReceiptsResponse.serializer()) as Decoded.Supported).value
                true
            },
            { true },
            peers,
        )
    private val text =
        JournalNote.Text(
            creationTimestamp = Instant.fromEpochMilliseconds(1),
            lastUpdated = Instant.fromEpochMilliseconds(5),
            content = "watch capture",
        )
    private val stored = mutableMapOf(text.uid to (text as JournalNote))
    private val mapper = NoteDataMapper()
    private var resolveFile: (String) -> String? = { it }
    private val transactions =
        object : SyncTransactionManager {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        }
    private val removals = WatchCaptureRemovals(notes, transactions, dao, peers, notifier, mockk(relaxed = true), outbox)
    private val ingestion =
        ScopedCaptureIngestion(peers, removals) { context, operation ->
            val guarded =
                WatchCaptureNotes(
                    notes,
                    transactions,
                    outbox,
                    { peers.isCurrent(context) },
                    { },
                    { },
                    canStore = { !removals.isRemoved(context, it) },
                )
            val store = store(operation)
            val acknowledger = ReceiptAcknowledger(ledger, notifier, context, operation)
            val ingestor =
                WatchNoteIngestor(
                    guarded,
                    store,
                    acknowledger,
                    { },
                    mapper,
                    proveExistingAudio =
                        if (operation == null) {
                            null
                        } else {
                            { note, data ->
                                WatchCaptureReceipt.existingAudioMatches(note, data, resolveFile)
                            }
                        },
                )
            WatchRecordIngestorV2(
                ingestor::onNoteMetadata,
                { id, input -> ingestor.onAudioBytes(id, input) },
                guarded,
                acknowledger,
                RecordRejection { _, _ -> },
                operationId = operation,
            )
        }

    init {
        coEvery { peers.current("watch") } returns peer
        coEvery { peers.isCurrent(peer) } returns true
        coEvery { peers.all() } returns listOf(peer)
        coEvery { notes.getNoteById(any()) } answers { stored[firstArg()] }
        coEvery { notes.deleteFromSync(any()) } answers {
            stored.remove(firstArg())
        }
        coEvery { notes.create(any<JournalNote>()) } answers {
            val note = firstArg<JournalNote>()
            stored[note.uid] = note
            note.uid
        }
        coEvery { notes.createFromSync(any()) } answers {
            val note = firstArg<JournalNote>()
            val previous = stored[note.uid]
            if (previous == null || previous.lastUpdated < note.lastUpdated) stored[note.uid] = note
        }
        coEvery { outbox.hasPending(any(), any()) } returns false
        coEvery { outbox.enqueuePending(any(), any(), any()) } returns Unit
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `stored text yields a receipt only for its captured operation`() =
        runTest {
            send(text, "operation")
            assertEquals(
                "operation",
                sent
                    .single()
                    .receipts
                    .single()
                    .operationId,
            )
            assertEquals(ReceiptState.ON_PHONE, receipt("operation")?.state)
            assertNull(receipt("new-operation"))
        }

    @Test
    fun `equal timestamp with different text cannot certify a new edit`() =
        runTest {
            send(text.copy(content = "new edit"), "new-operation")
            assertEquals(text, stored[text.uid])
            assertTrue(sent.isEmpty())
            assertNull(receipt("new-operation"))
        }

    @Test
    fun `metadata then audio stores a complete capture and proves its operation after restart`() =
        runTest {
            stored.clear()
            val source = File(root, "watch.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val note = audio(source.absolutePath)
            send(note, "audio-operation", media(source))
            assertNull(stored[text.uid])
            assertNull(receipt("audio-operation"))
            // Each event creates a fresh ingestor, exercising the persisted staging path.
            ingestion.onMedia("watch", WearPaths.recordMedia(RecordKind.NOTE, note.uid, peer, "audio-operation"), source.inputStream())
            val saved = stored[text.uid] as JournalNote.Audio
            assertContentEquals(byteArrayOf(1, 2, 3), File(saved.mediaRef).readBytes())
            assertEquals("audio-operation", receipt("audio-operation")?.operationId)
        }

    @Test
    fun `audio then metadata resumes the same operation and never acknowledges bytes alone`() =
        runTest {
            stored.clear()
            val source = File(root, "watch.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val note = audio(source.absolutePath)
            ingestion.onMedia("watch", WearPaths.recordMedia(RecordKind.NOTE, note.uid, peer, "audio-operation"), source.inputStream())
            assertTrue(sent.isEmpty())
            send(note, "audio-operation", media(source))
            assertEquals(ReceiptState.ON_PHONE, receipt("audio-operation")?.state)
            assertContentEquals(source.readBytes(), File((stored[text.uid] as JournalNote.Audio).mediaRef).readBytes())
        }

    @Test
    fun `an existing recording is not proof that a new operation's bytes were received`() =
        runTest {
            val phone = File(root, "phone.m4a").apply { writeBytes(byteArrayOf(1)) }
            stored[text.uid] = audio(phone.absolutePath)
            ingestion.onMedia(
                "watch",
                WearPaths.recordMedia(RecordKind.NOTE, text.uid, peer, "new-audio"),
                ByteArrayInputStream(byteArrayOf(2)),
            )
            assertTrue(sent.isEmpty())
            assertNull(receipt("new-audio"))
            val different = File(root, "different.m4a").apply { writeBytes(byteArrayOf(2)) }
            send(audio("/watch/recording.m4a"), "new-audio", media(different))
            assertTrue(sent.isEmpty())
            assertNull(receipt("new-audio"))
            assertContentEquals(byteArrayOf(1), phone.readBytes())
        }

    @Test
    fun `matching existing audio metadata and bytes can acknowledge a redelivery`() =
        runTest {
            val phone = File(root, "phone.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
            stored[text.uid] = audio(phone.absolutePath)
            send(audio("/watch/recording.m4a"), "audio-operation", media(phone))
            assertEquals("audio-operation", receipt("audio-operation")?.operationId)
        }

    @Test
    fun `matching stored metadata cannot certify different recording bytes`() =
        runTest {
            val phone = File(root, "phone.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
            val incoming = File(root, "watch.m4a").apply { writeBytes(byteArrayOf(3, 4)) }
            stored[text.uid] = audio(phone.absolutePath)
            send(audio("/watch/recording.m4a"), "different-bytes", media(incoming))
            assertTrue(sent.isEmpty())
            assertNull(receipt("different-bytes"))
            assertContentEquals(byteArrayOf(1, 2), phone.readBytes())
        }

    @Test
    fun `matching recording bytes cannot certify different audio metadata`() =
        runTest {
            val phone = File(root, "phone.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
            stored[text.uid] = audio(phone.absolutePath)
            send(audio("/watch/recording.m4a").copy(durationMs = 1000), "different-metadata", media(phone))
            assertTrue(sent.isEmpty())
            assertNull(receipt("different-metadata"))
        }

    @Test
    fun `separate operations cannot share staging or erase each other's recording`() =
        runTest {
            val first = store("first")
            val second = store("second")
            first.stashMetadata(text.uid, mapOf("operation" to "first"))
            first.writeAudio(text.uid, ByteArrayInputStream(byteArrayOf(1)))
            second.stashMetadata(text.uid, mapOf("operation" to "second"))
            second.writeAudio(text.uid, ByteArrayInputStream(byteArrayOf(2)))
            assertNotEquals(first.audioPath(text.uid), second.audioPath(text.uid))
            first.discard(text.uid)
            assertContentEquals(byteArrayOf(2), File(second.audioPath(text.uid)).readBytes())
            assertEquals(mapOf("operation" to "second"), second.stashedMetadata(text.uid))
        }

    @Test
    fun `stored media references are resolved before proving recording bytes`() =
        runTest {
            val phone = File(root, "owned.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
            stored[text.uid] = audio("logdate-media://audio/owned.m4a")
            resolveFile = { if (it == "logdate-media://audio/owned.m4a") phone.absolutePath else null }
            send(audio("/watch/recording.m4a"), "owned-audio", media(phone))
            assertEquals("owned-audio", receipt("owned-audio")?.operationId)
        }

    @Test
    fun `an unavailable stored recording cannot produce a receipt`() =
        runTest {
            val phone = File(root, "owned.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
            stored[text.uid] = audio("logdate-media://audio/owned.m4a")
            resolveFile = { null }
            send(audio("/watch/recording.m4a"), "owned-audio", media(phone))
            assertTrue(sent.isEmpty())
            assertNull(receipt("owned-audio"))
        }

    @Test
    fun `cancelling recording proof cannot acknowledge a capture`() =
        runTest {
            val phone = File(root, "owned.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
            stored[text.uid] = audio(phone.absolutePath)
            resolveFile = { throw CancellationException("stopped") }
            assertFailsWith<CancellationException> { send(audio("/watch/recording.m4a"), "owned-audio", media(phone)) }
            assertTrue(sent.isEmpty())
            assertNull(receipt("owned-audio"))
        }

    @Test
    fun `a scoped removal prevents later text and recording events from restoring a note`() =
        runTest {
            val removal =
                RecordEnvelope(
                    kind = RecordKind.NOTE,
                    id = text.uid.toString(),
                    contentVersion = 0,
                    origin = RecordOrigin.WATCH,
                    context = peer,
                    operationId = "remove-operation",
                    removed = true,
                )
            ingestion.onRecord("watch", WearPaths.watchRecord(RecordKind.NOTE, text.uid), EnvelopeDataMap.toDataMap(removal, 1))
            assertNull(stored[text.uid])
            send(text, "late-operation")
            val source = File(root, "late.m4a").apply { writeBytes(byteArrayOf(7, 8)) }
            val note = audio(source.absolutePath)
            ingestion.onMedia("watch", WearPaths.recordMedia(RecordKind.NOTE, note.uid, peer, "late-audio"), source.inputStream())
            send(note, "late-audio", media(source))
            assertNull(stored[text.uid])
            assertEquals(
                ReceiptState.DELETED_ON_PHONE,
                book.receiptFor(peer, ReceiptQuery(RecordKind.NOTE, text.uid.toString(), 0, "remove-operation", removed = true))?.state,
            )
            assertFalse(File(root, "incoming/v2/${peer.linkPathSegment}/operations/late-audio").walkTopDown().any { it.isFile })
        }

    @Test
    fun `a removal on another note path cannot delete the envelope note`() =
        runTest {
            val removal =
                RecordEnvelope(
                    kind = RecordKind.NOTE,
                    id = text.uid.toString(),
                    contentVersion = 0,
                    origin = RecordOrigin.WATCH,
                    context = peer,
                    operationId = "remove-operation",
                    removed = true,
                )
            ingestion.onRecord(
                "watch",
                WearPaths.watchRecord(RecordKind.NOTE, kotlin.uuid.Uuid.random()),
                EnvelopeDataMap.toDataMap(removal, 1),
            )
            assertEquals(text, stored[text.uid])
            assertFalse(removals.isRemoved(peer, text.uid))
        }

    @Test
    fun `a removal item on an earlier link path cannot delete a current capture`() =
        runTest {
            val removal =
                RecordEnvelope(
                    kind = RecordKind.NOTE,
                    id = text.uid.toString(),
                    contentVersion = 0,
                    origin = RecordOrigin.WATCH,
                    context = peer,
                    operationId = "remove-operation",
                    removed = true,
                )
            val old = peer.copy(linkId = LinkWire.encodeKey(ByteArray(32) { 2 }))
            ingestion.onRecord(
                "watch",
                WearPaths.watchRecord(RecordKind.NOTE, text.uid, old, "remove-operation"),
                EnvelopeDataMap.toDataMap(removal, 1),
            )
            assertEquals(text, stored[text.uid])
        }

    private fun store(operation: String?) =
        FileWatchAudioStore.scoped(
            File(root, "audio"),
            File(root, "incoming"),
            peer,
            operationId = operation,
        )

    private fun audio(path: String) =
        JournalNote.Audio(
            uid = text.uid,
            mediaRef = path,
            creationTimestamp = text.creationTimestamp,
            lastUpdated = text.lastUpdated,
        )

    private suspend fun media(file: File): List<MediaRef> {
        val digest = file.audioDigest()
        return listOf(MediaRef(digest.sha256Hex, digest.sizeBytes, "audio/mp4"))
    }

    private suspend fun send(
        note: JournalNote,
        operation: String,
        media: List<MediaRef> = emptyList(),
    ) {
        val envelope =
            NoteEnvelopeBody
                .envelope(note, RecordOrigin.WATCH, DeliveryPriority.URGENT, media = media)
                .copy(context = peer, operationId = operation)
        ingestion.onRecord("watch", WearPaths.watchRecord(RecordKind.NOTE, note.uid), EnvelopeDataMap.toDataMap(envelope, 1))
    }

    private suspend fun receipt(operation: String) =
        book.receiptFor(
            peer,
            ReceiptQuery(RecordKind.NOTE, text.uid.toString(), 5, operation),
        )
}
