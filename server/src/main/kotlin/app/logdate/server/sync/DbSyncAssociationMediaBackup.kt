@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.sync

import app.logdate.shared.model.sync.DeviceId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi

internal fun dbUpsertAssociations(
    userId: UUID,
    records: List<AssociationRecord>,
) {
    transaction {
        records.forEach { record ->
            val key = AssociationKey(record.journalId, record.contentId)
            val existing =
                AssociationSyncTable
                    .selectAll()
                    .where {
                        (AssociationSyncTable.journalId eq key.journalId) and
                            (AssociationSyncTable.contentId eq key.contentId) and
                            (AssociationSyncTable.userId eq userId)
                    }.forUpdate()
                    .singleOrNull()
            if (existing != null && existing[AssociationSyncTable.deleted]) {
                throw StaleWriteException("${key.journalId}/${key.contentId}", existing[AssociationSyncTable.deletedAt] ?: 0L)
            }
            val serverVersion = nextVersion(existing?.get(AssociationSyncTable.serverVersion))
            if (existing == null) {
                AssociationSyncTable.insert {
                    it[journalId] = key.journalId
                    it[contentId] = key.contentId
                    it[AssociationSyncTable.userId] = userId
                    it[createdAt] = record.createdAt
                    it[AssociationSyncTable.serverVersion] = serverVersion
                    it[deviceId] = record.deviceId.value
                    it[deleted] = false
                    it[deletedAt] = null
                }
            } else {
                AssociationSyncTable.update({
                    (AssociationSyncTable.journalId eq key.journalId) and
                        (AssociationSyncTable.contentId eq key.contentId) and
                        (AssociationSyncTable.userId eq userId)
                }) {
                    it[createdAt] = record.createdAt
                    it[AssociationSyncTable.serverVersion] = serverVersion
                    it[deviceId] = record.deviceId.value
                    it[deleted] = false
                    it[deletedAt] = null
                }
            }
        }
    }
}

internal fun dbDeleteAssociations(
    userId: UUID,
    keys: List<AssociationKey>,
    deletedAt: Long,
) {
    transaction {
        keys.forEach { key ->
            val existing =
                AssociationSyncTable
                    .selectAll()
                    .where {
                        (AssociationSyncTable.journalId eq key.journalId) and
                            (AssociationSyncTable.contentId eq key.contentId) and (AssociationSyncTable.userId eq userId)
                    }.forUpdate()
                    .singleOrNull()
            val newVersion = nextVersion(existing?.get(AssociationSyncTable.serverVersion))
            val retainedDeletedAt = maxOf(deletedAt, existing?.get(AssociationSyncTable.deletedAt) ?: deletedAt)
            if (existing == null) {
                AssociationSyncTable.insert {
                    it[journalId] = key.journalId
                    it[contentId] = key.contentId
                    it[AssociationSyncTable.userId] = userId
                    it[createdAt] = retainedDeletedAt
                    it[serverVersion] = newVersion
                    it[deviceId] = DeviceId.UNKNOWN.value
                    it[deleted] = true
                    it[AssociationSyncTable.deletedAt] = retainedDeletedAt
                }
            } else {
                AssociationSyncTable.update({
                    (AssociationSyncTable.journalId eq key.journalId) and
                        (AssociationSyncTable.contentId eq key.contentId) and (AssociationSyncTable.userId eq userId)
                }) {
                    it[deleted] = true
                    it[AssociationSyncTable.deletedAt] = retainedDeletedAt
                    it[serverVersion] = newVersion
                }
            }
        }
    }
}

internal fun dbAssociationChanges(
    userId: UUID,
    since: Long,
    limit: Int,
): ChangeSet<AssociationRecord, AssociationDeletionMarker> =
    transaction {
        val changeRows =
            AssociationSyncTable
                .selectAll()
                .where {
                    (AssociationSyncTable.userId eq userId) and
                        (AssociationSyncTable.deleted eq false) and
                        (AssociationSyncTable.serverVersion greater since)
                }.orderBy(AssociationSyncTable.serverVersion to SortOrder.ASC)
                .limit(limit + 1)
                .toList()
        val hasMoreChanges = changeRows.size > limit
        val changes = changeRows.take(limit).map { it.toAssociationRecord() }
        val changeVersionMax = changes.maxOfOrNull { it.serverVersion }

        val deletionRows =
            AssociationSyncTable
                .selectAll()
                .where {
                    (AssociationSyncTable.userId eq userId) and
                        (AssociationSyncTable.deleted eq true) and
                        (AssociationSyncTable.serverVersion greater since)
                }.orderBy(AssociationSyncTable.serverVersion to SortOrder.ASC)
                .limit(limit + 1)
                .toList()
        val hasMoreDeletions = deletionRows.size > limit
        val deletions =
            deletionRows.take(limit).map { row ->
                AssociationDeletionMarker(
                    AssociationKey(
                        row[AssociationSyncTable.journalId],
                        row[AssociationSyncTable.contentId],
                    ),
                    row[AssociationSyncTable.deletedAt] ?: row[AssociationSyncTable.createdAt],
                    row[AssociationSyncTable.serverVersion],
                )
            }
        val deletionVersionMax =
            deletionRows.take(limit).maxOfOrNull { it[AssociationSyncTable.serverVersion] }

        val lastTimestamp =
            listOfNotNull(changeVersionMax, deletionVersionMax).maxOrNull() ?: since

        ChangeSet(changes, deletions, lastTimestamp, hasMoreChanges || hasMoreDeletions)
    }

// --- Media ---

internal fun dbUpsertMedia(
    userId: UUID,
    record: MediaRecord,
): MediaRecord =
    transaction {
        val id =
            if (record.mediaId.isBlank()) "media-${Random.nextLong().absoluteValue}" else record.mediaId
        val existing =
            MediaSyncTable
                .selectAll()
                .where {
                    (MediaSyncTable.mediaId eq id) and (MediaSyncTable.userId eq userId)
                }.forUpdate()
                .singleOrNull()
        if (existing != null && existing[MediaSyncTable.deleted]) {
            throw StaleWriteException(id, existing[MediaSyncTable.deletedAt] ?: 0L)
        }
        val serverVersion = nextVersion(existing?.get(MediaSyncTable.serverVersion))
        if (existing == null) {
            MediaSyncTable.insert {
                it[mediaId] = id
                it[MediaSyncTable.userId] = userId
                it[contentId] = record.contentId
                it[fileName] = record.fileName
                it[mimeType] = record.mimeType
                it[sizeBytes] = record.sizeBytes
                it[data] = record.data
                it[storagePath] = record.storagePath
                it[createdAt] = record.createdAt
                it[MediaSyncTable.serverVersion] = serverVersion
                it[deviceId] = record.deviceId.value
                it[deleted] = false
                it[deletedAt] = null
                it[encryptionVersion] = record.encryptionVersion
                it[encryptionKeyId] = record.encryptionKeyId
                it[encryptionMode] = record.encryptionMode
            }
        } else {
            MediaSyncTable.update({ (MediaSyncTable.mediaId eq id) and (MediaSyncTable.userId eq userId) }) {
                it[fileName] = record.fileName
                it[mimeType] = record.mimeType
                it[sizeBytes] = record.sizeBytes
                it[data] = record.data
                it[storagePath] = record.storagePath
                it[MediaSyncTable.serverVersion] = serverVersion
                it[deviceId] = record.deviceId.value
                it[deleted] = false
                it[encryptionVersion] = record.encryptionVersion
                it[encryptionKeyId] = record.encryptionKeyId
                it[encryptionMode] = record.encryptionMode
                it[deletedAt] = null
            }
        }
        record.copy(mediaId = id, serverVersion = serverVersion, createdAt = currentTimestamp())
    }

internal fun dbGetMedia(
    userId: UUID,
    mediaId: String,
): MediaRecord? =
    transaction {
        MediaSyncTable
            .selectAll()
            .where {
                (MediaSyncTable.mediaId eq mediaId) and
                    (MediaSyncTable.userId eq userId) and
                    (MediaSyncTable.deleted eq false)
            }.forUpdate()
            .singleOrNull()
            ?.toMediaRecord()
    }

internal fun dbDeleteMedia(
    userId: UUID,
    mediaId: String,
    deletedAt: Long,
) {
    transaction {
        val existing =
            MediaSyncTable
                .selectAll()
                .where {
                    (MediaSyncTable.mediaId eq mediaId) and (MediaSyncTable.userId eq userId)
                }.forUpdate()
                .singleOrNull()
        val newVersion = nextVersion(existing?.get(MediaSyncTable.serverVersion))
        val retainedDeletedAt = maxOf(deletedAt, existing?.get(MediaSyncTable.deletedAt) ?: deletedAt)
        if (existing == null) {
            MediaSyncTable.insert {
                it[MediaSyncTable.mediaId] = mediaId
                it[MediaSyncTable.userId] = userId
                it[contentId] = ""
                it[fileName] = ""
                it[mimeType] = "application/octet-stream"
                it[sizeBytes] = 0
                it[data] = byteArrayOf()
                it[createdAt] = retainedDeletedAt
                it[serverVersion] = newVersion
                it[deviceId] = DeviceId.UNKNOWN.value
                it[deleted] = true
                it[MediaSyncTable.deletedAt] = retainedDeletedAt
            }
        } else {
            MediaSyncTable.update({ (MediaSyncTable.mediaId eq mediaId) and (MediaSyncTable.userId eq userId) }) {
                it[deleted] = true
                it[MediaSyncTable.deletedAt] = retainedDeletedAt
                it[serverVersion] = newVersion
            }
        }
    }
}

internal fun dbListAllMediaForUser(userId: UUID): List<MediaRecord> =
    transaction {
        MediaSyncTable
            .selectAll()
            .where { MediaSyncTable.userId eq userId }
            .map { it.toMediaRecord() }
    }

internal fun dbCreateBackupRecord(
    userId: UUID,
    record: BackupRecord,
): BackupRecord =
    transaction {
        BackupSyncTable.insert {
            it[id] = record.id
            it[BackupSyncTable.userId] = userId
            it[deviceId] = record.deviceId
            it[manifest] = record.manifest
            it[storagePath] = record.storagePath
            it[createdAt] = record.createdAt
            it[sizeBytes] = record.sizeBytes
        }
        record
    }

internal fun dbGetBackupRecord(
    userId: UUID,
    id: UUID,
): BackupRecord? =
    transaction {
        BackupSyncTable
            .selectAll()
            .where {
                (BackupSyncTable.id eq id) and (BackupSyncTable.userId eq userId)
            }.forUpdate()
            .singleOrNull()
            ?.toBackupRecord()
    }

internal fun dbListBackups(userId: UUID): List<BackupRecord> =
    transaction {
        BackupSyncTable
            .selectAll()
            .where { BackupSyncTable.userId eq userId }
            .orderBy(BackupSyncTable.createdAt to SortOrder.DESC)
            .map { it.toBackupRecord() }
    }

internal fun dbDeleteBackup(
    userId: UUID,
    id: UUID,
) {
    transaction {
        BackupSyncTable.deleteWhere {
            (BackupSyncTable.id eq id) and (BackupSyncTable.userId eq userId)
        }
    }
}

internal fun ResultRow.toContentRecord(): ContentRecord =
    ContentRecord(
        id = this[ContentSyncTable.id],
        type = this[ContentSyncTable.type],
        content = this[ContentSyncTable.content],
        mediaUri = this[ContentSyncTable.mediaUri],
        transcript = this[ContentSyncTable.transcript],
        durationMs = this[ContentSyncTable.durationMs],
        createdAt = this[ContentSyncTable.createdAt],
        lastUpdated = this[ContentSyncTable.lastUpdated],
        serverVersion = this[ContentSyncTable.serverVersion],
        deviceId = DeviceId(this[ContentSyncTable.deviceId]),
    )

internal fun ResultRow.toJournalRecord(): JournalRecord =
    JournalRecord(
        id = this[JournalSyncTable.id],
        title = this[JournalSyncTable.title],
        description = this[JournalSyncTable.description],
        createdAt = this[JournalSyncTable.createdAt],
        lastUpdated = this[JournalSyncTable.lastUpdated],
        serverVersion = this[JournalSyncTable.serverVersion],
        deviceId = DeviceId(this[JournalSyncTable.deviceId]),
    )

internal fun ResultRow.toAssociationRecord(): AssociationRecord =
    AssociationRecord(
        journalId = this[AssociationSyncTable.journalId],
        contentId = this[AssociationSyncTable.contentId],
        createdAt = this[AssociationSyncTable.createdAt],
        serverVersion = this[AssociationSyncTable.serverVersion],
        deviceId = DeviceId(this[AssociationSyncTable.deviceId]),
    )

internal fun ResultRow.toMediaRecord(): MediaRecord =
    MediaRecord(
        mediaId = this[MediaSyncTable.mediaId],
        contentId = this[MediaSyncTable.contentId],
        userId = this[MediaSyncTable.userId],
        fileName = this[MediaSyncTable.fileName],
        mimeType = this[MediaSyncTable.mimeType],
        sizeBytes = this[MediaSyncTable.sizeBytes],
        data = this[MediaSyncTable.data],
        storagePath = this[MediaSyncTable.storagePath],
        createdAt = this[MediaSyncTable.createdAt],
        serverVersion = this[MediaSyncTable.serverVersion],
        deviceId = DeviceId(this[MediaSyncTable.deviceId]),
        encryptionVersion = this[MediaSyncTable.encryptionVersion],
        encryptionKeyId = this[MediaSyncTable.encryptionKeyId],
        encryptionMode = this[MediaSyncTable.encryptionMode],
    )

internal fun ResultRow.toBackupRecord(): BackupRecord =
    BackupRecord(
        id = this[BackupSyncTable.id],
        userId = this[BackupSyncTable.userId],
        deviceId = this[BackupSyncTable.deviceId],
        manifest = this[BackupSyncTable.manifest],
        storagePath = this[BackupSyncTable.storagePath],
        createdAt = this[BackupSyncTable.createdAt],
        sizeBytes = this[BackupSyncTable.sizeBytes],
    )

internal fun nextVersion(existingVersion: Long?): Long {
    val candidate = currentTimestamp()
    return if (existingVersion != null && candidate <= existingVersion) existingVersion + 1 else candidate
}

internal fun currentTimestamp(): Long = System.currentTimeMillis()
