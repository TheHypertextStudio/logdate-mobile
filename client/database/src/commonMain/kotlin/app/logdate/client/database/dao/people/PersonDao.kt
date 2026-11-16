package app.logdate.client.database.dao.people

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.logdate.client.database.entities.people.PersonEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity)

    @Update
    suspend fun update(person: PersonEntity)

    @Query("SELECT * FROM people WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: Uuid): PersonEntity?

    @Query("SELECT * FROM people WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: Uuid): Flow<PersonEntity?>

    @Query("SELECT * FROM people WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE contact_lookup_key = :lookupKey AND deleted_at IS NULL LIMIT 1")
    suspend fun getByContactLookupKey(lookupKey: String): PersonEntity?

    /** The contact lookup keys of people who were removed from the directory. */
    @Query("SELECT contact_lookup_key FROM people WHERE deleted_at IS NOT NULL AND contact_lookup_key IS NOT NULL")
    suspend fun getRemovedContactLookupKeys(): List<String>

    @Query("SELECT * FROM people WHERE deleted_at IS NULL")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM people WHERE origin = :origin AND deleted_at IS NULL")
    fun observeCountByOrigin(origin: String): Flow<Int>

    @Query("UPDATE people SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: Uuid,
        deletedAt: Instant,
    )
}
