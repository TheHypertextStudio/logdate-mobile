package app.logdate.server.database

import app.logdate.server.logdate.LogDateAtprotoBlob
import app.logdate.server.logdate.LogDateAtprotoBlobRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

internal class PostgreSQLLogDateAtprotoBlobRepository : LogDateAtprotoBlobRepository {
    override fun upsertBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
        identityEpoch: Long,
    ): LogDateAtprotoBlob =
        transaction {
            requireAccountIdentityWrite(userId, identityEpoch)
            requireUnretiredObjectPath(blob.storagePath)
            val existing =
                LogDateAtprotoBlobsTable
                    .selectAll()
                    .where {
                        (LogDateAtprotoBlobsTable.userId eq userId) and
                            (LogDateAtprotoBlobsTable.cid eq blob.cid)
                    }.singleOrNull()
            if (existing == null) {
                LogDateAtprotoBlobsTable.insert {
                    it[LogDateAtprotoBlobsTable.userId] = userId
                    it[cid] = blob.cid
                    it[mimeType] = blob.mimeType
                    it[sizeBytes] = blob.sizeBytes
                    it[storagePath] = blob.storagePath
                    it[createdAt] = blob.createdAt
                }
            } else {
                LogDateAtprotoBlobsTable.update({
                    (LogDateAtprotoBlobsTable.userId eq userId) and
                        (LogDateAtprotoBlobsTable.cid eq blob.cid)
                }) {
                    it[mimeType] = blob.mimeType
                    it[sizeBytes] = blob.sizeBytes
                    it[storagePath] = blob.storagePath
                    it[createdAt] = blob.createdAt
                }
            }
            blob.copy(userId = userId)
        }

    override fun getBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob? =
        transaction {
            LogDateAtprotoBlobsTable
                .selectAll()
                .where {
                    (LogDateAtprotoBlobsTable.userId eq userId) and
                        (LogDateAtprotoBlobsTable.cid eq cid)
                }.singleOrNull()
                ?.toLogDateAtprotoBlob()
        }

    private fun ResultRow.toLogDateAtprotoBlob(): LogDateAtprotoBlob =
        LogDateAtprotoBlob(
            cid = this[LogDateAtprotoBlobsTable.cid],
            userId = this[LogDateAtprotoBlobsTable.userId],
            mimeType = this[LogDateAtprotoBlobsTable.mimeType],
            sizeBytes = this[LogDateAtprotoBlobsTable.sizeBytes],
            storagePath = this[LogDateAtprotoBlobsTable.storagePath],
            createdAt = this[LogDateAtprotoBlobsTable.createdAt],
        )
}
