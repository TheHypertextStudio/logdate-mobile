package app.logdate.server.space

import app.logdate.server.database.AccountStorageUsageTable
import app.logdate.server.database.AccountsTable
import app.logdate.server.database.IdentityAdoptionOperationsTable
import app.logdate.server.database.PostgreSQLSpaceStore
import app.logdate.server.database.RetiredIdentityDidsTable
import app.logdate.server.database.SpaceBlobUploadReceiptsTable
import app.logdate.server.database.SpaceBlobsTable
import app.logdate.server.database.SpaceRecordBlobsTable
import app.logdate.server.database.SpaceRecordsTable
import app.logdate.server.database.SpaceStatesTable
import app.logdate.server.database.SpaceUploadReservationsTable
import app.logdate.server.database.support.withH2Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

abstract class SpaceStoreContractTest {
    abstract fun withStore(
        changesByteBudget: Long = MAX_SPACE_CHANGES_BYTES,
        objectDeleteTimeout: Duration = OBJECT_DELETE_TIMEOUT,
        block: suspend (SpaceStore, UUID) -> Unit,
    )

    private val space = "at://did:plc:owner/space/app.logdate.space.personal/self"
    private val author = "did:plc:owner"

    private fun sealed(
        fill: Int,
        ciphertextBytes: Int = 528,
    ) = buildJsonObject {
        put("\$type", "app.logdate.crypto.sealed")
        put("version", 1)
        put("keyId", "abcdefghijklm")
        putJsonObject("nonce") { put("\$bytes", Base64.encode(ByteArray(12))) }
        putJsonObject("ciphertext") { put("\$bytes", Base64.encode(ByteArray(ciphertextBytes) { fill.toByte() })) }
    }

    private fun write(
        account: UUID,
        rkey: String,
        fill: Int = 1,
        swapCid: String? = null,
        createOnly: Boolean = false,
        blobs: List<String> = emptyList(),
        ciphertextBytes: Int = 528,
    ) = SpaceRecordWrite(account, space, author, rkey, sealed(fill, ciphertextBytes), blobs, swapCid, createOnly)

    @Test
    fun `concurrent ciphertexts for one upload identity retain one blob and one quota charge`() =
        withStore { store, account ->
            val intent = UUID.randomUUID().toString()
            val results =
                coroutineScope {
                    (1..4)
                        .map { index ->
                            async(Dispatchers.IO) {
                                store.putBlob(
                                    account,
                                    space,
                                    SpaceBlob("bafy$index", "application/octet-stream", 41, "owned/$index", intent),
                                )
                            }
                        }.awaitAll()
                }
            assertEquals(1, results.map { it.cid }.distinct().size)
            assertEquals(results.first(), store.getBlobUploadReceipt(space, intent))
            assertEquals(41L, store.storedBytes(account))
        }

    @Test
    fun `blob upload identity commits once and is isolated by space`() =
        withStore { store, account ->
            val intent = UUID.randomUUID().toString()
            val first = SpaceBlob("bafyfirst", "application/octet-stream", 41, "owned/first", intent)
            val second = SpaceBlob("bafysecond", "application/octet-stream", 41, "owned/second", intent)
            store.putBlob(account, space, first)
            assertEquals(first, store.getBlobUploadReceipt(space, intent))
            assertEquals(first, store.putBlob(account, space, second))
            assertNull(store.getBlob(space, second.cid))
            assertNull(store.getBlobUploadReceipt("another-space", intent))
            assertEquals(41L, store.storedBytes(account))
        }

    @Test
    fun `writes get increasing sequence numbers and read back`() =
        withStore { store, account ->
            val first = store.put(write(account, "a"))
            val second = store.put(write(account, "b"))
            assertTrue(second.seq > first.seq)
            assertEquals(first, store.get(space, author, "a"))
        }

    @Test
    fun `createOnly refuses an existing record and swapCid refuses a changed one`() =
        withStore { store, account ->
            val first = store.put(write(account, "a"))
            assertFailsWith<SpaceSwapException> { store.put(write(account, "a", createOnly = true)) }
            store.put(write(account, "a", fill = 2, swapCid = first.cid))
            assertFailsWith<SpaceSwapException> { store.put(write(account, "a", fill = 3, swapCid = first.cid)) }
        }

    @Test
    fun `deletions are listed in order as tombstones`() =
        withStore { store, account ->
            store.put(write(account, "a"))
            val put = store.put(write(account, "b"))
            val deleted = store.delete(account, space, author, "a", swapCid = null)
            assertNull(store.get(space, author, "a"))
            val page = store.changes(space, author, since = put.seq, limit = 10)
            assertEquals(listOf(SpaceChange("a", deleted, true, null, null)), page.changes)
            assertEquals(deleted, page.cursor)
            assertFalse(page.hasMore)
        }

    @Test
    fun `deleting a missing record or a tombstone changes nothing`() =
        withStore { store, account ->
            assertEquals(0L, store.delete(account, space, author, "never", swapCid = null))
            val put = store.put(write(account, "a"))
            assertEquals(put.seq, store.delete(account, space, author, "never", swapCid = null))
            val deleted = store.delete(account, space, author, "a", swapCid = null)
            assertEquals(deleted, store.delete(account, space, author, "a", swapCid = "bafyanything"))
            assertEquals(deleted, store.changes(space, author, 0, 10).cursor)
        }

    @Test
    fun `delete refuses a changed record and a tombstone can be written again`() =
        withStore { store, account ->
            val first = store.put(write(account, "a"))
            assertFailsWith<SpaceSwapException> { store.delete(account, space, author, "a", swapCid = "bafyother") }
            store.delete(account, space, author, "a", swapCid = first.cid)
            val again = store.put(write(account, "a", createOnly = true))
            assertEquals(again, store.get(space, author, "a"))
        }

    @Test
    fun `changes page by limit`() =
        withStore { store, account ->
            repeat(3) { store.put(write(account, "r$it")) }
            val first = store.changes(space, author, 0, 2)
            assertEquals(2, first.changes.size)
            assertTrue(first.hasMore)
            val rest = store.changes(space, author, first.cursor, 2)
            assertEquals(listOf("r2"), rest.changes.map { it.rkey })
            assertFalse(rest.hasMore)
            val empty = store.changes(space, author, rest.cursor, 2)
            assertEquals(SpaceChangePage(emptyList(), rest.cursor, false), empty)
        }

    @Test
    fun `a page stops before its stored records pass the byte budget`() =
        withStore { store, account ->
            listOf("a", "b", "c").forEach { store.put(write(account, it, ciphertextBytes = LARGEST_CIPHERTEXT)) }
            store.put(write(account, "d"))
            val first = store.changes(space, author, 0, 10)
            assertEquals(listOf("a", "b"), first.changes.map { it.rkey })
            assertTrue(first.hasMore)
            assertEquals(first.changes.last().seq, first.cursor)
            val rest = store.changes(space, author, first.cursor, 10)
            assertEquals(listOf("c", "d"), rest.changes.map { it.rkey })
            assertFalse(rest.hasMore)
        }

    @Test
    fun `a record larger than the byte budget still comes back on its own page`() =
        withStore(changesByteBudget = 100) { store, account ->
            store.put(write(account, "a"))
            store.put(write(account, "b"))
            val first = store.changes(space, author, 0, 10)
            assertEquals(listOf("a"), first.changes.map { it.rkey })
            assertTrue(first.hasMore)
            val rest = store.changes(space, author, first.cursor, 10)
            assertEquals(listOf("b"), rest.changes.map { it.rkey })
            assertFalse(rest.hasMore)
        }

    @Test
    fun `purge deletes old tombstones and unreferenced old blobs only`() =
        withStore { store, account ->
            val now = Clock.System.now()
            store.putBlob(account, space, SpaceBlob("bafyused", "application/octet-stream", 10, "path/used"))
            store.putBlob(account, space, SpaceBlob("bafyorphan", "application/octet-stream", 10, "path/orphan"))
            store.put(write(account, "a", blobs = listOf("bafyused")))
            store.put(write(account, "b"))
            store.delete(account, space, author, "b", null)
            val deleted = mutableListOf<String>()
            val result = store.purge(tombstonesBefore = now + 1.hours, unreferencedBlobsBefore = now + 1.hours) { deleted += it }
            assertEquals(listOf("path/orphan"), deleted)
            assertEquals(SpacePurgeResult(blobsDeleted = 1, blobsFailed = 0), result)
            assertNotNull(store.getBlob(space, "bafyused"))
            assertNull(store.getBlob(space, "bafyorphan"))
            assertEquals(listOf("a"), store.changes(space, author, 0, 10).changes.map { it.rkey })
        }

    @Test
    fun `a late abandoned deletion cannot remove a newly uploaded copy`() =
        withStore(objectDeleteTimeout = 200.milliseconds) { store, account ->
            val objects = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
            objects["path/old"] = byteArrayOf(1, 2, 3)
            val blob = SpaceBlob("bafylate", "application/octet-stream", 3, "path/old", "intent-late")
            store.putBlob(account, space, blob)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val later = Clock.System.now() + 1.hours
            try {
                val first =
                    store.purge(later, later) { path ->
                        try {
                            while (release.count > 0) {
                                try {
                                    release.await()
                                } catch (
                                    _: InterruptedException,
                                ) {
                                    // Simulates an object store that ignores cancellation.
                                }
                            }
                            objects.remove(path)
                        } finally {
                            finished.countDown()
                        }
                    }
                assertEquals(SpacePurgeResult(0, 1), first)
                assertNull(store.getBlob(space, blob.cid))
                assertNull(store.getBlobUploadReceipt(space, "intent-late"))
                assertFailsWith<SpaceMissingBlobException> { store.put(write(account, "pending", blobs = listOf(blob.cid))) }
                assertFailsWith<SpaceBlobPurgingException> {
                    store.putBlob(account, space, blob.copy(storagePath = "path/premature"))
                }
                assertFailsWith<SpaceBlobPurgingException> {
                    store.putBlob(account, space, blob.copy(cid = "bafyother", storagePath = "path/premature"))
                }
                assertEquals(3L, store.storedBytes(account))
                assertEquals(
                    SpacePurgeResult(1, 0),
                    store.purge(Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0)) { path -> objects.remove(path) },
                )
                objects["path/new"] = byteArrayOf(1, 2, 3)
                val current = store.putBlob(account, space, blob.copy(storagePath = "path/new"))
                val record = store.put(write(account, "pending", blobs = listOf(blob.cid)))
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS), "The abandoned callback must finish within the test")
                assertEquals("path/new", current.storagePath)
                assertTrue(objects["path/new"]!!.contentEquals(byteArrayOf(1, 2, 3)))
                assertEquals(record, store.get(space, author, "pending"))
            } finally {
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `a cursor from before purged tombstones is too old and a full listing still works`() =
        withStore { store, account ->
            store.put(write(account, "a"))
            store.put(write(account, "b"))
            val deleted = store.delete(account, space, author, "a", swapCid = null)
            store.put(write(account, "c"))
            val later = Clock.System.now() + 1.hours
            store.purgeRecording(tombstonesBefore = later, unreferencedBlobsBefore = later)

            listOf(1L, deleted - 1).forEach { since ->
                val stale = assertFailsWith<SpaceCursorTooOldException> { store.changes(space, author, since, 10) }
                assertEquals(deleted, stale.purgedThroughSeq)
            }
            assertEquals(listOf("c"), store.changes(space, author, deleted, 10).changes.map { it.rkey })
            assertEquals(listOf("b", "c"), store.changes(space, author, 0, 10).changes.map { it.rkey })

            store.purgeRecording(tombstonesBefore = later, unreferencedBlobsBefore = later)
            assertFailsWith<SpaceCursorTooOldException> { store.changes(space, author, 1, 10) }
            val otherAuthor = "did:plc:other"
            assertEquals(emptyList(), store.changes(space, otherAuthor, 1, 10).changes)
        }

    @Test
    fun `a blob whose object can't be deleted keeps its row until a later purge deletes both`() =
        withStore { store, account ->
            store.putBlob(account, space, SpaceBlob("bafyfirst", "application/octet-stream", 10, "path/first"))
            store.putBlob(account, space, SpaceBlob("bafysecond", "application/octet-stream", 10, "path/second"))
            val later = Clock.System.now() + 1.hours
            val deleted = mutableListOf<String>()
            val failing =
                store.purge(tombstonesBefore = later, unreferencedBlobsBefore = later) { path ->
                    check(path != "path/first") { "The object store is down" }
                    deleted += path
                }
            assertEquals(SpacePurgeResult(blobsDeleted = 1, blobsFailed = 1), failing)
            assertEquals(listOf("path/second"), deleted)
            assertNull(store.getBlob(space, "bafyfirst"), "An unresolved deletion must not expose a usable blob")
            assertEquals(10L, store.storedBytes(account), "Uncertain deletion still counts against quota")
            assertNull(store.getBlob(space, "bafysecond"))

            assertEquals(listOf("path/first"), store.purgeRecording(tombstonesBefore = later, unreferencedBlobsBefore = later))
            assertNull(store.getBlob(space, "bafyfirst"))
        }

    @Test
    fun `an object delete that hangs times out and keeps its row until a later purge deletes both`() =
        withStore(objectDeleteTimeout = 200.milliseconds) { store, account ->
            store.putBlob(account, space, SpaceBlob("bafystuck", "application/octet-stream", 10, "path/stuck"))
            store.putBlob(account, space, SpaceBlob("bafyprompt", "application/octet-stream", 10, "path/prompt"))
            val later = Clock.System.now() + 1.hours
            val never = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val deleted = ConcurrentLinkedQueue<String>()
            val hanging =
                store.purge(tombstonesBefore = later, unreferencedBlobsBefore = later) { path ->
                    if (path == "path/stuck") {
                        try {
                            never.await(10, TimeUnit.SECONDS)
                        } catch (e: InterruptedException) {
                            interrupted.countDown()
                            throw e
                        }
                    }
                    deleted += path
                }
            assertEquals(SpacePurgeResult(blobsDeleted = 1, blobsFailed = 1), hanging)
            assertEquals(listOf("path/prompt"), deleted.toList())
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "The abandoned delete must be interrupted")
            assertNull(store.getBlob(space, "bafystuck"), "A timed-out deletion must not expose a usable blob")
            assertEquals(10L, store.storedBytes(account), "Uncertain deletion still counts against quota")

            assertEquals(listOf("path/stuck"), store.purgeRecording(tombstonesBefore = later, unreferencedBlobsBefore = later))
            assertNull(store.getBlob(space, "bafystuck"))
        }

    @Test
    fun `put referencing an unknown blob is refused`() =
        withStore { store, account ->
            store.putBlob(account, space, SpaceBlob("bafyknown", "application/octet-stream", 10, "path/known"))
            store.putBlob(account, "at://did:plc:other/space/app.logdate.space.personal/self", SpaceBlob("bafyelsewhere", "x", 1, "p"))
            val missing =
                assertFailsWith<SpaceMissingBlobException> {
                    store.put(write(account, "a", blobs = listOf("bafyknown", "bafyelsewhere")))
                }
            assertEquals("bafyelsewhere", missing.cid)
            assertNull(store.get(space, author, "a"))
            assertEquals(emptyList(), store.changes(space, author, 0, 10).changes)
            assertEquals(1L, store.put(write(account, "a", blobs = listOf("bafyknown"))).seq)
        }

    @Test
    fun `put refuses a record that isn't a sealed envelope`() =
        withStore { store, account ->
            val plain = SpaceRecordWrite(account, space, author, "a", buildJsonObject { put("text", "hello") }, emptyList(), null, false)
            val refused = assertFailsWith<SpaceInvalidRecordException> { store.put(plain) }
            assertEquals("Not an app.logdate.crypto.sealed record", refused.reason)
            assertNull(store.get(space, author, "a"))
        }

    @Test
    fun `changes requires a limit from 1 to the maximum`() =
        withStore { store, account ->
            store.put(write(account, "a"))
            assertFailsWith<IllegalArgumentException> { store.changes(space, author, 0, 0) }
            assertFailsWith<IllegalArgumentException> { store.changes(space, author, 0, MAX_SPACE_CHANGES_LIMIT + 1) }
            assertEquals(1, store.changes(space, author, 0, MAX_SPACE_CHANGES_LIMIT).changes.size)
        }

    @Test
    fun `putting an existing blob keeps its storage path and restarts its grace period`() =
        withStore { store, account ->
            val original = SpaceBlob("bafyblob", "application/octet-stream", 10, "path/original")
            assertEquals(original, store.putBlob(account, space, original))
            delay(5)
            val between = Clock.System.now()
            delay(5)
            val stored = store.putBlob(account, space, SpaceBlob("bafyblob", "image/jpeg", 12, "path/second"))
            assertEquals(SpaceBlob("bafyblob", "image/jpeg", 12, "path/original"), stored)
            assertEquals(stored, store.getBlob(space, "bafyblob"))
            assertEquals(emptyList(), store.purgeRecording(tombstonesBefore = between, unreferencedBlobsBefore = between))
            assertNotNull(store.getBlob(space, "bafyblob"))
        }

    @Test
    fun `a purged blob stored again gets a new row at its new path`() =
        withStore { store, account ->
            store.putBlob(account, space, SpaceBlob("bafyblob", "application/octet-stream", 10, "path/first"))
            val later = Clock.System.now() + 1.hours
            assertEquals(listOf("path/first"), store.purgeRecording(tombstonesBefore = later, unreferencedBlobsBefore = later))
            val again = SpaceBlob("bafyblob", "application/octet-stream", 10, "path/again")
            assertEquals(again, store.putBlob(account, space, again))
            assertEquals(again, store.getBlob(space, "bafyblob"))
        }

    @Test
    fun `stored bytes follow writes, replacements, deletes, new blobs and purges`() =
        withStore { store, account ->
            val small = recordSize(sealed(1))
            val large = recordSize(sealed(1, ciphertextBytes = 2_048 + 16))
            store.put(write(account, "a"))
            assertEquals(small, store.storedBytes(account))
            store.put(write(account, "a", ciphertextBytes = 2_048 + 16))
            assertEquals(large, store.storedBytes(account), "a replacement counts only its own size")
            store.putBlob(account, space, SpaceBlob("bafycounted", OCTETS, 1_000, "path/first"))
            store.putBlob(account, space, SpaceBlob("bafycounted", OCTETS, 1_000, "path/again"))
            assertEquals(large + 1_000, store.storedBytes(account), "a blob the space holds counts once")
            store.delete(account, space, author, "a", null)
            assertEquals(1_000L, store.storedBytes(account))
            val later = Clock.System.now() + 1.hours
            store.purgeRecording(tombstonesBefore = later, unreferencedBlobsBefore = later)
            assertEquals(0L, store.storedBytes(account))
        }

    @Test
    fun `a write past the quota is refused after its conditions, and shrinking or deleting always works`() =
        withStore { store, account ->
            store.put(write(account, "a"))
            val size = store.storedBytes(account)
            val full = SpaceQuota(limitBytes = 10_000, otherBytes = 10_000 - size)
            val refused = assertFailsWith<SpaceQuotaExceededException> { store.put(write(account, "b").copy(quota = full)) }
            assertEquals(10_000L, refused.limitBytes)
            assertEquals(10_000L, refused.usedBytes)
            assertNull(store.get(space, author, "b"))
            assertEquals(size, store.storedBytes(account))
            assertFailsWith<SpaceSwapException> {
                store.put(write(account, "a", createOnly = true, ciphertextBytes = 4_096 + 16).copy(quota = full))
            }
            store.put(write(account, "a", fill = 2).copy(quota = full))
            store.delete(account, space, author, "a", null)
            assertEquals(0L, store.storedBytes(account))
        }

    @Test
    fun `an upload reservation holds room until its blob is stored or the room is released`() =
        withStore { store, account ->
            val quota = SpaceQuota(limitBytes = 1_000, otherBytes = 0)
            val held = store.reserveUpload(account, 600, quota)
            assertFailsWith<SpaceQuotaExceededException> { store.reserveUpload(account, 600, quota) }
            store.putBlob(account, space, SpaceBlob("bafyheld", OCTETS, 600, "path/held"), quota, held)
            assertEquals(600L, store.storedBytes(account))
            store.releaseUpload(held)
            assertFailsWith<SpaceQuotaExceededException> { store.reserveUpload(account, 600, quota) }
            val other = store.reserveUpload(account, 400, quota)
            store.releaseUpload(other)
            store.releaseUpload(other)
            store.releaseUpload(store.reserveUpload(account, 400, quota))
            assertFailsWith<SpaceQuotaExceededException> {
                store.putBlob(account, space, SpaceBlob("bafytoolarge", OCTETS, 401, "path/too-large"), quota)
            }
            assertNull(store.getBlob(space, "bafytoolarge"))
            assertEquals(600L, store.storedBytes(account))
        }

    @Test
    fun `concurrent writes and reservations can't together pass the quota`() =
        withStore { store, account ->
            val size = recordSize(sealed(1))
            val records = SpaceQuota(limitBytes = size * 3, otherBytes = 0)
            val writes =
                coroutineScope {
                    (0 until 8)
                        .map { index ->
                            async(Dispatchers.IO) { runCatching { store.put(write(account, "r$index").copy(quota = records)) } }
                        }.awaitAll()
                }
            assertEquals(3, writes.count { it.isSuccess }, "$writes")
            assertTrue(writes.mapNotNull { it.exceptionOrNull() }.all { it is SpaceQuotaExceededException }, "$writes")
            assertEquals(size * 3, store.storedBytes(account))

            val uploads = SpaceQuota(limitBytes = size * 3 + 1_000, otherBytes = 0)
            val reservations =
                coroutineScope {
                    (0 until 8).map { async(Dispatchers.IO) { runCatching { store.reserveUpload(account, 400, uploads) } } }.awaitAll()
                }
            assertEquals(2, reservations.count { it.isSuccess }, "$reservations")
        }

    @Test
    fun `purge keeps recent tombstones and blobs`() =
        withStore { store, account ->
            val before = Clock.System.now() - 1.hours
            store.putBlob(account, space, SpaceBlob("bafyorphan", "application/octet-stream", 10, "path/orphan"))
            store.put(write(account, "b"))
            store.delete(account, space, author, "b", null)
            assertEquals(emptyList(), store.purgeRecording(tombstonesBefore = before, unreferencedBlobsBefore = before))
            assertEquals(SpaceBlob("bafyorphan", "application/octet-stream", 10, "path/orphan"), store.getBlob(space, "bafyorphan"))
            assertEquals(listOf("b"), store.changes(space, author, 0, 10).changes.map { it.rkey })
        }
}

/** Purges with a deleter that always succeeds, and returns the storage paths it was asked to delete. */
private suspend fun SpaceStore.purgeRecording(
    tombstonesBefore: Instant,
    unreferencedBlobsBefore: Instant,
): List<String> {
    val deleted = mutableListOf<String>()
    purge(tombstonesBefore, unreferencedBlobsBefore) { deleted += it }
    return deleted
}

private const val OCTETS = "application/octet-stream"

/** How many bytes [record] takes in storage. */
private fun recordSize(record: kotlinx.serialization.json.JsonObject): Long = recordCid(record).second.size.toLong()

/** The largest ciphertext a sealed record can carry: 61 padding buckets of 64 KiB, plus the AES-GCM tag. */
private const val LARGEST_CIPHERTEXT = 61 * 65_536 + 16

class InMemorySpaceStoreTest : SpaceStoreContractTest() {
    override fun withStore(
        changesByteBudget: Long,
        objectDeleteTimeout: Duration,
        block: suspend (SpaceStore, UUID) -> Unit,
    ) = runBlocking { block(InMemorySpaceStore(changesByteBudget, objectDeleteTimeout), UUID.randomUUID()) }
}

class PostgreSQLSpaceStoreH2Test : SpaceStoreContractTest() {
    override fun withStore(
        changesByteBudget: Long,
        objectDeleteTimeout: Duration,
        block: suspend (SpaceStore, UUID) -> Unit,
    ) = withH2Database(
        AccountsTable,
        IdentityAdoptionOperationsTable,
        RetiredIdentityDidsTable,
        SpaceStatesTable,
        SpaceRecordsTable,
        SpaceRecordBlobsTable,
        SpaceBlobsTable,
        SpaceBlobUploadReceiptsTable,
        AccountStorageUsageTable,
        SpaceUploadReservationsTable,
    ) {
        val account = UUID.randomUUID()
        transaction {
            AccountsTable.insert {
                it[id] = account
                it[username] = "owner"
                it[displayName] = "Owner"
                it[createdAt] = Clock.System.now()
            }
        }
        runBlocking { block(PostgreSQLSpaceStore(changesByteBudget, objectDeleteTimeout), account) }
    }
}
