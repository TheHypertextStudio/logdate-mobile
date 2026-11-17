package app.logdate.client.sync.v2.receipts

import app.logdate.client.data.moments.OfflineFirstMarkedMomentRepository
import app.logdate.client.database.dao.moments.MarkedMomentDao
import app.logdate.client.database.entities.moments.MarkedMomentEntity
import app.logdate.client.repository.moments.MarkState
import app.logdate.client.repository.moments.MarkedMoment
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.ReceiptQuery
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.datalayer.v2.ReceiptsResponse
import app.logdate.client.sync.datalayer.v2.RecordEnvelope
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.MomentEnvelopeBody
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.v2.FakeSessions
import app.logdate.client.sync.v2.MemoryPeerDeliveryDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WatchMomentIngestorTest {
    private val rows = MutableStateFlow<Map<Uuid, MarkedMomentEntity>>(emptyMap())
    private val marks =
        object : MarkedMomentDao {
            override suspend fun upsert(moment: MarkedMomentEntity) {
                rows.value = rows.value + (moment.id to moment)
            }

            override suspend fun getById(id: Uuid) = rows.value[id]

            override suspend fun liveIds() =
                rows.value.values
                    .filter { it.deletedAt == null }
                    .map { it.id }

            override fun observeDeleted() = rows.map { it.values.filter { row -> row.deletedAt != null } }

            override fun observeByState(state: String) =
                rows.map { it.values.filter { row -> row.state == state && row.deletedAt == null } }

            override suspend fun fillLocationIfMissing(
                id: Uuid,
                latitude: Double,
                longitude: Double,
                accuracy: Float?,
                lastUpdated: Instant,
            ): Int = error("Unused")
        }
    private val transactions =
        object : SyncTransactionManager {
            override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
        }
    private val outbox = mockk<SyncMetadataService>()
    private val queued = mutableMapOf<String, PendingOperation>()
    private val moments = OfflineFirstMarkedMomentRepository(marks, transactions, outbox)
    private val deliveries = MemoryPeerDeliveryDao()
    private val peers = mockk<PhoneDeliveryPeers>()
    private val peer = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.PAIR)
    private var current = peer
    private var enabled = true
    private var syncs = 0
    private val at = Instant.fromEpochMilliseconds(100)
    private val moment = MarkedMoment(Uuid.random(), at, "UTC", null, 72, MarkState.OPEN, "", emptySet(), "watch-device", at, 0)
    private val operation = "capture-1"
    private val ingestor =
        WatchMomentIngestor(
            moments,
            marks,
            deliveries,
            outbox,
            transactions,
            peers,
            mockk(relaxed = true),
            { enabled },
            { syncs++ },
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(200)
            },
        )
    private val book = MomentReceiptBook(marks, deliveries, outbox, peers) { enabled }

    init {
        coEvery { peers.current(any()) } answers { current.takeIf { it.watchNode == firstArg<String>() } }
        coEvery { peers.all() } answers { listOf(current) }
        coEvery { peers.isCurrent(any()) } answers { current == firstArg<PeerDeliveryContext>() }
        coEvery { outbox.enqueuePending(any(), EntityType.MOMENT, any()) } answers {
            queued[firstArg()] = thirdArg()
        }
        coEvery { outbox.hasPending(EntityType.MOMENT, any()) } answers { secondArg<String>() in queued }
    }

    @Test fun captureAndExactOperationReceiptAreStoredWithoutTrustingWatchServerVersion() =
        runTest {
            send(envelope(moment.copy(syncVersion = 900)))
            assertEquals(moment, moments.getMark(moment.id))
            assertEquals(PendingOperation.CREATE, queued[moment.id.toString()])
            assertEquals(1, syncs)
            val receipt = assertNotNull(book.receiptFor(peer, query()))
            assertEquals(ReceiptState.ON_PHONE, receipt.state)
            assertEquals(operation, receipt.operationId)
            assertNull(receipt.serverVersion)
            assertNull(book.receiptFor(peer, query().copy(operationId = "different-operation")))
        }

    @Test fun staleCaptureJoinsAttachmentsWithoutReopeningPhoneResolution() =
        runTest {
            val localNote = Uuid.random()
            val remoteNote = Uuid.random()
            moments.applyFromSync(
                moment.copy(state = MarkState.FILLED, caption = "Resolved", resolvedNoteIds = setOf(localNote), syncVersion = 7),
            )
            send(envelope(moment.copy(resolvedNoteIds = setOf(remoteNote))))
            val saved = assertNotNull(moments.getMark(moment.id))
            assertEquals(MarkState.FILLED, saved.state)
            assertEquals(setOf(localNote, remoteNote), saved.resolvedNoteIds)
            assertEquals(7L, saved.syncVersion)
            assertEquals(PendingOperation.UPDATE, queued[moment.id.toString()])
        }

    @Test fun duplicateCaptureDoesNotReplacePendingOperationOrScheduleAnotherBackup() =
        runTest {
            send(envelope())
            val count = syncs
            send(envelope())
            assertEquals(count, syncs)
            assertEquals(PendingOperation.CREATE, queued[moment.id.toString()])
            assertEquals(ReceiptState.ON_PHONE, book.receiptFor(peer, query())?.state)
        }

    @Test fun foreignSourceRelinkAndMissingCapabilityCannotStoreOrAcknowledge() =
        runTest {
            ingestor.onRecord("other-watch", path(), data(envelope()))
            enabled = false
            send(envelope())
            enabled = true
            current = peer.copy(linkId = LinkWire.encodeKey(ByteArray(32) { 2 }))
            send(envelope())
            assertNull(moments.getMark(moment.id))
            assertEquals(emptyMap(), queued)
            assertNull(book.receiptFor(peer, query()))
        }

    @Test fun mismatchedBodyIdentityVersionAndLegacyPathsHaveNoReceipt() =
        runTest {
            send(envelope().copy(id = Uuid.random().toString()))
            send(envelope().copy(contentVersion = 101))
            ingestor.onRecord("watch", WearPaths.watchRecord(RecordKind.MOMENT, moment.id), data(envelope()))
            assertNull(moments.getMark(moment.id))
            assertNull(book.receiptFor(peer, query()))
        }

    @Test fun deletedOnPhoneIsNotRevivedByCaptureReplay() =
        runTest {
            moments.applyFromSync(moment)
            moments.delete(moment.id, Instant.fromEpochMilliseconds(200))
            send(envelope())
            assertNull(moments.getMark(moment.id))
            assertEquals(PendingOperation.DELETE, queued[moment.id.toString()])
            assertEquals(ReceiptState.DELETED_ON_PHONE, book.receiptFor(peer, query())?.state)
        }

    @Test fun removalBeforeCaptureRetainsTombstoneAndExactRemovalReceipt() =
        runTest {
            val removed = envelope().copy(removed = true, body = null, contentVersion = 0)
            send(removed)
            assertNotNull(rows.value[moment.id]?.deletedAt)
            assertEquals(PendingOperation.DELETE, queued[moment.id.toString()])
            assertEquals(ReceiptState.DELETED_ON_PHONE, book.receiptFor(peer, query().copy(contentVersion = 0, removed = true))?.state)
            send(envelope())
            assertNull(moments.getMark(moment.id))
            assertEquals(PendingOperation.DELETE, queued[moment.id.toString()])
        }

    @Test fun onlyConfirmedCloudProjectionCanBeReportedBackedUp() =
        runTest {
            val cloudPeer = peer.copy(mode = LinkMode.CLOUD, accountId = "owner", serverOrigin = "https://cloud.test")
            current = cloudPeer
            ingestor.onRecord(
                "watch",
                WearPaths.watchRecord(RecordKind.MOMENT, moment.id, cloudPeer, operation),
                data(envelope().copy(context = cloudPeer)),
            )
            moments.applyFromSync(moment.copy(syncVersion = 7))
            queued.clear()
            val receipt = assertNotNull(book.receiptFor(cloudPeer, query()))
            assertEquals(ReceiptState.BACKED_UP, receipt.state)
            assertEquals(7L, receipt.serverVersion)
            assertNull(book.receiptFor(cloudPeer, query().copy(contentVersion = 101)))
        }

    @Test fun scopedListenerDispatchesMomentToDedicatedIngestor() =
        runTest {
            val scoped = ScopedCaptureIngestion(peers, moments = ingestor) { _, _ -> error("Moment reached note ingestion") }
            scoped.onRecord("watch", path(), data(envelope()))
            assertEquals(moment, moments.getMark(moment.id))
            assertEquals(ReceiptState.ON_PHONE, book.receiptFor(peer, query())?.state)
        }

    @Test fun notifierPushesMomentAndLaterBackupProofThroughSharedReceiptBook() =
        runTest {
            val cloudPeer = peer.copy(mode = LinkMode.CLOUD, accountId = "owner", serverOrigin = "https://cloud.test")
            current = cloudPeer
            ingestor.onRecord(
                "watch",
                WearPaths.watchRecord(RecordKind.MOMENT, moment.id, cloudPeer, operation),
                data(envelope().copy(context = cloudPeer)),
            )
            val ledger = PeerDeliveryLedger(deliveries)
            val shared = PhoneReceiptBook(mockk(), outbox, FakeSessions(null), ledger, peers, book)
            val pushed = mutableListOf<ReceiptsResponse>()
            val notifier =
                WatchReceiptNotifier(
                    shared,
                    ledger,
                    WatchPeerMessageSender { _, _, bytes ->
                        pushed +=
                            (WearJson.decode(bytes, WearProtocol.RECEIPTS_SCHEMA, ReceiptsResponse.serializer()) as Decoded.Supported).value
                        true
                    },
                    { true },
                    peers,
                )
            assertEquals(true, notifier.push(cloudPeer, moment.id.toString(), 100, operation, kind = RecordKind.MOMENT))
            assertEquals(
                RecordKind.MOMENT,
                pushed
                    .single()
                    .receipts
                    .single()
                    .kind,
            )
            assertEquals(
                ReceiptState.ON_PHONE,
                pushed
                    .single()
                    .receipts
                    .single()
                    .state,
            )
            moments.applyFromSync(moment.copy(syncVersion = 7))
            queued.clear()
            pushed.clear()
            assertEquals(1, notifier.pushBackedUp())
            assertEquals(
                ReceiptState.BACKED_UP,
                pushed
                    .single()
                    .receipts
                    .single()
                    .state,
            )
            assertEquals(0, notifier.pushBackedUp())
        }

    private fun envelope(value: MarkedMoment = moment) =
        MomentEnvelopeBody.envelope(value, RecordOrigin.WATCH).copy(context = peer, operationId = operation)

    private fun path() = WearPaths.watchRecord(RecordKind.MOMENT, moment.id, peer, operation)

    private fun data(value: RecordEnvelope) = EnvelopeDataMap.toDataMap(value, sentAt = 100)

    private suspend fun send(value: RecordEnvelope) = ingestor.onRecord("watch", path(), data(value))

    private fun query() = ReceiptQuery(RecordKind.MOMENT, moment.id.toString(), 100, operation)
}
