package app.logdate.client.database.dao.people

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface InferredPersonClusterDao {
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

    @Query("DELETE FROM inferred_person_evidence WHERE cluster_id IN (SELECT id FROM inferred_person_clusters WHERE status = :status)")
    suspend fun deleteEvidenceByClusterStatus(status: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: List<InferredPersonEvidenceEntity>)

    @Query("DELETE FROM person_links WHERE provenance = :provenance")
    suspend fun deleteLinksByProvenance(provenance: String)

    /** Inserts links, leaving any existing link between the same person and target untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinksIfAbsent(links: List<PersonLinkEntity>)

    /**
     * Replaces every suggestion with [openStatus] and every link with [replacedLinkProvenance] in
     * one transaction, so an interrupted refresh leaves the previous result in place.
     *
     * Room runs a transaction through a single DAO, which is why the evidence and link rows that
     * belong to a refresh are written here. Links with any other provenance are never replaced.
     */
    @Transaction
    suspend fun replaceOpenInference(
        openStatus: String,
        replacedLinkProvenance: String,
        clusters: List<InferredPersonClusterEntity>,
        evidence: List<InferredPersonEvidenceEntity>,
        links: List<PersonLinkEntity>,
    ) {
        deleteEvidenceByClusterStatus(openStatus)
        deleteByStatus(openStatus)
        deleteLinksByProvenance(replacedLinkProvenance)
        insertAll(clusters)
        insertEvidence(evidence)
        insertLinksIfAbsent(links)
    }
}
