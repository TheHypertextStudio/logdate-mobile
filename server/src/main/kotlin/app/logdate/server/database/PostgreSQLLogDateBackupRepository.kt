package app.logdate.server.database

import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBackupRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

internal class PostgreSQLLogDateBackupRepository : LogDateBackupRepository {
    override fun cleanupUnreferencedObject(
        userId: UUID,
        path: String,
        identityEpoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean = PostgreSQLLegacyObjectDeletion().cleanup(userId, path, identityEpoch, deleteObject)

    override fun createBackup(
        userId: UUID,
        backup: LogDateBackup,
        identityEpoch: Long,
    ): LogDateBackup =
        transaction {
            requireAccountIdentityWrite(userId, identityEpoch)
            requireLegacyObjectMutation(userId, LegacyObjectKind.BACKUP, backup.id.toString(), backup.storagePath)
            LogDateBackupsTable.insert {
                it[id] = backup.id
                it[LogDateBackupsTable.userId] = userId
                it[deviceId] = backup.deviceId
                it[manifest] = backup.manifest
                it[storagePath] = backup.storagePath
                it[createdAt] = backup.createdAt
                it[sizeBytes] = backup.sizeBytes
            }
            backup.copy(userId = userId)
        }

    override fun getBackup(
        userId: UUID,
        id: UUID,
    ): LogDateBackup? =
        transaction {
            LogDateBackupsTable
                .selectAll()
                .where {
                    (LogDateBackupsTable.userId eq userId) and
                        (LogDateBackupsTable.id eq id)
                }.singleOrNull()
                ?.toLogDateBackup()
        }

    override fun listBackups(userId: UUID): List<LogDateBackup> =
        transaction {
            LogDateBackupsTable
                .selectAll()
                .where { LogDateBackupsTable.userId eq userId }
                .orderBy(LogDateBackupsTable.createdAt to SortOrder.DESC)
                .map { row -> row.toLogDateBackup() }
        }

    override fun deleteBackupWithObject(
        userId: UUID,
        id: UUID,
        identityEpoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean =
        PostgreSQLLegacyObjectDeletion().delete(userId, LegacyObjectKind.BACKUP, id.toString(), identityEpoch, deleteObject) {
            deleteBackup(userId, id, identityEpoch)
        }

    override fun deleteBackup(
        userId: UUID,
        id: UUID,
        identityEpoch: Long,
    ) {
        transaction {
            requireAccountIdentityWrite(userId, identityEpoch)
            requireLegacyObjectMutation(userId, LegacyObjectKind.BACKUP, id.toString())
            LogDateBackupsTable.deleteWhere {
                (LogDateBackupsTable.userId eq userId) and
                    (LogDateBackupsTable.id eq id)
            }
        }
    }

    private fun ResultRow.toLogDateBackup(): LogDateBackup =
        LogDateBackup(
            id = this[LogDateBackupsTable.id],
            userId = this[LogDateBackupsTable.userId],
            deviceId = this[LogDateBackupsTable.deviceId],
            manifest = this[LogDateBackupsTable.manifest],
            storagePath = this[LogDateBackupsTable.storagePath],
            createdAt = this[LogDateBackupsTable.createdAt],
            sizeBytes = this[LogDateBackupsTable.sizeBytes],
        )
}
