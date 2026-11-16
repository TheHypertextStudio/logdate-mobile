package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.logdate.client.database.entities.HistoryRecordEntity

@Dao
abstract class HistoryOwnerAdoptionDao {
    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin ORDER BY id LIMIT 100")
    abstract suspend fun historyPage(
        owner: String,
        origin: String,
    ): List<HistoryRecordEntity>

    @Query("SELECT COUNT(*) FROM history_records WHERE ownerId = :owner AND origin = :origin AND id = :id")
    abstract suspend fun historyIdCount(
        owner: String,
        origin: String,
        id: String,
    ): Int

    @Insert
    abstract suspend fun insertHistory(record: HistoryRecordEntity)

    @Query("DELETE FROM history_records WHERE ownerId = :owner AND origin = :origin AND id = :id")
    abstract suspend fun removeHistory(
        owner: String,
        origin: String,
        id: String,
    )

    @Query("UPDATE location_logs SET user_id = :newOwner WHERE user_id = :oldOwner AND device_id = :device")
    abstract suspend fun moveLocations(
        oldOwner: String,
        newOwner: String,
        device: String,
    )

    @Query("UPDATE location_activity SET user_id = :newOwner WHERE user_id = :oldOwner AND device_id = :device")
    abstract suspend fun moveActivities(
        oldOwner: String,
        newOwner: String,
        device: String,
    )

    @Query("DELETE FROM history_cursors WHERE ownerId = :owner AND origin = :origin")
    abstract suspend fun removeCursor(
        owner: String,
        origin: String,
    )

    @Query(
        "SELECT (SELECT COUNT(*) FROM history_records WHERE ownerId = :owner AND origin = :origin) + " +
            "(SELECT COUNT(*) FROM location_logs WHERE user_id IN (:owner, 'default_user') AND device_id = :device) + " +
            "(SELECT COUNT(*) FROM location_activity WHERE user_id IN (:owner, 'default_user') AND device_id = :device)",
    )
    abstract suspend fun localHistoryCount(
        owner: String,
        origin: String,
        device: String,
    ): Int

    @Query("UPDATE journal_merges SET ownerId = :newOwner WHERE ownerId = :oldOwner AND serverOrigin = :origin")
    abstract suspend fun moveJournalMerges(
        oldOwner: String,
        newOwner: String,
        origin: String,
    )

    @Query(
        "UPDATE pending_uploads SET ownerId = :newOwner WHERE ownerId = :oldOwner AND serverOrigin = " +
            ":origin AND entityType = 'JOURNAL_MERGE'",
    )
    abstract suspend fun moveJournalMergeUploads(
        oldOwner: String,
        newOwner: String,
        origin: String,
    )

    @Query(
        "SELECT COUNT(*) FROM sync_download_inbox WHERE ownerId = :oldOwner AND serverOrigin = :origin " +
            "AND entityType IN ('LOCAL_DELETE_NOTE', 'LOCAL_DELETE_JOURNAL', 'LOCAL_DELETE_ASSOCIATION', 'LOCAL_DELETE_TRANSCRIPTION') " +
            "AND state = 'APPLIED' AND (deleted != 1 OR operationId != 'local-delete-v1' " +
            "OR version != 0 OR payload != '' OR attempts != 0 OR nextAttemptAt != 0 OR reason != 'NONE')",
    )
    abstract suspend fun malformedLocalDeletions(
        oldOwner: String,
        origin: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM sync_download_inbox source JOIN sync_download_inbox target " +
            "ON target.ownerId = :newOwner AND target.serverOrigin = source.serverOrigin " +
            "AND target.entityType = source.entityType AND target.entityId = source.entityId " +
            "WHERE source.ownerId = :oldOwner AND source.serverOrigin = :origin " +
            "AND source.entityType IN ('LOCAL_DELETE_NOTE', 'LOCAL_DELETE_JOURNAL', " +
            "'LOCAL_DELETE_ASSOCIATION', 'LOCAL_DELETE_TRANSCRIPTION') " +
            "AND source.state = 'APPLIED' AND source.deleted = 1 AND source.operationId = 'local-delete-v1' " +
            "AND source.version = 0 AND source.payload = '' AND source.attempts = 0 AND " +
            "source.nextAttemptAt = 0 AND source.reason = 'NONE' " +
            "AND (target.state != 'APPLIED' OR target.deleted != 1 OR target.operationId != 'local-delete-v1' " +
            "OR target.version != 0 OR target.payload != '' OR target.attempts != 0 OR target.nextAttemptAt " +
            "!= 0 OR target.reason != 'NONE')",
    )
    abstract suspend fun conflictingLocalDeletions(
        oldOwner: String,
        newOwner: String,
        origin: String,
    ): Int

    @Query(
        "INSERT OR IGNORE INTO sync_download_inbox " +
            "(ownerId, serverOrigin, entityType, entityId, version, deleted, payload, operationId, state, " +
            "attempts, nextAttemptAt, reason, appliedAt) " +
            "SELECT :newOwner, serverOrigin, entityType, entityId, version, deleted, payload, operationId, " +
            "state, attempts, nextAttemptAt, reason, appliedAt " +
            "FROM sync_download_inbox WHERE ownerId = :oldOwner AND serverOrigin = :origin " +
            "AND entityType IN ('LOCAL_DELETE_NOTE', 'LOCAL_DELETE_JOURNAL', 'LOCAL_DELETE_ASSOCIATION', 'LOCAL_DELETE_TRANSCRIPTION') " +
            "AND state = 'APPLIED' AND deleted = 1 AND operationId = 'local-delete-v1' " +
            "AND version = 0 AND payload = '' AND attempts = 0 AND nextAttemptAt = 0 AND reason = 'NONE'",
    )
    abstract suspend fun copyLocalDeletions(
        oldOwner: String,
        newOwner: String,
        origin: String,
    )

    @Query(
        "DELETE FROM sync_download_inbox WHERE ownerId = :oldOwner AND serverOrigin = :origin " +
            "AND entityType IN ('LOCAL_DELETE_NOTE', 'LOCAL_DELETE_JOURNAL', 'LOCAL_DELETE_ASSOCIATION', 'LOCAL_DELETE_TRANSCRIPTION') " +
            "AND state = 'APPLIED' AND deleted = 1 AND operationId = 'local-delete-v1' " +
            "AND version = 0 AND payload = '' AND attempts = 0 AND nextAttemptAt = 0 AND reason = 'NONE'",
    )
    abstract suspend fun removeLocalDeletions(
        oldOwner: String,
        origin: String,
    )

    @Transaction
    open suspend fun adopt(
        oldOwner: String,
        newOwner: String,
        origin: String,
        device: String,
        transform: (HistoryRecordEntity) -> HistoryRecordEntity,
    ) {
        if (oldOwner == newOwner) return
        check(malformedLocalDeletions(oldOwner, origin) == 0) { "History owner adoption has unrecognized deletion custody" }
        check(conflictingLocalDeletions(oldOwner, newOwner, origin) == 0) { "History owner adoption has conflicting deletion custody" }
        copyLocalDeletions(oldOwner, newOwner, origin)
        removeLocalDeletions(oldOwner, origin)
        while (true) {
            val page = historyPage(oldOwner, origin)
            if (page.isEmpty()) break
            page.forEach { source ->
                check(historyIdCount(newOwner, origin, source.id) == 0) { "History owner adoption has a conflicting record" }
                val rewritten = transform(source)
                check(rewritten.id == source.id && rewritten.recordType == source.recordType && rewritten.deleted == source.deleted)
                insertHistory(rewritten.copy(ownerId = newOwner, origin = origin, serverVersion = 0, dirty = true))
                removeHistory(oldOwner, origin, source.id)
            }
        }
        moveJournalMerges(oldOwner, newOwner, origin)
        moveJournalMergeUploads(oldOwner, newOwner, origin)
        moveLocations(oldOwner, newOwner, device)
        moveActivities(oldOwner, newOwner, device)
        removeCursor(oldOwner, origin)
    }
}
