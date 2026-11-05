package app.logdate.server

import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.logging.SpaceMigrationNotices
import app.logdate.server.logging.SpacePurgeNotices
import app.logdate.server.logging.safeServerMessage
import app.logdate.server.space.InMemorySpaceStore
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpacePurgeResult
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.SpaceStore
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SyncMaintenanceSpaceTest {
    private val owner = UUID.randomUUID()
    private val space = "at://did:plc:owner/space/app.logdate.space.personal/self"
    private val author = "did:plc:owner"
    private val logged = mutableListOf<Pair<LogLevel, String?>>()
    private val antilog =
        object : Antilog() {
            override fun performLog(
                priority: LogLevel,
                tag: String?,
                throwable: Throwable?,
                message: String?,
            ) {
                logged += priority to message
            }
        }

    private fun storeBlob(
        storage: LogDateBlobStorage,
        cid: String,
    ): SpaceBlob {
        val path =
            storage.putBlob(
                LogDateBlobWriteRequest(owner, LogDateBlobNamespace.SPACE, cid, null, "application/octet-stream", byteArrayOf(1)),
            )
        return SpaceBlob(cid, "application/octet-stream", 1, path)
    }

    private fun sealed(): JsonObject =
        buildJsonObject {
            put("\$type", "app.logdate.crypto.sealed")
            put("version", 1)
            put("keyId", "abcdefghijklm")
            putJsonObject("nonce") { put("\$bytes", Base64.encode(ByteArray(12))) }
            putJsonObject("ciphertext") { put("\$bytes", Base64.encode(ByteArray(528))) }
        }

    private suspend fun <T> withLogs(block: suspend () -> T): T {
        Napier.base(antilog)
        try {
            return block()
        } finally {
            Napier.takeLogarithm(antilog)
        }
    }

    @Test
    fun `old tombstones and blobs nothing refers to are purged from the store and storage`() =
        runTest {
            val store = InMemorySpaceStore()
            val storage = InMemoryLogDateBlobStorage()
            val orphan = store.putBlob(owner, space, storeBlob(storage, "bafyorphan"))
            store.put(SpaceRecordWrite(owner, space, author, "gone", sealed(), emptyList(), null, false))
            store.delete(owner, space, author, "gone", swapCid = null)
            assertEquals(listOf("gone"), store.changes(space, author, 0, 10).changes.map { it.rkey })

            purgeSpaces(store, storage, now = Clock.System.now() + 2.days, tombstoneRetention = 1.days)

            assertNull(storage.getBlob(orphan.storagePath))
            assertNull(store.getBlob(space, "bafyorphan"))
            assertEquals(emptyList(), store.changes(space, author, 0, 10).changes)
        }

    @Test
    fun `tombstones inside the configured retention survive`() =
        runTest {
            val store = InMemorySpaceStore()
            store.put(SpaceRecordWrite(owner, space, author, "gone", sealed(), emptyList(), null, false))
            store.delete(owner, space, author, "gone", swapCid = null)

            purgeSpaces(store, InMemoryLogDateBlobStorage(), now = Clock.System.now() + 2.days, tombstoneRetention = 3.days)

            assertEquals(listOf("gone"), store.changes(space, author, 0, 10).changes.map { it.rkey })
        }

    @Test
    fun `a blob a live record refers to and a recent orphan both survive`() =
        runTest {
            val store = InMemorySpaceStore()
            val storage = InMemoryLogDateBlobStorage()
            val used = store.putBlob(owner, space, storeBlob(storage, "bafyused"))
            val recent = store.putBlob(owner, space, storeBlob(storage, "bafyrecent"))
            store.put(SpaceRecordWrite(owner, space, author, "a", sealed(), listOf("bafyused"), null, false))

            purgeSpaces(store, storage, now = Clock.System.now() + 2.hours, tombstoneRetention = 30.days)

            assertNotNull(storage.getBlob(used.storagePath))
            assertNotNull(store.getBlob(space, "bafyused"))
            assertNotNull(storage.getBlob(recent.storagePath))
            assertNotNull(store.getBlob(space, "bafyrecent"))
        }

    @Test
    fun `a storage delete that throws keeps that blob for the next purge and the others are still deleted`() =
        runTest {
            val store = InMemorySpaceStore()
            val delegate = InMemoryLogDateBlobStorage()
            val first = store.putBlob(owner, space, storeBlob(delegate, "bafyfirst"))
            val second = store.putBlob(owner, space, storeBlob(delegate, "bafysecond"))
            val failingPath = listOf(first.storagePath, second.storagePath).minOf { it }
            val storage =
                object : LogDateBlobStorage by delegate {
                    override fun deleteBlobIfExists(storagePath: String) {
                        check(storagePath != failingPath) { "provider failure" }
                        delegate.deleteBlobIfExists(storagePath)
                    }
                }
            val later = Clock.System.now() + 2.days
            val result = withLogs { purgeSpaces(store, storage, now = later, tombstoneRetention = 30.days) }

            val survivor = listOf(first, second).single { it.storagePath == failingPath }
            val deleted = listOf(first, second).single { it.storagePath != failingPath }
            assertEquals(SpacePurgeResult(blobsDeleted = 1, blobsFailed = 1), result)
            assertNotNull(delegate.getBlob(survivor.storagePath))
            assertNull(store.getBlob(space, survivor.cid), "Unresolved deletion quarantines the retained row")
            assertEquals(1L, store.storedBytes(owner), "Uncertain deletion remains charged against quota")
            assertNull(delegate.getBlob(deleted.storagePath))
            assertNull(store.getBlob(space, deleted.cid))
            assertEquals(listOf(LogLevel.ERROR to SpacePurgeNotices.BLOB_DELETE_FAILED), logged.filter { it.first == LogLevel.ERROR })

            purgeSpaces(store, delegate, now = later, tombstoneRetention = 30.days)

            assertNull(delegate.getBlob(survivor.storagePath))
            assertNull(store.getBlob(space, survivor.cid))
            assertEquals(0L, store.storedBytes(owner))
        }

    @Test
    fun `a purge that fails is logged with its own code and recorded as a failed run`() =
        runTest {
            val store =
                object : SpaceStore by InMemorySpaceStore() {
                    override suspend fun purge(
                        tombstonesBefore: Instant,
                        unreferencedBlobsBefore: Instant,
                        deleteObject: (storagePath: String) -> Unit,
                    ): SpacePurgeResult = error("The database is down")
                }
            val metrics = SyncMetricsRegistry()

            withLogs { purgeSpacesRecorded(store, InMemoryLogDateBlobStorage(), metrics, tombstoneRetention = 30.days) }

            assertEquals(listOf(LogLevel.ERROR to SpacePurgeNotices.PURGE_FAILED), logged.filter { it.first == LogLevel.ERROR })
            val run = metrics.snapshot().operations.single { it.name == "maintenance.space_purge" }
            assertEquals(1, run.errorCount)
            assertEquals(0, run.successCount)
        }

    @Test
    fun `the sweep purges space tombstones with the configured retention`() {
        val asked = CompletableDeferred<Instant>()
        val store =
            object : SpaceStore by InMemorySpaceStore() {
                override suspend fun purge(
                    tombstonesBefore: Instant,
                    unreferencedBlobsBefore: Instant,
                    deleteObject: (storagePath: String) -> Unit,
                ): SpacePurgeResult {
                    asked.complete(tombstonesBefore)
                    return SpacePurgeResult(blobsDeleted = 0, blobsFailed = 0)
                }
            }
        val job =
            startSyncMaintenance(
                collectionsRepository = InMemorySyncRepository().asLogDateCollectionsRepository(),
                metrics = SyncMetricsRegistry(),
                readEnv = { name -> if (name == "SYNC_TOMBSTONE_RETENTION_DAYS") "2" else null },
                spaceStore = store,
                blobStorage = InMemoryLogDateBlobStorage(),
            )
        try {
            val cutoff = runBlocking { withTimeout(5_000) { asked.await() } }
            val expected = Clock.System.now() - 2.days
            assertTrue(cutoff in (expected - 1.minutes)..expected, "cutoff $cutoff, expected about $expected")
        } finally {
            runBlocking {
                requireNotNull(job).cancel()
                job.join()
            }
        }
    }

    @Test
    fun `space purge outcomes keep their own codes in production logs`() {
        mapOf(
            SpacePurgeNotices.BLOBS_PURGED to "SERVER_SPACE_BLOBS_PURGED",
            SpacePurgeNotices.BLOB_DELETE_FAILED to "SERVER_SPACE_BLOB_DELETE_FAILED",
            SpacePurgeNotices.PURGE_FAILED to "SERVER_SPACE_PURGE_FAILED",
        ).forEach { (message, code) ->
            assertEquals(code, safeServerMessage(LogLevel.ERROR, tag = null, message = message))
        }
    }

    @Test
    fun `journal move outcomes keep their own codes in production logs`() {
        mapOf(
            SpaceMigrationNotices.SWITCHED to "SERVER_SPACE_MIGRATION_SWITCHED",
            SpaceMigrationNotices.SWITCHED_BACK to "SERVER_SPACE_MIGRATION_SWITCHED_BACK",
            SpaceMigrationNotices.COPY_MISMATCH to "SERVER_SPACE_MIGRATION_COPY_MISMATCH",
            SpaceMigrationNotices.CLEANUP_FAILED to "SERVER_LEGACY_COPY_CLEANUP_FAILED",
            SpaceMigrationNotices.CLEANUP_SKIPPED to "SERVER_LEGACY_COPY_CLEANUP_SKIPPED",
            SpaceMigrationNotices.CLEANUP_RUN_FAILED to "SERVER_LEGACY_COPY_CLEANUP_RUN_FAILED",
        ).forEach { (message, code) ->
            assertEquals(code, safeServerMessage(LogLevel.ERROR, tag = null, message = message))
        }
        val counted = "Deleted the legacy copies of 12 moved journals"
        assertEquals(counted, safeServerMessage(LogLevel.INFO, tag = null, message = counted))
    }
}
