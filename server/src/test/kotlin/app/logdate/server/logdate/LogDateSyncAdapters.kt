package app.logdate.server.logdate

import app.logdate.server.sync.BackupRecord
import app.logdate.server.sync.MediaRecord
import app.logdate.server.sync.SyncRepository

fun SyncRepository.asLogDateCollectionsRepository(): LogDateCollectionsRepository = SyncBackedLogDateCollectionsRepository(this)

/**
 * Transitional compatibility adapter for tests and call sites that still seed media metadata
 * through the legacy sync repository shape.
 */
fun SyncRepository.asLogDateMediaRepository(): LogDateMediaRepository = SyncBackedLogDateMediaRepository(this)

/**
 * Transitional compatibility adapter for tests and call sites that still seed backup metadata
 * through the legacy sync repository shape.
 */
fun SyncRepository.asLogDateBackupRepository(): LogDateBackupRepository = SyncBackedLogDateBackupRepository(this)

internal class SyncBackedLogDateMediaRepository(
    private val syncRepository: SyncRepository,
) : LogDateMediaRepository {
    override fun upsertMedia(
        userId: java.util.UUID,
        media: LogDateMedia,
    ): LogDateMedia = syncRepository.upsertMedia(userId, media.toSyncRecord()).toLogDateMedia()

    override fun getMedia(
        userId: java.util.UUID,
        mediaId: String,
    ): LogDateMedia? = syncRepository.getMedia(userId, mediaId)?.toLogDateMedia()

    override fun deleteMedia(
        userId: java.util.UUID,
        mediaId: String,
        deletedAt: Long,
    ) {
        syncRepository.deleteMedia(userId, mediaId, deletedAt)
    }

    override fun listAllForUser(userId: java.util.UUID): List<LogDateMedia> =
        syncRepository.listAllMediaForUser(userId).map(MediaRecord::toLogDateMedia)
}

internal class SyncBackedLogDateBackupRepository(
    private val syncRepository: SyncRepository,
) : LogDateBackupRepository {
    override fun createBackup(
        userId: java.util.UUID,
        backup: LogDateBackup,
    ): LogDateBackup = syncRepository.createBackupRecord(userId, backup.toSyncRecord()).toLogDateBackup()

    override fun getBackup(
        userId: java.util.UUID,
        id: java.util.UUID,
    ): LogDateBackup? = syncRepository.getBackupRecord(userId, id)?.toLogDateBackup()

    override fun listBackups(userId: java.util.UUID): List<LogDateBackup> =
        syncRepository.listBackups(userId).map(BackupRecord::toLogDateBackup)

    override fun deleteBackup(
        userId: java.util.UUID,
        id: java.util.UUID,
    ) {
        syncRepository.deleteBackup(userId, id)
    }
}

private fun LogDateMedia.toSyncRecord(): MediaRecord =
    MediaRecord(
        mediaId = mediaId,
        contentId = contentId,
        userId = userId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        data = data,
        storagePath = storagePath,
        createdAt = createdAt,
        serverVersion = version,
        deviceId = deviceId,
        encryptionVersion = encryptionVersion,
        encryptionKeyId = encryptionKeyId,
        encryptionMode = encryptionMode,
    )

private fun MediaRecord.toLogDateMedia(): LogDateMedia =
    LogDateMedia(
        mediaId = mediaId,
        contentId = contentId,
        userId = userId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        data = data,
        storagePath = storagePath,
        createdAt = createdAt,
        version = serverVersion,
        deviceId = deviceId,
        encryptionVersion = encryptionVersion,
        encryptionKeyId = encryptionKeyId,
        encryptionMode = encryptionMode,
    )

private fun LogDateBackup.toSyncRecord(): BackupRecord =
    BackupRecord(
        id = id,
        userId = userId,
        deviceId = deviceId,
        manifest = manifest,
        storagePath = storagePath,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
    )

private fun BackupRecord.toLogDateBackup(): LogDateBackup =
    LogDateBackup(
        id = id,
        userId = userId,
        deviceId = deviceId,
        manifest = manifest,
        storagePath = storagePath,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
    )
