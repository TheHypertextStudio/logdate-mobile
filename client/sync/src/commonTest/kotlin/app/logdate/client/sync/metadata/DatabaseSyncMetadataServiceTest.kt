package app.logdate.client.sync.metadata

import app.logdate.client.database.dao.sync.SyncMetadataDao
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.entities.sync.SyncCursorEntity
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.shared.config.DefaultLogDateConfigRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DatabaseSyncMetadataServiceTest {
    @Test
    fun `legacy create binding preserves newer mutations and makes old settlement stale`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val metadata = service(dao, "owner", "https://first.example")
            metadata.enqueuePending("legacy", EntityType.NOTE, PendingOperation.CREATE)
            val captured = metadata.getPendingUploads(EntityType.NOTE).single()
            assertTrue(metadata.bindCreateToServerVersion(EntityType.NOTE, captured, 42))
            val repair = metadata.getPendingUploads(EntityType.NOTE).single()
            assertEquals(PendingOperation.UPDATE, repair.operation)
            assertEquals(42L, repair.expectedServerVersion)
            kotlin.test.assertNotEquals(captured.operationId, repair.operationId)
            kotlin.test.assertFalse(metadata.settleIfCurrent(EntityType.NOTE, captured, Instant.fromEpochMilliseconds(10), 42))
            metadata.enqueuePending("legacy", EntityType.NOTE, PendingOperation.DELETE)
            val deletion = metadata.getPendingUploads(EntityType.NOTE).single()
            kotlin.test.assertFalse(metadata.bindCreateToServerVersion(EntityType.NOTE, captured, 43))
            assertEquals(deletion, metadata.getPendingUploads(EntityType.NOTE).single())
            val anotherServer = service(dao, "owner", "https://other.example")
            kotlin.test.assertFalse(anotherServer.bindCreateToServerVersion(EntityType.NOTE, captured, 43))
        }

    @Test
    fun `delete remains queued when a create may already have reached the server`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val metadata = service(dao, "owner", "https://first.example")
            metadata.enqueuePending("note", EntityType.NOTE, PendingOperation.CREATE)
            val transmittedCreate = metadata.getPendingUploads(EntityType.NOTE).single()
            metadata.enqueuePending("note", EntityType.NOTE, PendingOperation.DELETE)
            kotlin.test.assertFalse(metadata.settleIfCurrent(EntityType.NOTE, transmittedCreate, Instant.fromEpochMilliseconds(10), 1))
            assertEquals(PendingOperation.DELETE, metadata.getPendingUploads(EntityType.NOTE).single().operation)
        }

    @Test
    fun `pending operations retain ownership and stable retry identity across reopening`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val first = service(dao, "owner", "https://first.example")
            first.enqueuePending("note", EntityType.NOTE, PendingOperation.UPDATE)
            val captured = first.getPendingUploads(EntityType.NOTE).single()
            assertEquals(UploadScope("owner", "https://first.example"), captured.scope)
            assertNotNull(captured.operationId)
            assertTrue(
                app.logdate.shared.model.diagnostics.DiagnosticReportCodec
                    .isCorrelationId(captured.operationId),
            )
            first.incrementRetryCount("note", EntityType.NOTE)
            val reopened = service(dao, "owner", "https://first.example")
            assertEquals(captured.operationId, reopened.getPendingUploads(EntityType.NOTE).single().operationId)
            reopened.enqueuePending("note", EntityType.NOTE, PendingOperation.DELETE)
            kotlin.test.assertNotEquals(captured.operationId, reopened.getPendingUploads(EntityType.NOTE).single().operationId)
        }

    @Test
    fun `stale settlement cannot discard a newer deletion or work for another server`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val first = service(dao, "owner", "https://first.example")
            val second = service(dao, "owner", "https://second.example")
            first.enqueuePending("note", EntityType.NOTE, PendingOperation.UPDATE)
            val captured = first.getPendingUploads(EntityType.NOTE).single()
            first.enqueuePending("note", EntityType.NOTE, PendingOperation.DELETE)
            second.enqueuePending("note", EntityType.NOTE, PendingOperation.UPDATE)
            kotlin.test.assertFalse(second.settleIfCurrent(EntityType.NOTE, captured, Instant.fromEpochMilliseconds(10), 1))
            assertEquals(PendingOperation.DELETE, first.getPendingUploads(EntityType.NOTE).single().operation)
            assertEquals(PendingOperation.UPDATE, second.getPendingUploads(EntityType.NOTE).single().operation)
        }

    @Test
    fun `adopting matching legacy metadata retains the legacy rows`() =
        runTest {
            val ownerId = "2e10a582-197e-48fd-97df-5a1fc1669a9e"
            val serverOrigin = "https://cloud.logdate.app"
            val dao = InMemorySyncMetadataDao()
            dao.pendingRows +=
                PendingUploadEntity(
                    ownerId = "",
                    serverOrigin = serverOrigin,
                    entityType = EntityType.JOURNAL.name,
                    entityId = "journal-1",
                    operation = PendingOperation.CREATE.name,
                    createdAt = 1L,
                )
            dao.cursors +=
                SyncCursorEntity(
                    ownerId = "",
                    serverOrigin = serverOrigin,
                    entityType = EntityType.JOURNAL.name,
                    lastSyncTimestamp = 2L,
                )
            val service = service(dao, ownerId, serverOrigin)

            assertEquals(listOf("journal-1"), service.getPendingUploads(EntityType.JOURNAL).map { it.entityId })
            assertNotNull(service.getLastSyncTime(EntityType.JOURNAL))

            assertTrue(dao.pendingRows.any { it.ownerId.isEmpty() && it.entityId == "journal-1" })
            assertTrue(dao.cursors.any { it.ownerId.isEmpty() && it.entityType == EntityType.JOURNAL.name })
        }

    @Test
    fun `a settled upload does not come back from the legacy queue`() =
        runTest {
            // Promotion copies a legacy row forward rather than moving it. If settling only removed
            // the copy, the next read would promote the same row again -- an outbox that refills
            // itself after every successful sync and a pending count that can never reach zero.
            val ownerId = "2e10a582-197e-48fd-97df-5a1fc1669a9e"
            val serverOrigin = "https://cloud.logdate.app"
            val dao = InMemorySyncMetadataDao()
            dao.pendingRows +=
                PendingUploadEntity(
                    ownerId = "",
                    serverOrigin = serverOrigin,
                    entityType = EntityType.NOTE.name,
                    entityId = "note-1",
                    operation = PendingOperation.CREATE.name,
                    createdAt = 1L,
                )
            val service = service(dao, ownerId, serverOrigin)

            assertEquals(listOf("note-1"), service.getPendingUploads(EntityType.NOTE).map { it.entityId })
            service.markAsSynced("note-1", EntityType.NOTE, Instant.fromEpochMilliseconds(10L), 1L)

            assertEquals(emptyList(), service.getPendingUploads(EntityType.NOTE).map { it.entityId })
            assertEquals(0, service.getPendingCount())
        }

    @Test
    fun `the queue lists what is waiting and follows it as it drains`() =
        runTest {
            val ownerId = "2e10a582-197e-48fd-97df-5a1fc1669a9e"
            val serverOrigin = "https://cloud.logdate.app"
            val dao = InMemorySyncMetadataDao()
            val service = service(dao, ownerId, serverOrigin)
            service.enqueuePending("note-1", EntityType.NOTE, PendingOperation.CREATE)
            service.enqueuePending("media-1", EntityType.MEDIA, PendingOperation.CREATE)
            dao.pendingRows +=
                PendingUploadEntity(
                    ownerId = ownerId,
                    serverOrigin = "https://other.example",
                    entityType = EntityType.NOTE.name,
                    entityId = "elsewhere",
                    operation = PendingOperation.CREATE.name,
                    createdAt = 1L,
                )
            dao.publish()

            assertEquals(
                listOf(EntityType.NOTE to "note-1", EntityType.MEDIA to "media-1"),
                service.observePendingUploads().first().map { it.entityType to it.entityId },
            )

            service.markAsSynced("note-1", EntityType.NOTE, Instant.fromEpochMilliseconds(10L), 1L)

            assertEquals(listOf("media-1"), service.observePendingUploads().first().map { it.entityId })
        }

    @Test
    fun `a queued row of an unknown type is listed rather than dropped`() =
        runTest {
            val ownerId = "2e10a582-197e-48fd-97df-5a1fc1669a9e"
            val serverOrigin = "https://cloud.logdate.app"
            val dao = InMemorySyncMetadataDao()
            dao.pendingRows +=
                PendingUploadEntity(
                    ownerId = ownerId,
                    serverOrigin = serverOrigin,
                    entityType = "SOMETHING_NEWER",
                    entityId = "future-1",
                    operation = PendingOperation.CREATE.name,
                    createdAt = 1L,
                )
            dao.publish()

            val queued = service(dao, ownerId, serverOrigin).observePendingUploads().first()

            assertEquals(listOf<EntityType?>(null), queued.map { it.entityType })
        }

    @Test
    fun `repair preserves pending deletions and later deletion still reaches the server`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val service = service(dao, "owner", "https://cloud.logdate.app")
            service.enqueuePending("deleted", EntityType.DRAFT, PendingOperation.DELETE)
            service.enqueueRepairIfAbsent("deleted", EntityType.DRAFT)
            assertEquals(PendingOperation.DELETE, service.getPendingUploads(EntityType.DRAFT).single().operation)

            service.enqueueRepairIfAbsent("repaired", EntityType.DRAFT, expectedServerVersion = 2L)
            assertEquals(2L, service.getPendingUploads(EntityType.DRAFT).single { it.entityId == "repaired" }.expectedServerVersion)
            service.enqueuePending("repaired", EntityType.DRAFT, PendingOperation.UPDATE)
            assertEquals(null, service.getPendingUploads(EntityType.DRAFT).single { it.entityId == "repaired" }.expectedServerVersion)
            service.enqueuePending("repaired", EntityType.DRAFT, PendingOperation.DELETE)
            assertEquals(
                PendingOperation.DELETE,
                service.getPendingUploads(EntityType.DRAFT).single { it.entityId == "repaired" }.operation,
            )
        }

    @Test
    fun `a marked moment can wait in the outbox`() =
        runTest {
            val metadata = service(InMemorySyncMetadataDao(), "owner", "https://first.example")

            metadata.enqueuePending("moment", EntityType.MOMENT, PendingOperation.CREATE)

            assertEquals(PendingOperation.CREATE, metadata.getPendingUploads(EntityType.MOMENT).single().operation)
        }

    @Test
    fun `a retired legacy snapshot cannot refill the upload queue`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val legacy = PendingUploadEntity("", "https://first.example", "NOTE", "note", "CREATE", 1)
            dao.insertPending(legacy)
            dao.deletePending("", legacy.serverOrigin, legacy.entityType, legacy.entityId)
            dao.promotePendingIfAbsent(legacy.copy(ownerId = "owner"))
            assertTrue(dao.getAllPending().isEmpty())
        }

    @Test
    fun `a legacy snapshot cannot overwrite a newer queued edit`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val legacy = PendingUploadEntity("", "https://first.example", "NOTE", "note", "CREATE", 1)
            dao.insertPending(legacy)
            val newer = legacy.copy(ownerId = "owner", operation = "UPDATE", operationId = "newer-operation")
            dao.insertPending(newer)
            dao.promotePendingIfAbsent(legacy.copy(ownerId = "owner"))
            assertEquals(newer, dao.getPending("owner", legacy.serverOrigin, "NOTE", "note"))
        }

    @Test
    fun `queueing many changes never reads every queued row of their type`() =
        runTest {
            val dao = InMemorySyncMetadataDao()
            val metadata = service(dao, "owner", "https://sync.example")

            repeat(LARGE_QUEUE) { metadata.enqueuePending("note-$it", EntityType.NOTE, PendingOperation.UPDATE) }

            assertEquals(LARGE_QUEUE, dao.pendingRows.size)
            assertEquals(0, dao.typeReads)
        }

    @Test
    fun `a queue read waiting on the database never blocks settlement inside a transaction`() =
        runTest {
            // A transaction owns the database while it settles an upload. Another caller is
            // already reading the queue and waits for that database. Settling and counting must
            // not wait on an app lock that the waiting reader holds, or neither can finish.
            val dao = InMemorySyncMetadataDao()
            val metadata = service(dao, "owner", "https://sync.example")
            metadata.enqueuePending("note", EntityType.NOTE, PendingOperation.UPDATE)
            val databaseReleased = CompletableDeferred<Unit>()
            dao.typeReadGate = databaseReleased
            val waitingReader = launch { metadata.getPendingUploads(EntityType.NOTE) }
            testScheduler.runCurrent()
            assertTrue(waitingReader.isActive, "The reader must be waiting on the database")

            withTimeout(1_000) {
                metadata.markAsSynced("note", EntityType.NOTE, Instant.fromEpochMilliseconds(10), 1)
                assertEquals(0, metadata.getPendingCount())
            }

            databaseReleased.complete(Unit)
            waitingReader.join()
        }

    private fun service(
        dao: SyncMetadataDao,
        ownerId: String,
        serverOrigin: String,
    ): DatabaseSyncMetadataService =
        DatabaseSyncMetadataService(
            dao = dao,
            configRepository = DefaultLogDateConfigRepository(initialBackendUrl = serverOrigin),
            canonicalOwnerProvider =
                object : CanonicalOwnerProvider {
                    override suspend fun getCanonicalOwnerId(): String = ownerId

                    override suspend fun hasBoundOwner(): Boolean = true
                },
        )

    private companion object {
        const val LARGE_QUEUE = 1_000
    }

    private class InMemorySyncMetadataDao : SyncMetadataDao {
        val pendingRows = mutableListOf<PendingUploadEntity>()
        val cursors = mutableListOf<SyncCursorEntity>()
        private val pendingCount = MutableStateFlow(0)
        private val rows = MutableStateFlow<List<PendingUploadEntity>>(emptyList())

        fun publish() {
            pendingCount.value = pendingRows.size
            rows.value = pendingRows.toList()
        }

        override suspend fun getCursor(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
        ): SyncCursorEntity? =
            cursors.firstOrNull { it.ownerId == ownerId && it.serverOrigin == serverOrigin && it.entityType == entityType }

        override suspend fun getLegacyCursor(
            serverOrigin: String,
            entityType: String,
        ): SyncCursorEntity? = getCursor("", serverOrigin, entityType)

        override suspend fun upsertCursor(cursor: SyncCursorEntity) {
            cursors.removeAll {
                it.ownerId == cursor.ownerId && it.serverOrigin == cursor.serverOrigin && it.entityType == cursor.entityType
            }
            cursors += cursor
        }

        override suspend fun deleteCursorsForOrigin(
            ownerId: String,
            serverOrigin: String,
        ) {
            cursors.removeAll { it.ownerId == ownerId && it.serverOrigin == serverOrigin }
        }

        override suspend fun deleteLegacyCursor(
            serverOrigin: String,
            entityType: String,
        ) {
            cursors.removeAll { it.ownerId.isEmpty() && it.serverOrigin == serverOrigin && it.entityType == entityType }
        }

        var typeReads = 0

        /** The next owner queue read waits here, as a read waits for a database another caller owns. */
        var typeReadGate: CompletableDeferred<Unit>? = null

        override suspend fun getPendingByType(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
        ): List<PendingUploadEntity> {
            if (ownerId.isNotEmpty()) typeReads++
            if (ownerId.isNotEmpty()) typeReadGate?.also { typeReadGate = null }?.await()
            return pendingRows.filter { it.ownerId == ownerId && it.serverOrigin == serverOrigin && it.entityType == entityType }
        }

        override suspend fun hasPendingOfType(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
        ): Boolean = pendingRows.any { it.ownerId == ownerId && it.serverOrigin == serverOrigin && it.entityType == entityType }

        override suspend fun getLegacyPendingByType(
            serverOrigin: String,
            entityType: String,
        ): List<PendingUploadEntity> = getPendingByType("", serverOrigin, entityType)

        override suspend fun getPending(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
            entityId: String,
        ): PendingUploadEntity? =
            pendingRows.firstOrNull {
                it.ownerId == ownerId && it.serverOrigin == serverOrigin && it.entityType == entityType && it.entityId == entityId
            }

        override suspend fun getAllPending(): List<PendingUploadEntity> = pendingRows.toList()

        override suspend fun getPendingForOrigin(
            ownerId: String,
            serverOrigin: String,
        ): List<PendingUploadEntity> = pendingRows.filter { it.ownerId == ownerId && it.serverOrigin == serverOrigin }

        override suspend fun getCursorsForOrigin(
            ownerId: String,
            serverOrigin: String,
        ): List<SyncCursorEntity> = cursors.filter { it.ownerId == ownerId && it.serverOrigin == serverOrigin }

        override suspend fun insertRepairIfAbsent(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
            entityId: String,
            createdAt: Long,
            expectedServerVersion: Long?,
            operationId: String,
            operation: String,
        ) {
            if (pendingRows.none {
                    (it.ownerId == ownerId || it.ownerId.isEmpty()) &&
                        it.serverOrigin == serverOrigin &&
                        it.entityType == entityType &&
                        it.entityId == entityId
                }
            ) {
                insertPending(
                    PendingUploadEntity(
                        ownerId,
                        serverOrigin,
                        entityType,
                        entityId,
                        operation,
                        createdAt,
                        expectedServerVersion = expectedServerVersion,
                    ),
                )
            }
        }

        override suspend fun insertPending(pending: PendingUploadEntity) {
            pendingRows.removeAll {
                it.ownerId == pending.ownerId &&
                    it.serverOrigin == pending.serverOrigin &&
                    it.entityType == pending.entityType &&
                    it.entityId == pending.entityId
            }
            pendingRows += pending
            publish()
        }

        override suspend fun deletePending(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
            entityId: String,
        ) {
            pendingRows.removeAll {
                it.ownerId == ownerId && it.serverOrigin == serverOrigin && it.entityType == entityType && it.entityId == entityId
            }
            publish()
        }

        override suspend fun deletePendingForOrigin(
            ownerId: String,
            serverOrigin: String,
        ) {
            pendingRows.removeAll { it.ownerId == ownerId && it.serverOrigin == serverOrigin }
            publish()
        }

        override suspend fun getPendingCount(
            ownerId: String,
            serverOrigin: String,
        ): Int = pendingRows.count { it.ownerId == ownerId && it.serverOrigin == serverOrigin }

        override fun observePendingCount(
            ownerId: String,
            serverOrigin: String,
        ): Flow<Int> = pendingCount

        override fun observePending(
            ownerId: String,
            serverOrigin: String,
        ): Flow<List<PendingUploadEntity>> = rows.map { all -> all.filter { it.ownerId == ownerId && it.serverOrigin == serverOrigin } }

        override suspend fun incrementRetryCount(
            ownerId: String,
            serverOrigin: String,
            entityType: String,
            entityId: String,
        ) = Unit

        override suspend fun deleteLegacyPendingByType(
            serverOrigin: String,
            entityType: String,
        ) {
            pendingRows.removeAll { it.ownerId.isEmpty() && it.serverOrigin == serverOrigin && it.entityType == entityType }
            publish()
        }
    }
}
