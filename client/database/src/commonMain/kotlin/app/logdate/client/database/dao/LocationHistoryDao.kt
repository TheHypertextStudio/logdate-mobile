package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.logdate.client.database.entities.LocationLogEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface LocationHistoryDao {
    @Query("SELECT * FROM location_logs WHERE sample_id = :sampleId LIMIT 1")
    suspend fun getBySampleId(sampleId: String): LocationLogEntity?

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLocation(): LocationLogEntity?

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC LIMIT 1")
    fun observeLastLocation(): Flow<LocationLogEntity?>

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC")
    suspend fun getAllLocationHistory(): List<LocationLogEntity>

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC")
    fun observeAllLocationHistory(): Flow<List<LocationLogEntity>>

    @Query("SELECT * FROM location_logs WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp DESC")
    fun observeLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<LocationLogEntity>>

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLocationHistory(limit: Int): List<LocationLogEntity>

    @Query("SELECT * FROM location_logs WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): List<LocationLogEntity>

    @Query(
        "SELECT * FROM location_logs WHERE user_id = :userId AND device_id = :deviceId " +
            "AND (logged_at > :afterLoggedAt OR (logged_at = :afterLoggedAt AND sample_id > :afterSampleId)) " +
            "ORDER BY logged_at, sample_id LIMIT :limit",
    )
    suspend fun getPage(
        userId: String,
        deviceId: String,
        afterLoggedAt: Instant,
        afterSampleId: String,
        limit: Int,
    ): List<LocationLogEntity>

    @Query(
        "SELECT * FROM location_logs WHERE user_id = :userId AND device_id = :deviceId " +
            "AND (timestamp < :before OR (timestamp = :before AND sample_id < :beforeSampleId)) " +
            "ORDER BY timestamp DESC, sample_id DESC LIMIT :limit",
    )
    suspend fun before(
        userId: String,
        deviceId: String,
        before: Instant,
        beforeSampleId: String,
        limit: Int,
    ): List<LocationLogEntity>

    @Insert
    suspend fun addLocationLog(locationLog: LocationLogEntity)

    @Query("DELETE FROM location_logs WHERE user_id = :userId AND device_id = :deviceId AND timestamp = :timestamp")
    suspend fun deleteLocationById(
        userId: String,
        deviceId: String,
        timestamp: Instant,
    )

    @Query("DELETE FROM location_logs WHERE timestamp >= :start AND timestamp < :end")
    suspend fun deleteLogsWithinRange(
        start: Instant,
        end: Instant,
    )

    @Query("SELECT COUNT(*) FROM location_logs")
    suspend fun getLocationCount(): Int
}
