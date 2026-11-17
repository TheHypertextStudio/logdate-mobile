package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.logdate.client.database.entities.StorageContentType
import app.logdate.client.database.entities.StorageMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface StorageMetadataDao {
    /**
     * Gets storage metadata for a specific content object.
     */
    @Query("SELECT * FROM storage_metadata WHERE contentId = :contentId")
    suspend fun getStorageMetadata(contentId: Uuid): StorageMetadataEntity?

    /**
     * Observes storage metadata for a specific content object.
     */
    @Query("SELECT * FROM storage_metadata WHERE contentId = :contentId")
    fun observeStorageMetadata(contentId: Uuid): Flow<StorageMetadataEntity?>

    /**
     * Gets all storage metadata for a specific content type.
     */
    @Query("SELECT * FROM storage_metadata WHERE contentType = :contentType")
    suspend fun getStorageMetadataByType(contentType: StorageContentType): List<StorageMetadataEntity>

    /**
     * Gets total size for a specific content type, excluding items marked to exclude from quota.
     */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM storage_metadata WHERE contentType = :contentType AND excludeFromQuota = 0")
    suspend fun getTotalSizeByType(contentType: StorageContentType): Long

    /**
     * Gets count of objects for a specific content type, excluding items marked to exclude from quota.
     */
    @Query("SELECT COUNT(*) FROM storage_metadata WHERE contentType = :contentType AND excludeFromQuota = 0")
    suspend fun getObjectCountByType(contentType: StorageContentType): Int

    /**
     * Gets all storage metadata grouped by content type for quota calculation, excluding items marked to exclude from quota.
     */
    @Query(
        """
        SELECT contentType, 
               COALESCE(SUM(sizeBytes), 0) as totalSize, 
               COUNT(*) as objectCount
        FROM storage_metadata 
        WHERE excludeFromQuota = 0
        GROUP BY contentType
    """,
    )
    suspend fun getStorageSummaryByType(): List<StorageSummary>

    /**
     * Gets total storage usage across all content types, excluding items marked to exclude from quota.
     */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM storage_metadata WHERE excludeFromQuota = 0")
    suspend fun getTotalStorageUsage(): Long

    /**
     * Inserts or updates storage metadata for a content object.
     */
    @Upsert
    suspend fun upsertStorageMetadata(metadata: StorageMetadataEntity)

    /**
     * Inserts storage metadata, ignoring if it already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStorageMetadata(metadata: StorageMetadataEntity)

    /**
     * Removes storage metadata for a specific content object.
     */
    @Query("DELETE FROM storage_metadata WHERE contentId = :contentId")
    suspend fun removeStorageMetadata(contentId: Uuid)

    /**
     * Removes storage metadata for multiple content objects.
     */
    @Query("DELETE FROM storage_metadata WHERE contentId IN (:contentIds)")
    suspend fun removeStorageMetadata(contentIds: List<Uuid>)

    /**
     * Updates the size for a specific content object.
     */
    @Query("UPDATE storage_metadata SET sizeBytes = :newSizeBytes, lastUpdated = :timestamp WHERE contentId = :contentId")
    suspend fun updateStorageSize(
        contentId: Uuid,
        newSizeBytes: Long,
        timestamp: Long,
    )

    /**
     * Updates the quota exclusion status for a specific content object.
     */
    @Query("UPDATE storage_metadata SET excludeFromQuota = :exclude, lastUpdated = :timestamp WHERE contentId = :contentId")
    suspend fun updateQuotaExclusion(
        contentId: Uuid,
        exclude: Boolean,
        timestamp: Long,
    )

    /**
     * Gets all storage metadata items that are excluded from quota tracking.
     */
    @Query("SELECT * FROM storage_metadata WHERE excludeFromQuota = 1")
    suspend fun getExcludedFromQuota(): List<StorageMetadataEntity>
}

/**
 * Result class for storage summary queries.
 */
data class StorageSummary(
    val contentType: StorageContentType,
    val totalSize: Long,
    val objectCount: Int,
)
