package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.logdate.client.database.entities.PlaceResolutionCacheEntity

@Dao
interface PlaceResolutionCacheDao {
    @Query("SELECT * FROM place_resolution_cache WHERE cell_x = :cellX AND cell_y = :cellY LIMIT 1")
    suspend fun get(
        cellX: Int,
        cellY: Int,
    ): PlaceResolutionCacheEntity?

    @Upsert
    suspend fun upsert(entry: PlaceResolutionCacheEntity)

    @Query(
        "DELETE FROM place_resolution_cache " +
            "WHERE cell_x BETWEEN :minX AND :maxX AND cell_y BETWEEN :minY AND :maxY",
    )
    suspend fun deleteNeighborhood(
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
    )

    @Query("DELETE FROM place_resolution_cache WHERE expires_at_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long)

    @Query("DELETE FROM place_resolution_cache")
    suspend fun clearAll()
}
