package app.logdate.server.space

import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [SpaceStore] for servers running without a database. A page of changes carries at most
 * [changesByteBudget] stored record bytes, and a purge waits at most [objectDeleteTimeout] for each
 * object delete. One lock guards state and quota changes; object deletion runs outside it after quarantine.
 */
class InMemorySpaceStore(
    private val changesByteBudget: Long = MAX_SPACE_CHANGES_BYTES,
    private val objectDeleteTimeout: Duration = OBJECT_DELETE_TIMEOUT,
) : SpaceStore {
    private class Row(
        val accountId: UUID,
        val seq: Long,
        val cid: String?,
        val bytes: ByteArray?,
        val deletedAt: Instant?,
    )

    private class BlobRow(
        val accountId: UUID,
        val blob: SpaceBlob,
        val createdAt: Instant,
    ) {
        var purging: Boolean = false
    }

    private class Held(
        val accountId: UUID,
        val bytes: Long,
        val expiresAt: Instant,
    )

    private val mutex = Mutex()
    private val states = mutableMapOf<Pair<String, String>, Long>()
    private val purgedThrough = mutableMapOf<Pair<String, String>, Long>()
    private val records = mutableMapOf<Triple<String, String, String>, Row>()
    private val recordBlobs = mutableMapOf<Triple<String, String, String>, Set<String>>()
    private val blobs = mutableMapOf<Pair<String, String>, BlobRow>()
    private val uploadReceipts = mutableMapOf<Pair<String, String>, String>()
    private val stored = mutableMapOf<UUID, Long>()
    private val reservations = mutableMapOf<UUID, Held>()

    override suspend fun put(write: SpaceRecordWrite): StoredSpaceRecord =
        mutex.withLock {
            requireSealedRecord(write.record)
            val key = Triple(write.space, write.author, write.rkey)
            val live = records[key]?.takeIf { it.deletedAt == null }
            if (write.createOnly && live != null) throw SpaceSwapException("A record with this key exists")
            if (write.swapCid != null && live?.cid != write.swapCid) throw SpaceSwapException("The record changed")
            write.blobs
                .sorted()
                .firstOrNull { blobs[write.space to it]?.let { row -> !row.purging } != true }
                ?.let { throw SpaceMissingBlobException(it) }
            val (cid, bytes) = recordCid(write.record)
            val growth = bytes.size.toLong() - (live?.bytes?.size ?: 0)
            requireRoom(write.quota, held(write.accountId, except = null), growth)
            addStored(write.accountId, growth)
            val seq = nextSeq(write.space, write.author)
            records[key] = Row(write.accountId, seq, cid, bytes, deletedAt = null)
            recordBlobs[key] = write.blobs.toSet()
            StoredSpaceRecord(write.rkey, cid, seq, decodeRecord(bytes))
        }

    override suspend fun delete(
        accountId: UUID,
        space: String,
        author: String,
        rkey: String,
        swapCid: String?,
        identityEpoch: Long,
    ): Long =
        mutex.withLock {
            val key = Triple(space, author, rkey)
            val row = records[key] ?: return@withLock states[space to author] ?: 0L
            if (row.deletedAt != null) return@withLock row.seq
            if (swapCid != null && row.cid != swapCid) throw SpaceSwapException("The record changed")
            val seq = nextSeq(space, author)
            addStored(row.accountId, -(row.bytes?.size ?: 0).toLong())
            records[key] = Row(row.accountId, seq, cid = null, bytes = null, deletedAt = Clock.System.now())
            recordBlobs.remove(key)
            seq
        }

    override suspend fun get(
        space: String,
        author: String,
        rkey: String,
    ): StoredSpaceRecord? =
        mutex.withLock {
            val row = records[Triple(space, author, rkey)] ?: return@withLock null
            val bytes = row.bytes ?: return@withLock null
            StoredSpaceRecord(rkey, requireNotNull(row.cid), row.seq, decodeRecord(bytes))
        }

    override suspend fun changes(
        space: String,
        author: String,
        since: Long,
        limit: Int,
    ): SpaceChangePage {
        requireChangesLimit(limit)
        return mutex.withLock {
            requireFreshCursor(since, purgedThrough[space to author] ?: 0L)
            val rows =
                records
                    .filter { (key, row) -> key.first == space && key.second == author && row.seq > since }
                    .entries
                    .sortedBy { it.value.seq }
                    .take(limit + 1)
            val fitting = countWithinBudget(rows.take(limit).map { (it.value.bytes?.size ?: 0).toLong() }, changesByteBudget)
            val page =
                rows.take(fitting).map { (key, row) ->
                    SpaceChange(key.third, row.seq, row.deletedAt != null, row.cid, row.bytes?.let(::decodeRecord))
                }
            SpaceChangePage(page, page.lastOrNull()?.seq ?: since, rows.size > fitting)
        }
    }

    override suspend fun putBlob(
        accountId: UUID,
        space: String,
        blob: SpaceBlob,
        quota: SpaceQuota?,
        reservation: SpaceUploadReservation?,
        identityEpoch: Long,
    ): SpaceBlob =
        mutex.withLock {
            if (blobs[space to blob.cid]?.purging == true) throw SpaceBlobPurgingException()
            blob.uploadIntent?.let { intent ->
                uploadReceipts[space to intent]?.let { cid ->
                    blobs[space to cid]?.let { row ->
                        if (row.purging) throw SpaceBlobPurgingException()
                        reservation?.let { reservations.remove(it.id) }
                        return@withLock row.blob.copy(uploadIntent = intent)
                    }
                }
            }
            val existing = blobs[space to blob.cid]
            if (existing == null) {
                requireRoom(quota, held(accountId, except = reservation?.id), blob.size)
                addStored(accountId, blob.size)
            }
            reservation?.let { reservations.remove(it.id) }
            val stored = blob.copy(storagePath = existing?.blob?.storagePath ?: blob.storagePath)
            blobs[space to blob.cid] = BlobRow(existing?.accountId ?: accountId, stored, Clock.System.now())
            blob.uploadIntent?.let { uploadReceipts[space to it] = stored.cid }
            stored
        }

    override suspend fun reserveUpload(
        accountId: UUID,
        bytes: Long,
        quota: SpaceQuota,
        identityEpoch: Long,
    ): SpaceUploadReservation =
        mutex.withLock {
            requireRoom(quota, held(accountId, except = null), bytes)
            val reservation = SpaceUploadReservation(UUID.randomUUID(), accountId, bytes, identityEpoch)
            reservations[reservation.id] = Held(accountId, bytes, Clock.System.now() + UPLOAD_RESERVATION_LIFETIME)
            reservation
        }

    override suspend fun releaseUpload(reservation: SpaceUploadReservation) {
        mutex.withLock { reservations.remove(reservation.id) }
    }

    override suspend fun storedBytes(accountId: UUID): Long = mutex.withLock { stored[accountId] ?: 0L }

    override suspend fun getBlob(
        space: String,
        cid: String,
    ): SpaceBlob? = mutex.withLock { blobs[space to cid]?.takeUnless { it.purging }?.blob }

    override suspend fun getBlobUploadReceipt(
        space: String,
        intent: String,
    ): SpaceBlob? =
        mutex.withLock {
            uploadReceipts[space to intent]?.let { cid ->
                blobs[space to cid]?.takeUnless { it.purging }?.blob?.copy(uploadIntent = intent)
            }
        }

    override suspend fun purge(
        tombstonesBefore: Instant,
        unreferencedBlobsBefore: Instant,
        deleteObject: (storagePath: String) -> Unit,
    ): SpacePurgeResult {
        val candidates =
            mutex.withLock {
                val now = Clock.System.now()
                reservations.values.removeAll { it.expiresAt <= now }
                val old = records.filterValues { it.deletedAt != null && it.deletedAt < tombstonesBefore }
                old.forEach { (key, row) ->
                    val repository = key.first to key.second
                    purgedThrough[repository] = maxOf(purgedThrough[repository] ?: 0L, row.seq)
                    records.remove(key)
                }
                blobs.keys.filter { purgeable(it, unreferencedBlobsBefore) }.sortedWith(compareBy({ it.first }, { it.second }))
            }
        var deleted = 0
        var failed = 0
        candidates.forEach { key ->
            val row =
                mutex.withLock {
                    if (!purgeable(key, unreferencedBlobsBefore)) return@withLock null
                    blobs.getValue(key).also { it.purging = true }
                } ?: return@forEach
            try {
                withContext(Dispatchers.IO) { deleteObjectOrThrow(deleteObject, row.blob.storagePath, objectDeleteTimeout) }
            } catch (e: SpaceObjectDeleteException) {
                logObjectDeleteFailure(e)
                failed++
                return@forEach
            }
            mutex.withLock {
                if (blobs[key] !== row) return@withLock
                blobs.remove(key)
                uploadReceipts.entries.removeAll { it.key.first == key.first && it.value == key.second }
                addStored(row.accountId, -row.blob.size)
                deleted++
            }
        }
        return SpacePurgeResult(deleted, failed)
    }

    /** What [accountId] stores plus the room its unexpired uploads hold, leaving out the reservation [except]. */
    private fun held(
        accountId: UUID,
        except: UUID?,
    ): Long {
        val now = Clock.System.now()
        val reserved =
            reservations
                .filter { (id, held) -> id != except && held.accountId == accountId && held.expiresAt > now }
                .values
                .sumOf { it.bytes }
        return (stored[accountId] ?: 0L) + reserved
    }

    private fun addStored(
        accountId: UUID,
        delta: Long,
    ) {
        if (delta == 0L) return
        val current = stored[accountId] ?: 0L
        val next = current + delta
        if (next < 0) {
            Napier.e("Storage total would go below zero: account=$accountId kind=SPACE current=$current delta=$delta; stored 0")
        }
        stored[accountId] = next.coerceAtLeast(0L)
    }

    /** Whether the blob [key] still exists, was stored before [createdBefore], and no record refers to it. */
    private fun purgeable(
        key: Pair<String, String>,
        createdBefore: Instant,
    ): Boolean {
        val row = blobs[key] ?: return false
        if (!row.purging && row.createdAt >= createdBefore) return false
        return recordBlobs.none { (recordKey, cids) -> recordKey.first == key.first && key.second in cids }
    }

    private fun nextSeq(
        space: String,
        author: String,
    ): Long {
        val seq = (states[space to author] ?: 0L) + 1
        states[space to author] = seq
        return seq
    }
}
