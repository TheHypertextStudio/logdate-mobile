package app.logdate.server.database

import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.LogDateMediaRepository
import app.logdate.server.sync.StaleWriteException
import app.logdate.shared.model.sync.DeviceId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.random.Random

internal class PostgreSQLLogDateMediaRepository : LogDateMediaRepository {
    override fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
    ): LogDateMedia =
        transaction {
            val mediaId = media.mediaId.ifBlank { "media-${Random.nextLong().absoluteValue}" }
            val existing =
                LogDateMediaRecordsTable
                    .selectAll()
                    .where {
                        (LogDateMediaRecordsTable.userId eq userId) and
                            (LogDateMediaRecordsTable.mediaId eq mediaId)
                    }.forUpdate()
                    .singleOrNull()
            if (existing != null && existing[LogDateMediaRecordsTable.deleted]) {
                throw StaleWriteException(mediaId, existing[LogDateMediaRecordsTable.deletedAt] ?: 0L)
            }
            val version = nextLogDateMediaVersion(existing?.get(LogDateMediaRecordsTable.version))
            if (existing == null) {
                LogDateMediaRecordsTable.insert {
                    it[LogDateMediaRecordsTable.userId] = userId
                    it[LogDateMediaRecordsTable.mediaId] = mediaId
                    it[contentId] = media.contentId
                    it[fileName] = media.fileName
                    it[mimeType] = media.mimeType
                    it[sizeBytes] = media.sizeBytes
                    it[data] = media.data
                    it[storagePath] = media.storagePath
                    it[createdAt] = media.createdAt
                    it[LogDateMediaRecordsTable.version] = version
                    it[deviceId] = media.deviceId.value
                    it[deleted] = false
                    it[deletedAt] = null
                    it[encryptionVersion] = media.encryptionVersion
                    it[encryptionKeyId] = media.encryptionKeyId
                    it[encryptionMode] = media.encryptionMode
                }
            } else {
                LogDateMediaRecordsTable.update({
                    (LogDateMediaRecordsTable.userId eq userId) and
                        (LogDateMediaRecordsTable.mediaId eq mediaId)
                }) {
                    it[contentId] = media.contentId
                    it[fileName] = media.fileName
                    it[mimeType] = media.mimeType
                    it[sizeBytes] = media.sizeBytes
                    it[data] = media.data
                    it[storagePath] = media.storagePath
                    it[createdAt] = media.createdAt
                    it[LogDateMediaRecordsTable.version] = version
                    it[deviceId] = media.deviceId.value
                    it[deleted] = false
                    it[deletedAt] = null
                    it[encryptionVersion] = media.encryptionVersion
                    it[encryptionKeyId] = media.encryptionKeyId
                    it[encryptionMode] = media.encryptionMode
                }
            }
            media.copy(
                mediaId = mediaId,
                userId = userId,
                version = version,
            )
        }

    override fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia? =
        transaction {
            LogDateMediaRecordsTable
                .selectAll()
                .where {
                    (LogDateMediaRecordsTable.userId eq userId) and
                        (LogDateMediaRecordsTable.mediaId eq mediaId) and
                        (LogDateMediaRecordsTable.deleted eq false)
                }.forUpdate()
                .singleOrNull()
                ?.toLogDateMedia()
        }

    override fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
    ) {
        transaction {
            val existing =
                LogDateMediaRecordsTable
                    .selectAll()
                    .where {
                        (LogDateMediaRecordsTable.userId eq userId) and
                            (LogDateMediaRecordsTable.mediaId eq mediaId)
                    }.forUpdate()
                    .singleOrNull()
            if (existing == null) {
                LogDateMediaRecordsTable.insert {
                    it[LogDateMediaRecordsTable.userId] = userId
                    it[LogDateMediaRecordsTable.mediaId] = mediaId
                    it[contentId] = ""
                    it[fileName] = ""
                    it[mimeType] = "application/octet-stream"
                    it[sizeBytes] = 0
                    it[data] = byteArrayOf()
                    it[createdAt] = deletedAt
                    it[version] = nextLogDateMediaVersion(null)
                    it[deviceId] = DeviceId.UNKNOWN.value
                    it[deleted] = true
                    it[LogDateMediaRecordsTable.deletedAt] = deletedAt
                }
                return@transaction
            }
            LogDateMediaRecordsTable.update({
                (LogDateMediaRecordsTable.userId eq userId) and
                    (LogDateMediaRecordsTable.mediaId eq mediaId)
            }) {
                it[deleted] = true
                it[LogDateMediaRecordsTable.deletedAt] = maxOf(deletedAt, existing[LogDateMediaRecordsTable.deletedAt] ?: deletedAt)
                it[version] = nextLogDateMediaVersion(existing[LogDateMediaRecordsTable.version])
            }
        }
    }

    override fun listAllForUser(userId: UUID): List<LogDateMedia> =
        transaction {
            LogDateMediaRecordsTable
                .selectAll()
                .where { LogDateMediaRecordsTable.userId eq userId }
                .map { it.toLogDateMedia() }
        }

    private fun ResultRow.toLogDateMedia(): LogDateMedia =
        LogDateMedia(
            mediaId = this[LogDateMediaRecordsTable.mediaId],
            contentId = this[LogDateMediaRecordsTable.contentId],
            userId = this[LogDateMediaRecordsTable.userId],
            fileName = this[LogDateMediaRecordsTable.fileName],
            mimeType = this[LogDateMediaRecordsTable.mimeType],
            sizeBytes = this[LogDateMediaRecordsTable.sizeBytes],
            data = this[LogDateMediaRecordsTable.data],
            storagePath = this[LogDateMediaRecordsTable.storagePath],
            createdAt = this[LogDateMediaRecordsTable.createdAt],
            version = this[LogDateMediaRecordsTable.version],
            deviceId = DeviceId(this[LogDateMediaRecordsTable.deviceId]),
            encryptionVersion = this[LogDateMediaRecordsTable.encryptionVersion],
            encryptionKeyId = this[LogDateMediaRecordsTable.encryptionKeyId],
            encryptionMode = this[LogDateMediaRecordsTable.encryptionMode],
        )
}

private fun nextLogDateMediaVersion(existingVersion: Long?): Long {
    val candidate = System.currentTimeMillis()
    return when {
        existingVersion == null -> candidate
        candidate > existingVersion -> candidate
        else -> existingVersion + 1L
    }
}
