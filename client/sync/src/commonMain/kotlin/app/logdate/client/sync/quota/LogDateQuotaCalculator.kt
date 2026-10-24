package app.logdate.client.sync.quota

import app.logdate.client.database.dao.StorageMetadataDao
import app.logdate.client.database.entities.StorageContentType
import app.logdate.shared.model.CloudObjectType
import app.logdate.shared.model.CloudStorageCategoryUsage
import app.logdate.shared.model.CloudStorageQuota

/**
 * LogDate implementation of quota calculator.
 * Calculates usage by examining stored notes and media in the local database.
 */
class LogDateQuotaCalculator(
    private val storageMetadataDao: StorageMetadataDao,
) : QuotaCalculator {
    override suspend fun calculateTotalUsage(): CloudStorageQuota {
        val categories =
            CloudObjectType.entries.map { objectType ->
                calculateCategoryUsage(objectType)
            }

        val totalUsed = categories.sumOf { it.sizeBytes }
        val totalQuota = 100L * 1024L * 1024L * 1024L // 100 GB default

        return CloudStorageQuota(
            totalBytes = totalQuota,
            usedBytes = totalUsed,
            categories = categories,
        )
    }

    override suspend fun calculateCategoryUsage(objectType: CloudObjectType): CloudStorageCategoryUsage {
        val storageContentType = mapCloudObjectTypeToStorageContentType(objectType)

        return if (storageContentType != null) {
            // Get storage data directly from metadata table
            // Note: DAO methods automatically exclude items marked with excludeFromQuota = true
            val totalSize = storageMetadataDao.getTotalSizeByType(storageContentType)
            val objectCount = storageMetadataDao.getObjectCountByType(storageContentType)

            // Validate the total size to catch data corruption
            validateTotalSize(totalSize, objectType.name)

            CloudStorageCategoryUsage(
                category = objectType,
                sizeBytes = totalSize,
                objectCount = objectCount,
            )
        } else {
            // Category not tracked in storage metadata
            CloudStorageCategoryUsage(
                category = objectType,
                sizeBytes = 0L,
                objectCount = 0,
            )
        }
    }

    /**
     * Maps CloudObjectType to StorageContentType for database queries.
     */
    private fun mapCloudObjectTypeToStorageContentType(objectType: CloudObjectType): StorageContentType? =
        when (objectType) {
            CloudObjectType.TEXT_NOTES -> StorageContentType.TEXT_NOTE
            CloudObjectType.IMAGE_NOTES -> StorageContentType.IMAGE_NOTE
            CloudObjectType.VIDEO_NOTES -> StorageContentType.VIDEO_NOTE
            CloudObjectType.VOICE_NOTES -> StorageContentType.VOICE_NOTE
            CloudObjectType.JOURNAL_DATA -> StorageContentType.JOURNAL_METADATA
            CloudObjectType.USER_PROFILE -> StorageContentType.USER_PROFILE
            CloudObjectType.ATTACHMENTS -> StorageContentType.ATTACHMENT
        }

    /**
     * Validates that total storage size is reasonable.
     * Throws an exception if the size is invalid.
     */
    private fun validateTotalSize(
        totalSizeBytes: Long,
        categoryName: String,
    ) {
        when {
            totalSizeBytes < 0 -> throw IllegalStateException(
                "Invalid negative total size for $categoryName: $totalSizeBytes bytes",
            )

            totalSizeBytes > MAX_REASONABLE_TOTAL_SIZE -> throw IllegalStateException(
                "Unreasonably large total size for $categoryName: $totalSizeBytes bytes " +
                    "(>${MAX_REASONABLE_TOTAL_SIZE / BYTES_PER_TERABYTE}TB)",
            )
        }
    }

    companion object {
        // Validation limits to catch data corruption or unrealistic sizes
        private const val BYTES_PER_TERABYTE = 1024L * 1024L * 1024L * 1024L
        private const val MAX_REASONABLE_TOTAL_SIZE = 100L * BYTES_PER_TERABYTE // 100TB per category
    }
}
