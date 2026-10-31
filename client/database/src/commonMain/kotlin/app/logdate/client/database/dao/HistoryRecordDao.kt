package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.logdate.client.database.entities.HistoryCursorEntity
import app.logdate.client.database.entities.HistoryRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HistoryRecordDao {
    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin")
    abstract fun observe(
        owner: String,
        origin: String,
    ): Flow<List<HistoryRecordEntity>>

    @Query(
        "SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin " +
            "AND (observedAt IS NULL OR (observedAt >= :startMillis AND observedAt < :endMillis))",
    )
    abstract fun observeRange(
        owner: String,
        origin: String,
        startMillis: Long,
        endMillis: Long,
    ): Flow<List<HistoryRecordEntity>>

    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin")
    abstract suspend fun records(
        owner: String,
        origin: String,
    ): List<HistoryRecordEntity>

    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND id = :id")
    abstract suspend fun record(
        owner: String,
        origin: String,
        id: String,
    ): HistoryRecordEntity?

    @Query(
        "SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND deviceId = :device " +
            "AND recordType = 'observation' AND deleted = 0 " +
            "AND (observedAt < :before OR (observedAt = :before AND id < :beforeId)) " +
            "ORDER BY observedAt DESC, id DESC LIMIT :limit",
    )
    abstract suspend fun observationsBefore(
        owner: String,
        origin: String,
        device: String,
        before: Long,
        beforeId: String,
        limit: Int,
    ): List<HistoryRecordEntity>

    @Query(
        "SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND deviceId = :device " +
            "AND recordType = 'activity' AND deleted = 0 AND observedAt >= :start AND observedAt < :end",
    )
    abstract suspend fun activitiesBetween(
        owner: String,
        origin: String,
        device: String,
        start: Long,
        end: Long,
    ): List<HistoryRecordEntity>

    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND recordType = :recordType")
    abstract suspend fun recordsOfType(
        owner: String,
        origin: String,
        recordType: String,
    ): List<HistoryRecordEntity>

    @Query(
        "SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND recordType = :recordType " +
            "AND deviceId = :device AND observedAt >= :start AND observedAt < :end",
    )
    abstract suspend fun recordsBetween(
        owner: String,
        origin: String,
        recordType: String,
        device: String,
        start: Long,
        end: Long,
    ): List<HistoryRecordEntity>

    @Upsert
    abstract suspend fun upsert(record: HistoryRecordEntity)

    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND id IN (:ids)")
    protected abstract suspend fun recordsWithIds(
        owner: String,
        origin: String,
        ids: List<String>,
    ): List<HistoryRecordEntity>

    /** Every row is written whole, so replacing a conflicting row is the same as updating it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun writeAll(records: List<HistoryRecordEntity>)

    @Transaction
    open suspend fun putPreservingTombstone(incoming: HistoryRecordEntity) {
        putBatchPreservingTombstones(listOf(incoming))
    }

    @Transaction
    open suspend fun putBatchPreservingTombstones(records: List<HistoryRecordEntity>) {
        records.groupBy { it.ownerId to it.origin }.forEach { (scope, scoped) ->
            val current = recordsById(scope.first, scope.second, scoped)
            val written = LinkedHashMap<String, HistoryRecordEntity>()
            val purged = mutableListOf<HistoryRecordEntity>()
            scoped.forEach { incoming ->
                val existing = current[incoming.id]
                if (existing?.deleted == true && !incoming.deleted) return@forEach
                val stored = incoming.keepingTimeOf(existing)
                current[incoming.id] = stored
                written[incoming.id] = stored
                if (incoming.isDeletedObservation()) purged += incoming
            }
            writeAll(written.values.toList())
            purgeDeletedObservations(purged)
        }
    }

    @Query(
        "DELETE FROM location_logs WHERE device_id = :deviceId AND (user_id = :owner OR user_id = 'default_user') " +
            "AND sample_id IN (:sampleIds)",
    )
    protected abstract suspend fun deleteRawObservations(
        owner: String,
        deviceId: String,
        sampleIds: List<String>,
    )

    private suspend fun recordsById(
        owner: String,
        origin: String,
        records: List<HistoryRecordEntity>,
    ): MutableMap<String, HistoryRecordEntity> =
        records
            .map { it.id }
            .distinct()
            .chunked(MAX_BOUND_IDS)
            .flatMap { recordsWithIds(owner, origin, it) }
            .associateByTo(HashMap()) { it.id }

    private suspend fun purgeDeletedObservations(records: List<HistoryRecordEntity>) {
        records.groupBy { it.ownerId to it.deviceId }.forEach { (source, observations) ->
            observations
                .map { it.id.removePrefix("observation:") }
                .distinct()
                .chunked(MAX_BOUND_IDS)
                .forEach { deleteRawObservations(source.first, source.second, it) }
        }
    }

    private fun HistoryRecordEntity.isDeletedObservation() = deleted && recordType == "observation" && id.startsWith("observation:")

    /** A deletion removes the payload, so it keeps the time the record had to stay findable by its hour. */
    private fun HistoryRecordEntity.keepingTimeOf(existing: HistoryRecordEntity?) =
        if (deleted && observedAt == null) copy(observedAt = existing?.observedAt) else this

    @Upsert
    abstract suspend fun upsertCursor(cursor: HistoryCursorEntity)

    @Query("SELECT cursor FROM history_cursors WHERE ownerId = :owner AND origin = :origin")
    abstract suspend fun cursor(
        owner: String,
        origin: String,
    ): Long?

    @Query("SELECT * FROM history_records WHERE ownerId = :owner AND origin = :origin AND dirty = 1 ORDER BY id LIMIT :limit")
    abstract suspend fun pending(
        owner: String,
        origin: String,
        limit: Int,
    ): List<HistoryRecordEntity>

    @Query(
        "UPDATE history_records SET dirty = " +
            "CASE WHEN deviceVersion = :version AND :serverVersion >= serverVersion THEN 0 ELSE dirty END, " +
            "serverVersion = MAX(serverVersion, :serverVersion) " +
            "WHERE ownerId = :owner AND origin = :origin AND id = :id",
    )
    abstract suspend fun acknowledge(
        owner: String,
        origin: String,
        id: String,
        version: Long,
        serverVersion: Long,
    )

    @Transaction
    open suspend fun applyPage(
        owner: String,
        origin: String,
        records: List<HistoryRecordEntity>,
        cursor: Long,
    ) {
        val current = recordsById(owner, origin, records)
        val written = LinkedHashMap<String, HistoryRecordEntity>()
        val purged = mutableListOf<HistoryRecordEntity>()
        records.forEach { incoming ->
            val local = current[incoming.id]
            if (incoming.serverVersion >= (local?.serverVersion ?: 0)) {
                val stored =
                    when {
                        local?.deleted == true -> local.copy(serverVersion = incoming.serverVersion, dirty = !incoming.deleted)
                        incoming.deleted || local?.dirty != true -> incoming.keepingTimeOf(local)
                        else -> local.copy(serverVersion = incoming.serverVersion)
                    }
                current[incoming.id] = stored
                written[incoming.id] = stored
            }
            current[incoming.id]?.takeIf { it.isDeletedObservation() }?.let { purged += it }
        }
        writeAll(written.values.toList())
        purgeDeletedObservations(purged)
        upsertCursor(HistoryCursorEntity(owner, origin, maxOf(cursor(owner, origin) ?: 0, cursor)))
    }

    private companion object {
        /** Stays well under SQLite's bound-variable limit on every platform driver. */
        const val MAX_BOUND_IDS = 500
    }
}
