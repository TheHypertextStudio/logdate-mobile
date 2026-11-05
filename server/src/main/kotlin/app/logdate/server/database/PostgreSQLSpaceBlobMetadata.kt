package app.logdate.server.database

import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceUploadReservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock

/** Metadata writes join the caller's account-first transaction; read entrypoints own their transaction. */
internal class PostgreSQLSpaceBlobMetadata(
    private val changeUsage: (UUID, Long, SpaceQuota?, UUID?) -> Unit,
) {
    /**
     * Inserts the blob row, or locks and refreshes the existing one and returns it with its original
     * storage path. Returns null when the existing row was purged between the two statements, so the
     * caller tries again rather than answering success without a row. A new row counts towards the
     * account's stored bytes, checked against [quota]; either way [reservation] is used up.
     */
    fun put(
        accountId: UUID,
        space: String,
        blob: SpaceBlob,
        quota: SpaceQuota?,
        reservation: SpaceUploadReservation?,
    ): SpaceBlob? {
        requireUnretiredObjectPath(blob.storagePath)
        val now = Clock.System.now()
        val inserted =
            SpaceBlobsTable
                .insertIgnore {
                    it[spaceUri] = space
                    it[cid] = blob.cid
                    it[SpaceBlobsTable.accountId] = accountId
                    it[mimeType] = blob.mimeType
                    it[sizeBytes] = blob.size
                    it[storagePath] = blob.storagePath
                    it[createdAt] = now
                }.insertedCount
        if (inserted > 0) {
            changeUsage(accountId, blob.size, quota, reservation?.id)
            reservation?.let { held -> SpaceUploadReservationsTable.deleteWhere { id eq held.id } }
            return blob
        }
        val key = (SpaceBlobsTable.spaceUri eq space) and (SpaceBlobsTable.cid eq blob.cid)
        val existing =
            SpaceBlobsTable
                .select(SpaceBlobsTable.storagePath, SpaceBlobsTable.purgeOperationId)
                .where { key }
                .forUpdate()
                .singleOrNull() ?: return null
        if (existing[SpaceBlobsTable.purgeOperationId] != null) {
            throw app.logdate.server.space
                .SpaceBlobPurgingException()
        }
        val existingPath = existing[SpaceBlobsTable.storagePath]
        SpaceBlobsTable.update({ key }) {
            it[mimeType] = blob.mimeType
            it[sizeBytes] = blob.size
            it[createdAt] = now
        }
        reservation?.let { held -> SpaceUploadReservationsTable.deleteWhere { id eq held.id } }
        return blob.copy(storagePath = existingPath)
    }

    suspend fun get(
        space: String,
        cid: String,
    ): SpaceBlob? =
        withContext(Dispatchers.IO) {
            transaction {
                SpaceBlobsTable
                    .selectAll()
                    .where {
                        (SpaceBlobsTable.spaceUri eq space) and (SpaceBlobsTable.cid eq cid) and
                            SpaceBlobsTable.purgeOperationId.isNull()
                    }.singleOrNull()
                    ?.let {
                        SpaceBlob(
                            it[SpaceBlobsTable.cid],
                            it[SpaceBlobsTable.mimeType],
                            it[SpaceBlobsTable.sizeBytes],
                            it[SpaceBlobsTable.storagePath],
                        )
                    }
            }
        }

    suspend fun getReceipt(
        space: String,
        intent: String,
    ): SpaceBlob? =
        withContext(Dispatchers.IO) {
            transaction { receipt(space, intent) }
        }

    fun receipt(
        space: String,
        intent: String,
        forWrite: Boolean = false,
    ): SpaceBlob? {
        val cid =
            SpaceBlobUploadReceiptsTable
                .select(SpaceBlobUploadReceiptsTable.cid)
                .where { (SpaceBlobUploadReceiptsTable.spaceUri eq space) and (SpaceBlobUploadReceiptsTable.uploadIntent eq intent) }
                .singleOrNull()
                ?.get(SpaceBlobUploadReceiptsTable.cid) ?: return null
        return SpaceBlobsTable
            .selectAll()
            .where { (SpaceBlobsTable.spaceUri eq space) and (SpaceBlobsTable.cid eq cid) }
            .singleOrNull()
            ?.let {
                if (it[SpaceBlobsTable.purgeOperationId] != null) {
                    if (forWrite) {
                        throw app.logdate.server.space
                            .SpaceBlobPurgingException()
                    }
                    return null
                }
                SpaceBlob(
                    it[SpaceBlobsTable.cid],
                    it[SpaceBlobsTable.mimeType],
                    it[SpaceBlobsTable.sizeBytes],
                    it[SpaceBlobsTable.storagePath],
                    intent,
                )
            }
    }
}
