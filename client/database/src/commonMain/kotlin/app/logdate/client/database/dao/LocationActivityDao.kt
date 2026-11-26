package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.logdate.client.database.entities.LocationActivityEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface LocationActivityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: LocationActivityEntity)

    @Query("SELECT * FROM location_activity WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp")
    fun observeBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<LocationActivityEntity>>

    @Query(
        "SELECT * FROM location_activity WHERE user_id = :userId AND device_id = :deviceId " +
            "AND (recorded_at > :afterRecordedAt OR (recorded_at = :afterRecordedAt AND id > :afterId)) " +
            "ORDER BY recorded_at, id LIMIT :limit",
    )
    suspend fun getPage(
        userId: String,
        deviceId: String,
        afterRecordedAt: Instant,
        afterId: String,
        limit: Int,
    ): List<LocationActivityEntity>

    @Query(
        "SELECT * FROM location_activity WHERE user_id = :userId AND device_id = :deviceId " +
            "AND timestamp < :before ORDER BY timestamp DESC, id DESC LIMIT 1",
    )
    suspend fun latestBefore(
        userId: String,
        deviceId: String,
        before: Instant,
    ): LocationActivityEntity?

    @Query(
        "SELECT * FROM location_activity WHERE device_id = :deviceId " +
            "AND timestamp >= :since ORDER BY timestamp DESC, recorded_at DESC LIMIT 1",
    )
    suspend fun latestForDevice(
        deviceId: String,
        since: Instant,
    ): LocationActivityEntity?

    @Query("DELETE FROM location_activity WHERE timestamp >= :startTime AND timestamp < :endTime")
    suspend fun deleteBetween(
        startTime: Instant,
        endTime: Instant,
    )
}
