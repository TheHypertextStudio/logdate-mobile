package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.logdate.client.database.entities.PlaceEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(place: PlaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: PlaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(places: List<PlaceEntity>)

    @Update
    suspend fun update(place: PlaceEntity)

    @Query("SELECT * FROM places WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: Uuid): PlaceEntity?

    @Query("SELECT * FROM places WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: Uuid): Flow<PlaceEntity?>

    @Query("SELECT * FROM places WHERE deleted_at IS NULL ORDER BY last_updated DESC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE deleted_at IS NULL ORDER BY last_updated DESC")
    suspend fun getAll(): List<PlaceEntity>

    @Query("UPDATE places SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: Uuid,
        deletedAt: Long,
    )

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun hardDelete(id: Uuid)
}
