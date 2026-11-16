package app.logdate.client.database.dao.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.logdate.client.database.entities.sync.DownloadCheckpointEntity
import app.logdate.client.database.entities.sync.DownloadInboxEntity

@Dao
interface DownloadInboxDao {
    @Query("SELECT * FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND entityType = :type AND entityId = :id")
    suspend fun get(
        owner: String,
        origin: String,
        type: String,
        id: String,
    ): DownloadInboxEntity?

    @Query(
        "SELECT * FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND entityType = :type AND state IN ('WAITING', 'FAILED') AND nextAttemptAt <= :now ORDER BY nextAttemptAt, version LIMIT 100",
    )
    suspend fun pending(
        owner: String,
        origin: String,
        type: String,
        now: Long,
    ): List<DownloadInboxEntity>

    /**
     * Rows this version can still finish. A row whose reason is `UNSUPPORTED_FORMAT` (the name of
     * `DiagnosticReason.UNSUPPORTED_FORMAT`) contains a record a newer version of LogDate wrote: it
     * stays in the inbox and [pending] offers it again, so an app update that reads it applies it,
     * but nothing this version does finishes it.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin " +
            "AND state != 'APPLIED' AND reason != 'UNSUPPORTED_FORMAT'",
    )
    suspend fun count(
        owner: String,
        origin: String,
    ): Int

    @Query(
        "SELECT COUNT(*) AS retained, " +
            "COALESCE(SUM(CASE WHEN state IN ('WAITING', 'FAILED') AND reason != 'UNSUPPORTED_FORMAT' " +
            "AND (reason != 'NETWORK_POLICY' OR (:includePolicyDeferred AND nextAttemptAt <= :now)) " +
            "THEN 1 ELSE 0 END), 0) AS retryable, " +
            "COALESCE(SUM(CASE WHEN state = 'PARKED' OR (state = 'FAILED' " +
            "AND reason NOT IN ('NETWORK_POLICY', 'UNSUPPORTED_FORMAT')) THEN 1 ELSE 0 END), 0) AS failed " +
            "FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND state != 'APPLIED'",
    )
    suspend fun recoveryCounts(
        owner: String,
        origin: String,
        now: Long,
        includePolicyDeferred: Boolean = false,
    ): DownloadRecoveryCounts

    /** Every row of [type], applied or not. */
    @Query("SELECT * FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND entityType = :type")
    suspend fun all(
        owner: String,
        origin: String,
        type: String,
    ): List<DownloadInboxEntity>

    @Query(
        "SELECT COUNT(*) FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin " +
            "AND (state = 'PARKED' OR (state = 'FAILED' AND reason != 'UNSUPPORTED_FORMAT'))",
    )
    suspend fun countNeedingAttention(
        owner: String,
        origin: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND state IN ('WAITING', 'FAILED') AND nextAttemptAt <= :now",
    )
    suspend fun countDue(
        owner: String,
        origin: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: DownloadInboxEntity)

    @Query(
        "DELETE FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND entityType = :type AND entityId = :id",
    )
    suspend fun deleteReceipt(
        owner: String,
        origin: String,
        type: String,
        id: String,
    )

    /**
     * Deletes applied rows of the scope that settled before [appliedBefore], and returns how many
     * it deleted. Deletion receipts are kept unless [includeDeletions] is set, because they are
     * what stops an older copy of a deleted record from being applied again.
     */
    @Query(
        "DELETE FROM sync_download_inbox WHERE ownerId = :owner AND serverOrigin = :origin AND state = 'APPLIED' " +
            "AND appliedAt < :appliedBefore AND (:includeDeletions OR deleted = 0)",
    )
    suspend fun pruneApplied(
        owner: String,
        origin: String,
        appliedBefore: Long,
        includeDeletions: Boolean = false,
    ): Int

    @Query("SELECT * FROM sync_download_checkpoints WHERE ownerId = :owner AND serverOrigin = :origin AND entityType = :type")
    suspend fun checkpoint(
        owner: String,
        origin: String,
        type: String,
    ): DownloadCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun checkpoint(row: DownloadCheckpointEntity)

    @Query(
        "UPDATE sync_download_inbox SET nextAttemptAt = 0, " +
            "state = 'WAITING', " +
            "attempts = CASE WHEN :afterRecovery THEN 0 ELSE attempts END " +
            "WHERE ownerId = :owner AND serverOrigin = :origin " +
            "AND (state IN ('WAITING', 'FAILED') OR (:afterRecovery AND state = 'PARKED')) AND reason IN (:reasons)",
    )
    suspend fun release(
        owner: String,
        origin: String,
        reasons: List<String>,
        afterRecovery: Boolean = false,
    )
}
