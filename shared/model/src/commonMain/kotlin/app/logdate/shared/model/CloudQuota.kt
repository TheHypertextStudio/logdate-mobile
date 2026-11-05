package app.logdate.shared.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

/**
 * Represents the cloud storage quota usage for a LogDate account.
 */
@Serializable
data class CloudStorageQuota(
    val totalBytes: Long,
    val usedBytes: Long,
    val categories: List<CloudStorageCategoryUsage>,
) {
    /**
     * The percentage of quota used (0.0 to 1.0).
     */
    val usagePercentage: Float
        get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes.toFloat() else 0f

    /**
     * Remaining bytes available.
     */
    val availableBytes: Long
        get() = totalBytes - usedBytes
}

/**
 * Storage usage for a specific category of cloud objects.
 */
@Serializable
data class CloudStorageCategoryUsage(
    val category: CloudObjectType,
    val sizeBytes: Long,
    val objectCount: Int = 0,
)

/**
 * Types of objects stored in LogDate Cloud.
 */
@Serializable
enum class CloudObjectType {
    TEXT_NOTES,
    IMAGE_NOTES,
    VOICE_NOTES,
    VIDEO_NOTES,
    JOURNAL_DATA,
    USER_PROFILE,
    ATTACHMENTS,
}

/**
 * Interface for cloud quota management functionality.
 */
interface CloudQuotaManager {
    /**
     * Gets the current quota usage. Returns cached data if available, otherwise calculates.
     */
    suspend fun getCurrentQuota(): CloudStorageQuota

    /**
     * Observes quota changes in real-time.
     */
    fun observeQuota(): Flow<CloudStorageQuota>

    /**
     * Observes authoritative usage together with the account that produced it.
     * Legacy implementations cannot establish that scope and report it as unavailable.
     */
    fun observeQuotaSnapshots(): Flow<CloudQuotaSnapshot> = flowOf(CloudQuotaSnapshot.Unavailable(null))

    /**
     * Records that a new cloud object was created.
     * Updates the cached quota incrementally.
     */
    suspend fun recordObjectCreation(
        objectType: CloudObjectType,
        bytes: Long,
    )

    /**
     * Records that a cloud object was deleted.
     * Updates the cached quota incrementally.
     */
    suspend fun recordObjectDeletion(
        objectType: CloudObjectType,
        bytes: Long,
    )

    /**
     * Records that a cloud object was updated (size changed).
     * Updates the cached quota incrementally.
     */
    suspend fun recordObjectUpdate(
        objectType: CloudObjectType,
        oldBytes: Long,
        newBytes: Long,
    )

    /**
     * Forces a complete recalculation of quota usage.
     * Used for validation and initial sync.
     */
    suspend fun recalculateQuota(): CloudStorageQuota

    /**
     * Sets the total quota limit (typically set by billing/subscription).
     */
    suspend fun setQuotaLimit(totalBytes: Long)

    /**
     * Synchronizes quota data with the server.
     * Server data is considered the source of truth.
     */
    suspend fun syncWithServer(): CloudStorageQuota

    /**
     * Gets the last sync timestamp with the server.
     * Returns null if never synced.
     */
    suspend fun getLastServerSyncTime(): kotlin.time.Instant?
}
