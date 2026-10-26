@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.sync

import app.logdate.shared.model.sync.DeviceId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

internal fun dbUpsertJournal(
    userId: UUID,
    record: JournalRecord,
): JournalRecord =
    transaction {
        val existing =
            JournalSyncTable
                .selectAll()
                .where {
                    (JournalSyncTable.id eq record.id) and (JournalSyncTable.userId eq userId)
                }.forUpdate()
                .singleOrNull()
        if (existing != null && existing[JournalSyncTable.deleted]) {
            throw StaleWriteException(record.id, existing[JournalSyncTable.deletedAt] ?: 0L)
        }
        val serverVersion = nextVersion(existing?.get(JournalSyncTable.serverVersion))
        if (existing == null) {
            JournalSyncTable.insert {
                it[id] = record.id
                it[JournalSyncTable.userId] = userId
                it[title] = record.title
                it[description] = record.description
                it[createdAt] = record.createdAt
                it[lastUpdated] = record.lastUpdated
                it[JournalSyncTable.serverVersion] = serverVersion
                it[deviceId] = record.deviceId.value
                it[deleted] = false
                it[deletedAt] = null
            }
        } else {
            JournalSyncTable.update({ (JournalSyncTable.id eq record.id) and (JournalSyncTable.userId eq userId) }) {
                it[title] = record.title
                it[description] = record.description
                it[lastUpdated] = record.lastUpdated
                it[JournalSyncTable.serverVersion] = serverVersion
                it[deviceId] = record.deviceId.value
                it[deleted] = false
                it[deletedAt] = null
            }
        }
        record.copy(serverVersion = serverVersion, lastUpdated = currentTimestamp())
    }

internal fun dbGetJournal(
    userId: UUID,
    id: String,
): JournalRecord? =
    transaction {
        JournalSyncTable
            .selectAll()
            .where {
                (JournalSyncTable.id eq id) and
                    (JournalSyncTable.userId eq userId) and
                    (JournalSyncTable.deleted eq false)
            }.singleOrNull()
            ?.toJournalRecord()
    }

internal fun dbDeleteJournal(
    userId: UUID,
    id: String,
    deletedAt: Long,
) {
    transaction {
        val existing =
            JournalSyncTable
                .selectAll()
                .where { (JournalSyncTable.id eq id) and (JournalSyncTable.userId eq userId) }
                .forUpdate()
                .singleOrNull()
        val existingVersion = existing?.get(JournalSyncTable.serverVersion)
        val retainedDeletedAt = maxOf(deletedAt, existing?.get(JournalSyncTable.deletedAt) ?: deletedAt)
        val newVersion = nextVersion(existingVersion)
        if (existingVersion == null) {
            JournalSyncTable.insert {
                it[JournalSyncTable.id] = id
                it[JournalSyncTable.userId] = userId
                it[title] = ""
                it[description] = ""
                it[createdAt] = retainedDeletedAt
                it[lastUpdated] = retainedDeletedAt
                it[serverVersion] = newVersion
                it[deviceId] = DeviceId.UNKNOWN.value
                it[deleted] = true
                it[JournalSyncTable.deletedAt] = retainedDeletedAt
            }
        } else {
            JournalSyncTable.update({ (JournalSyncTable.id eq id) and (JournalSyncTable.userId eq userId) }) {
                it[deleted] = true
                it[JournalSyncTable.deletedAt] = retainedDeletedAt
                it[lastUpdated] = retainedDeletedAt
                it[JournalSyncTable.serverVersion] = newVersion
            }
        }
    }
}

internal fun dbJournalChanges(
    userId: UUID,
    since: Long,
    limit: Int,
): ChangeSet<JournalRecord, JournalDeletionMarker> =
    transaction {
        val changeRows =
            JournalSyncTable
                .selectAll()
                .where {
                    (JournalSyncTable.userId eq userId) and
                        (JournalSyncTable.deleted eq false) and
                        (JournalSyncTable.serverVersion greater since)
                }.orderBy(JournalSyncTable.serverVersion to SortOrder.ASC)
                .limit(limit + 1)
                .toList()
        val hasMoreChanges = changeRows.size > limit
        val changes = changeRows.take(limit).map { it.toJournalRecord() }
        val changeVersionMax = changes.maxOfOrNull { it.serverVersion }

        val deletionRows =
            JournalSyncTable
                .selectAll()
                .where {
                    (JournalSyncTable.userId eq userId) and
                        (JournalSyncTable.deleted eq true) and
                        (JournalSyncTable.serverVersion greater since)
                }.orderBy(JournalSyncTable.serverVersion to SortOrder.ASC)
                .limit(limit + 1)
                .toList()
        val hasMoreDeletions = deletionRows.size > limit
        val deletions =
            deletionRows.take(limit).map { row ->
                JournalDeletionMarker(
                    row[JournalSyncTable.id],
                    row[JournalSyncTable.deletedAt] ?: row[JournalSyncTable.lastUpdated],
                    row[JournalSyncTable.serverVersion],
                )
            }
        val deletionVersionMax =
            deletionRows.take(limit).maxOfOrNull { it[JournalSyncTable.serverVersion] }

        val lastTimestamp =
            listOfNotNull(changeVersionMax, deletionVersionMax).maxOrNull() ?: since

        ChangeSet(changes, deletions, lastTimestamp, hasMoreChanges || hasMoreDeletions)
    }
