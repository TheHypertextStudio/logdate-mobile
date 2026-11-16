package app.logdate.client.database.dao.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import app.logdate.client.database.entities.sync.PendingUploadEntity

/**
 * Retry state of queued uploads, stored on the queued row. Every write names the operation it
 * belongs to, so state recorded for an operation that a newer edit already replaced is dropped.
 */
@Dao
interface PendingUploadRetryDao {
    @Query(
        "SELECT nextAttemptAt FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin " +
            "AND entityType = :entityType AND entityId = :entityId AND operationId = :operationId",
    )
    suspend fun nextAttemptAt(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Long?

    @Query(
        "UPDATE pending_uploads SET nextAttemptAt = :timestamp WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin " +
            "AND entityType = :entityType AND entityId = :entityId AND operationId = :operationId",
    )
    suspend fun setNextAttemptAt(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
        timestamp: Long,
    ): Int

    /** Forgets the operation's retry schedule and any attempt still recorded as started. */
    @Query(
        "UPDATE pending_uploads SET nextAttemptAt = NULL, unfinishedAttempts = 0 WHERE ownerId = :ownerId " +
            "AND serverOrigin = :serverOrigin AND entityType = :entityType AND entityId = :entityId " +
            "AND operationId = :operationId",
    )
    suspend fun clearRetry(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Int

    @Query(
        "SELECT unfinishedAttempts FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin " +
            "AND entityType = :entityType AND entityId = :entityId AND operationId = :operationId",
    )
    suspend fun unfinishedAttempts(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Int?

    @Query(
        "UPDATE pending_uploads SET unfinishedAttempts = unfinishedAttempts + 1 WHERE ownerId = :ownerId " +
            "AND serverOrigin = :serverOrigin AND entityType = :entityType AND entityId = :entityId " +
            "AND operationId = :operationId",
    )
    suspend fun incrementUnfinishedAttempts(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Int

    /**
     * Records that an upload attempt at the operation is starting, and returns how many earlier
     * attempts started and never finished. Returns 0 and records nothing when the operation is no
     * longer queued.
     */
    @Transaction
    suspend fun beginAttempt(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Int {
        val unfinished = unfinishedAttempts(ownerId, serverOrigin, entityType, entityId, operationId) ?: return 0
        incrementUnfinishedAttempts(ownerId, serverOrigin, entityType, entityId, operationId)
        return unfinished
    }

    /** Records that the attempt started by [beginAttempt] finished, whatever its outcome. */
    @Query(
        "UPDATE pending_uploads SET unfinishedAttempts = 0 WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin " +
            "AND entityType = :entityType AND entityId = :entityId AND operationId = :operationId",
    )
    suspend fun endAttempt(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Int

    /** Makes every scheduled retry in the scope due now, for example after connectivity returns. */
    @Query(
        "UPDATE pending_uploads SET nextAttemptAt = 0 WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin " +
            "AND nextAttemptAt IS NOT NULL",
    )
    suspend fun releaseScheduledRetries(
        ownerId: String,
        serverOrigin: String,
    ): Int

    /** Queued uploads of [entityType] that are not waiting for a later retry, oldest first. */
    @Query(
        "SELECT * FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin " +
            "AND entityType = :entityType AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun duePending(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        now: Long,
        limit: Int,
    ): List<PendingUploadEntity>
}
