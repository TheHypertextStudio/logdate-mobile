package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.server.space.MAX_SPACE_CHANGES_BYTES
import app.logdate.server.space.OBJECT_DELETE_TIMEOUT
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceChangePage
import app.logdate.server.space.SpaceMissingBlobException
import app.logdate.server.space.SpacePurgeResult
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.SpaceStore
import app.logdate.server.space.SpaceSwapException
import app.logdate.server.space.SpaceUploadReservation
import app.logdate.server.space.StoredSpaceRecord
import app.logdate.server.space.UPLOAD_RESERVATION_LIFETIME
import app.logdate.server.space.countWithinBudget
import app.logdate.server.space.decodeRecord
import app.logdate.server.space.recordCid
import app.logdate.server.space.requireChangesLimit
import app.logdate.server.space.requireFreshCursor
import app.logdate.server.space.requireRoom
import app.logdate.server.space.requireSealedRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private const val PUT_BLOB_ATTEMPTS = 3

/**
 * Stores sealed space records in PostgreSQL. Every write locks its (space, author) state row before
 * taking the next sequence number, so concurrent writes never share one. A page of changes carries
 * at most [changesByteBudget] stored record bytes, and a purge waits at most [objectDeleteTimeout]
 * for each object delete.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class PostgreSQLSpaceStore(
    private val changesByteBudget: Long = MAX_SPACE_CHANGES_BYTES,
    private val objectDeleteTimeout: Duration = OBJECT_DELETE_TIMEOUT,
    private val migrationConfig: SpaceMigrationConfig = SpaceMigrationConfig(),
) : SpaceStore {
    private val blobMetadata = PostgreSQLSpaceBlobMetadata(::changeUsage)

    override suspend fun put(write: SpaceRecordWrite): StoredSpaceRecord {
        requireSealedRecord(write.record)
        return withContext(Dispatchers.IO) {
            transaction {
                requireIdentity(write.accountId, write.identityEpoch, write.space, write.author)
                val lastSeq = lockState(write.accountId, write.space, write.author)
                val existing = lockedRecordRow(write.space, write.author, write.rkey)
                val liveCid = existing?.takeUnless { it[SpaceRecordsTable.deleted] }?.get(SpaceRecordsTable.cid)
                if (write.createOnly && liveCid != null) throw SpaceSwapException("A record with this key exists")
                if (write.swapCid != null && liveCid != write.swapCid) throw SpaceSwapException("The record changed")
                lockBlobs(write.space, write.blobs)
                val (cid, bytes) = recordCid(write.record)
                val replaced = existing?.takeUnless { it[SpaceRecordsTable.deleted] }?.get(SpaceRecordsTable.sizeBytes) ?: 0
                changeUsage(write.accountId, bytes.size.toLong() - replaced, write.quota)
                val seq = advanceState(write.space, write.author, lastSeq)
                if (existing == null) {
                    SpaceRecordsTable.insert {
                        it[spaceUri] = write.space
                        it[authorDid] = write.author
                        it[rkey] = write.rkey
                        it[accountId] = write.accountId
                        it[SpaceRecordsTable.seq] = seq
                        it[SpaceRecordsTable.cid] = cid
                        it[record] = bytes
                        it[sizeBytes] = bytes.size
                        it[deleted] = false
                        it[deletedAt] = null
                    }
                } else {
                    SpaceRecordsTable.update({ spaceRecordKey(write.space, write.author, write.rkey) }) {
                        it[SpaceRecordsTable.seq] = seq
                        it[SpaceRecordsTable.cid] = cid
                        it[record] = bytes
                        it[sizeBytes] = bytes.size
                        it[deleted] = false
                        it[deletedAt] = null
                    }
                }
                SpaceRecordBlobsTable.deleteWhere { spaceRecordBlobKey(write.space, write.author, write.rkey) }
                SpaceRecordBlobsTable.batchInsert(write.blobs.distinct(), shouldReturnGeneratedValues = false) { blobCid ->
                    this[SpaceRecordBlobsTable.spaceUri] = write.space
                    this[SpaceRecordBlobsTable.authorDid] = write.author
                    this[SpaceRecordBlobsTable.rkey] = write.rkey
                    this[SpaceRecordBlobsTable.cid] = blobCid
                }
                StoredSpaceRecord(write.rkey, cid, seq, decodeRecord(bytes))
            }
        }
    }

    override suspend fun delete(
        accountId: UUID,
        space: String,
        author: String,
        rkey: String,
        swapCid: String?,
        identityEpoch: Long,
    ): Long =
        withContext(Dispatchers.IO) {
            transaction {
                requireIdentity(accountId, identityEpoch, space, author)
                val lastSeq = lockState(accountId, space, author)
                val existing = lockedRecordRow(space, author, rkey) ?: return@transaction lastSeq
                if (existing[SpaceRecordsTable.deleted]) return@transaction existing[SpaceRecordsTable.seq]
                if (swapCid != null && existing[SpaceRecordsTable.cid] != swapCid) throw SpaceSwapException("The record changed")
                val seq = advanceState(space, author, lastSeq)
                changeUsage(existing[SpaceRecordsTable.accountId], -existing[SpaceRecordsTable.sizeBytes].toLong())
                SpaceRecordsTable.update({ spaceRecordKey(space, author, rkey) }) {
                    it[SpaceRecordsTable.seq] = seq
                    it[cid] = null
                    it[record] = null
                    it[sizeBytes] = 0
                    it[deleted] = true
                    it[deletedAt] = Clock.System.now()
                }
                SpaceRecordBlobsTable.deleteWhere { spaceRecordBlobKey(space, author, rkey) }
                seq
            }
        }

    override suspend fun get(
        space: String,
        author: String,
        rkey: String,
    ): StoredSpaceRecord? =
        withContext(Dispatchers.IO) {
            transaction {
                val row = recordRow(space, author, rkey) ?: return@transaction null
                val bytes = row[SpaceRecordsTable.record] ?: return@transaction null
                StoredSpaceRecord(rkey, requireNotNull(row[SpaceRecordsTable.cid]), row[SpaceRecordsTable.seq], decodeRecord(bytes))
            }
        }

    override suspend fun changes(
        space: String,
        author: String,
        since: Long,
        limit: Int,
    ): SpaceChangePage {
        requireChangesLimit(limit)
        return withContext(Dispatchers.IO) {
            // Sizes first, so only the records that fit the byte budget are loaded. Repeatable read
            // makes the second query see exactly the rows the first one measured.
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                val purgedThrough =
                    SpaceStatesTable
                        .select(SpaceStatesTable.purgedThroughSeq)
                        .where { spaceStateKey(space, author) }
                        .singleOrNull()
                        ?.get(SpaceStatesTable.purgedThroughSeq) ?: 0L
                requireFreshCursor(since, purgedThrough)
                val after =
                    (SpaceRecordsTable.spaceUri eq space) and (SpaceRecordsTable.authorDid eq author) and
                        (SpaceRecordsTable.seq greater since)
                val sizes =
                    SpaceRecordsTable
                        .select(SpaceRecordsTable.seq, SpaceRecordsTable.sizeBytes)
                        .where { after }
                        .orderBy(SpaceRecordsTable.seq to SortOrder.ASC)
                        .limit(limit + 1)
                        .map { it[SpaceRecordsTable.seq] to it[SpaceRecordsTable.sizeBytes].toLong() }
                val fitting = countWithinBudget(sizes.take(limit).map { it.second }, changesByteBudget)
                if (fitting == 0) return@transaction SpaceChangePage(emptyList(), since, false)
                val page =
                    SpaceRecordsTable
                        .selectAll()
                        .where { after and (SpaceRecordsTable.seq lessEq sizes[fitting - 1].first) }
                        .orderBy(SpaceRecordsTable.seq to SortOrder.ASC)
                        .map { it.toSpaceChange() }
                SpaceChangePage(page, page.lastOrNull()?.seq ?: since, sizes.size > fitting)
            }
        }
    }

    override suspend fun putBlob(
        accountId: UUID,
        space: String,
        blob: SpaceBlob,
        quota: SpaceQuota?,
        reservation: SpaceUploadReservation?,
        identityEpoch: Long,
    ): SpaceBlob = putBlobOwned(accountId, space, blob, quota, reservation, identityEpoch, null)

    override suspend fun putAdmittedBlob(
        accountId: UUID,
        space: String,
        blob: SpaceBlob,
        admissionId: String,
        quota: SpaceQuota?,
        reservation: SpaceUploadReservation?,
        identityEpoch: Long,
    ): SpaceBlob = putBlobOwned(accountId, space, blob, quota, reservation, identityEpoch, admissionId)

    private suspend fun putBlobOwned(
        accountId: UUID,
        space: String,
        blob: SpaceBlob,
        quota: SpaceQuota?,
        reservation: SpaceUploadReservation?,
        identityEpoch: Long,
        admissionId: String?,
    ): SpaceBlob =
        withContext(Dispatchers.IO) {
            val intent = blob.uploadIntent
            if (intent == null) {
                return@withContext putBlobTransactions(
                    accountId,
                    space,
                    blob,
                    quota,
                    reservation,
                    identityEpoch,
                    admissionId,
                )
            }
            val identity = UUID.nameUUIDFromBytes("space-upload:$accountId:$space:$intent".encodeToByteArray())
            val key = identity.mostSignificantBits xor identity.leastSignificantBits
            // Bounded stripes cover in-process adapters; PostgreSQL also fences across instances.
            receiptLocks[(key and Long.MAX_VALUE).rem(receiptLocks.size).toInt()].withLock {
                putBlobTransactions(accountId, space, blob, quota, reservation, identityEpoch, admissionId, key)
            }
        }

    private fun putBlobTransactions(
        accountId: UUID,
        space: String,
        blob: SpaceBlob,
        quota: SpaceQuota?,
        reservation: SpaceUploadReservation?,
        identityEpoch: Long,
        admissionId: String?,
        receiptLock: Long? = null,
    ): SpaceBlob {
        repeat(PUT_BLOB_ATTEMPTS) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                requireIdentity(accountId, identityEpoch, space)
                if (reservation != null && (reservation.accountId != accountId || reservation.identityEpoch != identityEpoch)) {
                    throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                }
                if (receiptLock != null && db.vendor == "postgresql") {
                    // Derived signed integer only; no request text is interpolated into SQL.
                    exec("SELECT pg_advisory_xact_lock($receiptLock)") { it.next() }
                }
                val publish = {
                    val previous = blob.uploadIntent?.let { blobMetadata.receipt(space, it, forWrite = true) }
                    if (previous != null) {
                        reservation?.let { held -> SpaceUploadReservationsTable.deleteWhere { id eq held.id } }
                        previous
                    } else {
                        blobMetadata.put(accountId, space, blob, quota, reservation)?.also { stored ->
                            blob.uploadIntent?.let { intent ->
                                SpaceBlobUploadReceiptsTable.insert {
                                    it[spaceUri] = space
                                    it[uploadIntent] = intent
                                    it[cid] = stored.cid
                                }
                            }
                        }
                    }
                }
                if (admissionId == null) {
                    publish()
                } else {
                    PostgreSQLObjectUploads(migrationConfig).attachSpaceInTransaction(
                        admissionId,
                        IdentityWriteExpectation(accountId.toKotlinUuid(), identityEpoch),
                        space,
                        blob.storagePath,
                        publish,
                    )
                }
            }?.let { return it }
        }
        error("Blob ${blob.cid} was purged during every attempt to store it")
    }

    private fun requireIdentity(
        accountId: UUID,
        identityEpoch: Long,
        space: String? = null,
        author: String? = null,
    ) {
        val outcome = identityOutcome(accountId, identityEpoch, space, author)
        if (outcome != IdentityFenceOutcome.ALLOWED) throw IdentityWriteRefusedException(outcome)
    }

    private fun identityOutcome(
        accountId: UUID,
        identityEpoch: Long,
        space: String? = null,
        author: String? = null,
    ): IdentityFenceOutcome {
        val connection = TransactionManager.current().connection.connection as Connection
        return connection.lockIdentityWrite(
            IdentityWriteExpectation(accountId.toKotlinUuid(), identityEpoch),
            Clock.System.now(),
            space,
            author,
        )
    }

    private companion object {
        val receiptLocks = Array(64) { Mutex() }
    }

    override suspend fun reserveUpload(
        accountId: UUID,
        bytes: Long,
        quota: SpaceQuota,
        identityEpoch: Long,
    ): SpaceUploadReservation =
        withContext(Dispatchers.IO) {
            transaction {
                requireIdentity(accountId, identityEpoch)
                requireRoom(quota, lockUsage(accountId) + reservedBytes(accountId, except = null), bytes)
                val reservation = SpaceUploadReservation(UUID.randomUUID(), accountId, bytes, identityEpoch)
                SpaceUploadReservationsTable.insert {
                    it[id] = reservation.id
                    it[SpaceUploadReservationsTable.accountId] = accountId
                    it[SpaceUploadReservationsTable.bytes] = bytes
                    it[expiresAt] = Clock.System.now() + UPLOAD_RESERVATION_LIFETIME
                }
                reservation
            }
        }

    override suspend fun releaseUpload(reservation: SpaceUploadReservation) {
        withContext(Dispatchers.IO) {
            transaction {
                requireIdentity(reservation.accountId, reservation.identityEpoch)
                SpaceUploadReservationsTable.deleteWhere { (id eq reservation.id) and (accountId eq reservation.accountId) }
            }
        }
    }

    override suspend fun storedBytes(accountId: UUID): Long =
        withContext(Dispatchers.IO) {
            transaction {
                AccountStorageUsageTable
                    .select(AccountStorageUsageTable.spaceBytes)
                    .where { AccountStorageUsageTable.accountId eq accountId }
                    .singleOrNull()
                    ?.get(AccountStorageUsageTable.spaceBytes) ?: 0L
            }
        }

    override suspend fun getBlob(
        space: String,
        cid: String,
    ): SpaceBlob? = blobMetadata.get(space, cid)

    override suspend fun getBlobUploadReceipt(
        space: String,
        intent: String,
    ): SpaceBlob? = blobMetadata.getReceipt(space, intent)

    override suspend fun purge(
        tombstonesBefore: Instant,
        unreferencedBlobsBefore: Instant,
        deleteObject: (storagePath: String) -> Unit,
    ): SpacePurgeResult =
        PostgreSQLSpaceMaintenance(objectDeleteTimeout) { owner, delta -> changeUsage(owner, delta) }
            .purge(tombstonesBefore, unreferencedBlobsBefore, deleteObject)

    /**
     * Locks the space blobs [cids] refers to, in key order, and throws [SpaceMissingBlobException] for
     * one that doesn't exist. Purging takes the same row lock, so it can't delete a blob this write
     * is about to refer to.
     */
    private fun lockBlobs(
        space: String,
        cids: List<String>,
    ) {
        val wanted = cids.distinct().sorted()
        if (wanted.isEmpty()) return
        val found =
            SpaceBlobsTable
                .select(SpaceBlobsTable.cid)
                .where {
                    (SpaceBlobsTable.spaceUri eq space) and (SpaceBlobsTable.cid inList wanted) and
                        SpaceBlobsTable.purgeOperationId.isNull()
                }.orderBy(SpaceBlobsTable.cid to SortOrder.ASC)
                .forUpdate()
                .map { it[SpaceBlobsTable.cid] }
                .toSet()
        wanted.firstOrNull { it !in found }?.let { throw SpaceMissingBlobException(it) }
    }

    private fun changeUsage(
        accountId: UUID,
        delta: Long,
        quota: SpaceQuota? = null,
        reservation: UUID? = null,
    ) = PostgreSQLSpaceUsage.change(accountId, delta, quota, reservation)

    private fun lockUsage(accountId: UUID): Long = PostgreSQLSpaceUsage.lock(accountId)

    private fun reservedBytes(
        accountId: UUID,
        except: UUID?,
    ): Long = PostgreSQLSpaceUsage.reserved(accountId, except)

    /** Creates the state row if needed, locks it, and returns its last sequence number. */
    private fun lockState(
        accountId: UUID,
        space: String,
        author: String,
    ): Long {
        SpaceStatesTable.insertIgnore {
            it[spaceUri] = space
            it[authorDid] = author
            it[SpaceStatesTable.accountId] = accountId
            it[lastSeq] = 0L
        }
        return SpaceStatesTable
            .selectAll()
            .where { spaceStateKey(space, author) }
            .forUpdate()
            .single()[SpaceStatesTable.lastSeq]
    }

    private fun advanceState(
        space: String,
        author: String,
        lastSeq: Long,
    ): Long {
        val next = lastSeq + 1
        SpaceStatesTable.update({ spaceStateKey(space, author) }) { it[SpaceStatesTable.lastSeq] = next }
        return next
    }

    private fun recordRow(
        space: String,
        author: String,
        rkey: String,
    ): ResultRow? =
        SpaceRecordsTable
            .selectAll()
            .where { spaceRecordKey(space, author, rkey) }
            .singleOrNull()

    /** Locks the row too, so purging can't remove a tombstone this transaction is about to rewrite. */
    private fun lockedRecordRow(
        space: String,
        author: String,
        rkey: String,
    ): ResultRow? =
        SpaceRecordsTable
            .selectAll()
            .where { spaceRecordKey(space, author, rkey) }
            .forUpdate()
            .singleOrNull()
}
