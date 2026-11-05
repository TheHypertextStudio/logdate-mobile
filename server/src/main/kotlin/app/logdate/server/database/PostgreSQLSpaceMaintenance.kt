package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.space.SpacePurgeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

private const val PURGE_BATCH_SIZE = 500

/** Maintenance uses the same account-before-space boundary as interactive writes. */
@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLSpaceMaintenance(
    private val objectDeleteTimeout: Duration,
    private val adjustUsage: (UUID, Long) -> Unit,
) {
    suspend fun purge(
        tombstonesBefore: Instant,
        unreferencedBlobsBefore: Instant,
        deleteObject: (storagePath: String) -> Unit,
    ): SpacePurgeResult =
        withContext(Dispatchers.IO) {
            purgeExpiredReservations()
            purgeTombstones(tombstonesBefore)
            var deleted = 0
            var failed = 0
            var after: Pair<String, String>? = null
            do {
                val batch = purgeCandidates(unreferencedBlobsBefore, after)
                batch.forEach { (space, cid) ->
                    when (purgeBlob(space, cid, unreferencedBlobsBefore, deleteObject)) {
                        SpaceBlobPurgeOutcome.DELETED -> deleted++
                        SpaceBlobPurgeOutcome.FAILED -> failed++
                        SpaceBlobPurgeOutcome.KEPT -> Unit
                    }
                }
                after = batch.lastOrNull()
            } while (batch.size == PURGE_BATCH_SIZE)
            SpacePurgeResult(deleted, failed)
        }

    private fun purgeExpiredReservations() {
        val now = Clock.System.now()
        val owners =
            transaction {
                SpaceUploadReservationsTable
                    .select(SpaceUploadReservationsTable.accountId)
                    .where { SpaceUploadReservationsTable.expiresAt lessEq now }
                    .withDistinct()
                    .map { it[SpaceUploadReservationsTable.accountId] }
            }
        owners.forEach { owner ->
            transaction {
                val epoch = currentEpoch(owner) ?: return@transaction
                if (identityOutcome(owner, epoch) != IdentityFenceOutcome.ALLOWED) return@transaction
                SpaceUploadReservationsTable.deleteWhere { (accountId eq owner) and (expiresAt lessEq now) }
            }
        }
    }

    /**
     * Deletes tombstones made before [before], one repository at a time. Each repository's state row is
     * locked after the account fence, and its purged-through sequence number is raised to the
     * highest tombstone deleted, in the same transaction.
     */
    private fun purgeTombstones(before: Instant) {
        val old = (SpaceRecordsTable.deleted eq true) and (SpaceRecordsTable.deletedAt less before)
        val repositories =
            transaction {
                SpaceRecordsTable
                    .select(SpaceRecordsTable.accountId, SpaceRecordsTable.spaceUri, SpaceRecordsTable.authorDid)
                    .where { old }
                    .withDistinct()
                    .map { Triple(it[SpaceRecordsTable.accountId], it[SpaceRecordsTable.spaceUri], it[SpaceRecordsTable.authorDid]) }
            }
        repositories.forEach { (owner, space, author) ->
            transaction {
                val epoch = currentEpoch(owner) ?: return@transaction
                if (identityOutcome(owner, epoch, space, author) != IdentityFenceOutcome.ALLOWED) return@transaction
                val purgedThrough =
                    SpaceStatesTable
                        .select(SpaceStatesTable.purgedThroughSeq)
                        .where { stateKey(space, author) and (SpaceStatesTable.accountId eq owner) }
                        .forUpdate()
                        .singleOrNull()
                        ?.get(SpaceStatesTable.purgedThroughSeq) ?: return@transaction
                val tombstones =
                    old and (SpaceRecordsTable.spaceUri eq space) and (SpaceRecordsTable.authorDid eq author) and
                        (SpaceRecordsTable.accountId eq owner)
                val highest = SpaceRecordsTable.seq.max()
                val highestSeq =
                    SpaceRecordsTable
                        .select(highest)
                        .where { tombstones }
                        .single()[highest] ?: return@transaction
                SpaceRecordsTable.deleteWhere { tombstones }
                if (highestSeq > purgedThrough) {
                    SpaceStatesTable.update({ stateKey(space, author) }) { it[SpaceStatesTable.purgedThroughSeq] = highestSeq }
                }
            }
        }
    }

    /**
     * The next [PURGE_BATCH_SIZE] blobs, in key order after [after], that were created before
     * [createdBefore] and that no record refers to. Paging by key rather than restarting from the
     * first keeps a blob whose object delete failed from being picked again in the same purge.
     */
    private fun purgeCandidates(
        createdBefore: Instant,
        after: Pair<String, String>?,
    ): List<Pair<String, String>> =
        transaction {
            SpaceBlobsTable
                .select(SpaceBlobsTable.spaceUri, SpaceBlobsTable.cid)
                .where {
                    val candidate =
                        ((SpaceBlobsTable.createdAt less createdBefore) or SpaceBlobsTable.purgeOperationId.isNotNull()) and unreferenced()
                    if (after == null) {
                        candidate
                    } else {
                        val (space, cid) = after
                        candidate and
                            (
                                (SpaceBlobsTable.spaceUri greater space) or
                                    ((SpaceBlobsTable.spaceUri eq space) and (SpaceBlobsTable.cid greater cid))
                            )
                    }
                }.orderBy(SpaceBlobsTable.spaceUri to SortOrder.ASC, SpaceBlobsTable.cid to SortOrder.ASC)
                .limit(PURGE_BATCH_SIZE)
                .map { it[SpaceBlobsTable.spaceUri] to it[SpaceBlobsTable.cid] }
        }

    private fun purgeBlob(
        space: String,
        cid: String,
        createdBefore: Instant,
        deleteObject: (storagePath: String) -> Unit,
    ): SpaceBlobPurgeOutcome =
        PostgreSQLSpaceBlobPurge(objectDeleteTimeout, adjustUsage)
            .purge(space, cid, createdBefore, deleteObject)

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

    private fun currentEpoch(accountId: UUID): Long? =
        AccountsTable
            .select(AccountsTable.identityEpoch)
            .where { AccountsTable.id eq accountId }
            .singleOrNull()
            ?.get(AccountsTable.identityEpoch)

    private fun stateKey(
        space: String,
        author: String,
    ): Op<Boolean> = (SpaceStatesTable.spaceUri eq space) and (SpaceStatesTable.authorDid eq author)

    private fun unreferenced(): Op<Boolean> =
        notExists(
            SpaceRecordBlobsTable
                .select(SpaceRecordBlobsTable.cid)
                .where {
                    (SpaceRecordBlobsTable.spaceUri eq SpaceBlobsTable.spaceUri) and (SpaceRecordBlobsTable.cid eq SpaceBlobsTable.cid)
                },
        )
}
