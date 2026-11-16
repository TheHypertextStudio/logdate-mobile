package app.logdate.client.database.dao.people

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.logdate.client.database.entities.people.PersonLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface PersonLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<PersonLinkEntity>)

    @Update
    suspend fun update(link: PersonLinkEntity)

    @Query("SELECT * FROM person_links WHERE person_id = :personId AND status = :status ORDER BY last_updated DESC")
    fun observeForPerson(
        personId: Uuid,
        status: String = "ACTIVE",
    ): Flow<List<PersonLinkEntity>>

    @Query("SELECT * FROM person_links WHERE target_type = :targetType AND target_id = :targetId AND status = :status")
    fun observeForTarget(
        targetType: String,
        targetId: Uuid,
        status: String = "ACTIVE",
    ): Flow<List<PersonLinkEntity>>

    @Query("DELETE FROM person_links WHERE person_id = :personId")
    suspend fun deleteForPerson(personId: Uuid)
}
