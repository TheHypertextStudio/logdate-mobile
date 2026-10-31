package app.logdate.client.database.dao.people

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface InferredPersonClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cluster: InferredPersonClusterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clusters: List<InferredPersonClusterEntity>)

    @Update
    suspend fun update(cluster: InferredPersonClusterEntity)

    @Query("SELECT * FROM inferred_person_clusters WHERE id = :id LIMIT 1")
    suspend fun getById(id: Uuid): InferredPersonClusterEntity?

    @Query("SELECT * FROM inferred_person_clusters WHERE status = :status ORDER BY last_updated DESC")
    fun observeByStatus(status: String): Flow<List<InferredPersonClusterEntity>>

    @Query("SELECT * FROM inferred_person_clusters WHERE status = :status ORDER BY last_updated DESC")
    suspend fun getByStatus(status: String): List<InferredPersonClusterEntity>

    @Query("DELETE FROM inferred_person_clusters WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query(
        "DELETE FROM inferred_person_evidence WHERE cluster_id IN " +
            "(SELECT id FROM inferred_person_clusters WHERE status = :status)",
    )
    suspend fun deleteEvidenceForStatus(status: String)

    @Query("DELETE FROM person_links WHERE provenance = :provenance")
    suspend fun deleteLinksByProvenance(provenance: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeople(people: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<PersonLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: List<InferredPersonEvidenceEntity>)

    /**
     * Replaces every open suggestion and inferred link with a freshly computed set in one
     * transaction, so screens never observe the graph half cleared and a failed refresh keeps the
     * previous suggestions.
     */
    @Transaction
    suspend fun replaceOpenInference(
        openStatus: String,
        inferredProvenance: String,
        people: List<PersonEntity>,
        links: List<PersonLinkEntity>,
        clusters: List<InferredPersonClusterEntity>,
        evidence: List<InferredPersonEvidenceEntity>,
    ) {
        deleteEvidenceForStatus(openStatus)
        deleteByStatus(openStatus)
        deleteLinksByProvenance(inferredProvenance)
        insertPeople(people)
        insertLinks(links)
        insertAll(clusters)
        insertEvidence(evidence)
    }
}
