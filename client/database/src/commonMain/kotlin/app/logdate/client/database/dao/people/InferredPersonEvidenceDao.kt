package app.logdate.client.database.dao.people

import androidx.room.Dao
import androidx.room.Query
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface InferredPersonEvidenceDao {
    @Query("SELECT * FROM inferred_person_evidence WHERE cluster_id = :clusterId ORDER BY confidence DESC, created DESC")
    fun observeForCluster(clusterId: Uuid): Flow<List<InferredPersonEvidenceEntity>>

    @Query("SELECT * FROM inferred_person_evidence WHERE cluster_id IN (:clusterIds) ORDER BY confidence DESC, created DESC")
    suspend fun getForClusters(clusterIds: List<Uuid>): List<InferredPersonEvidenceEntity>

    @Query("DELETE FROM inferred_person_evidence WHERE cluster_id IN (:clusterIds)")
    suspend fun deleteForClusters(clusterIds: List<Uuid>)
}
