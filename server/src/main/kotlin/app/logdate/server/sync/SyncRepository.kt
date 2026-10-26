package app.logdate.server.sync

import app.logdate.shared.model.sync.DeviceId
import java.util.UUID

/**
 * Transport-level storage records for sync. These are not core domain entities;
 * they carry sync-only metadata like serverVersion and deviceId.
 */
data class ContentRecord(
    val id: String,
    val type: String,
    val content: String?,
    val mediaUri: String?,
    val durationMs: Long?,
    val createdAt: Long,
    val lastUpdated: Long,
    val serverVersion: Long,
    val deviceId: DeviceId,
    val transcript: String? = null,
)

data class JournalRecord(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val lastUpdated: Long,
    val serverVersion: Long,
    val deviceId: DeviceId,
)

data class AssociationRecord(
    val journalId: String,
    val contentId: String,
    val createdAt: Long,
    val serverVersion: Long,
    val deviceId: DeviceId,
)

data class MediaRecord(
    val mediaId: String,
    val contentId: String,
    val userId: UUID,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val data: ByteArray,
    val storagePath: String? = null,
    val createdAt: Long,
    val serverVersion: Long,
    val deviceId: DeviceId,
    val encryptionVersion: Int? = null,
    val encryptionKeyId: String? = null,
    val encryptionMode: String? = null,
)

data class BackupRecord(
    val id: UUID,
    val userId: UUID,
    val deviceId: String,
    val manifest: String,
    val storagePath: String,
    val createdAt: Long,
    val sizeBytes: Long,
)

data class ChangeSet<T, D>(
    val changes: List<T>,
    val deletions: List<D>,
    val lastTimestamp: Long,
    val hasMore: Boolean = false,
)

/**
 * Repository interface for sync data operations.
 * All methods require a userId for multi-tenancy isolation.
 */
interface SyncRepository {
    fun status(userId: UUID): SyncStatus

    // Content
    fun upsertContent(
        userId: UUID,
        record: ContentRecord,
    ): ContentRecord

    fun getContent(
        userId: UUID,
        id: String,
    ): ContentRecord?

    fun deleteContent(
        userId: UUID,
        id: String,
        deletedAt: Long,
    )

    fun contentChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): ChangeSet<ContentRecord, ContentDeletionMarker>

    // Journals
    fun upsertJournal(
        userId: UUID,
        record: JournalRecord,
    ): JournalRecord

    fun getJournal(
        userId: UUID,
        id: String,
    ): JournalRecord?

    fun deleteJournal(
        userId: UUID,
        id: String,
        deletedAt: Long,
    )

    fun journalChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): ChangeSet<JournalRecord, JournalDeletionMarker>

    // Associations
    fun upsertAssociations(
        userId: UUID,
        records: List<AssociationRecord>,
    )

    fun deleteAssociations(
        userId: UUID,
        keys: List<AssociationKey>,
        deletedAt: Long,
    )

    fun associationChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): ChangeSet<AssociationRecord, AssociationDeletionMarker>

    // Media
    fun upsertMedia(
        userId: UUID,
        record: MediaRecord,
    ): MediaRecord

    fun getMedia(
        userId: UUID,
        mediaId: String,
    ): MediaRecord?

    fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
    )

    /**
     * Returns every media record owned by [userId], including soft-deleted ones, so
     * account-deletion can purge blobs the user had soft-deleted but whose bytes are still live.
     */
    fun listAllMediaForUser(userId: UUID): List<MediaRecord>

    // Backups
    fun createBackupRecord(
        userId: UUID,
        record: BackupRecord,
    ): BackupRecord

    fun getBackupRecord(
        userId: UUID,
        id: UUID,
    ): BackupRecord?

    fun listBackups(userId: UUID): List<BackupRecord>

    fun deleteBackup(
        userId: UUID,
        id: UUID,
    )

    // Maintenance
    fun purgeTombstones(
        userId: UUID,
        olderThan: Long,
    ): SyncPurgeResult

    fun purgeTombstonesOlderThan(olderThan: Long): SyncPurgeResult
}

data class SyncStatus(
    val contentCount: Int,
    val journalCount: Int,
    val associationCount: Int,
    val lastTimestamp: Long,
)

data class ContentDeletionMarker(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

data class JournalDeletionMarker(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

data class AssociationKey(
    val journalId: String,
    val contentId: String,
)

data class AssociationDeletionMarker(
    val key: AssociationKey,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

/** Counts payload records scrubbed; deletion identities and feed markers remain available. */
data class SyncPurgeResult(
    val contentPurged: Int,
    val journalPurged: Int,
    val associationPurged: Int,
    val mediaPurged: Int,
    val cutoff: Long,
)
