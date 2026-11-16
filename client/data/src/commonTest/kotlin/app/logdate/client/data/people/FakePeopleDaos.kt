package app.logdate.client.data.people

import app.logdate.client.database.dao.people.InferredPersonClusterDao
import app.logdate.client.database.dao.people.InferredPersonEvidenceDao
import app.logdate.client.database.dao.people.PersonDao
import app.logdate.client.database.dao.people.PersonLinkDao
import app.logdate.client.database.dao.people.PersonResolutionDecisionDao
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.client.database.entities.people.PersonResolutionDecisionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * In-memory People tables shared by the five People DAOs.
 *
 * Every write suspends before it changes a table, the way a real database call does, so that two
 * callers that are not serialized interleave. The uniqueness rules of the real tables are kept:
 * one person per contact lookup key, one decision per name and one link per person and target.
 */
internal class FakePeopleDaos {
    val people = linkedMapOf<Uuid, PersonEntity>()
    val clusters = linkedMapOf<Uuid, InferredPersonClusterEntity>()
    val evidence = linkedMapOf<Uuid, InferredPersonEvidenceEntity>()
    val links = linkedMapOf<Uuid, PersonLinkEntity>()
    val decisions = linkedMapOf<String, PersonResolutionDecisionEntity>()

    fun clustersWithStatus(status: String): List<InferredPersonClusterEntity> =
        clusters.values.filter { it.status == status }.sortedByDescending { it.lastUpdated }

    fun linksFor(personId: Uuid): List<PersonLinkEntity> = links.values.filter { it.personId == personId }

    val personDao: PersonDao =
        object : PersonDao {
            override suspend fun insert(person: PersonEntity) {
                yield()
                person.contactLookupKey?.let { key -> people.values.removeAll { it.contactLookupKey == key } }
                people[person.id] = person
            }

            override suspend fun update(person: PersonEntity) {
                yield()
                people[person.id] = person
            }

            override suspend fun getById(id: Uuid): PersonEntity? {
                yield()
                return people[id]?.takeIf { it.deletedAt == null }
            }

            override fun observeById(id: Uuid): Flow<PersonEntity?> = flow { emit(people[id]?.takeIf { it.deletedAt == null }) }

            override fun observeAll(): Flow<List<PersonEntity>> = flow { emit(currentPeople()) }

            override suspend fun getByContactLookupKey(lookupKey: String): PersonEntity? =
                currentPeople().firstOrNull { it.contactLookupKey == lookupKey }

            override suspend fun getRemovedContactLookupKeys(): List<String> =
                people.values.filter { it.deletedAt != null }.mapNotNull { it.contactLookupKey }

            override suspend fun getAll(): List<PersonEntity> {
                yield()
                return currentPeople()
            }

            override fun observeCountByOrigin(origin: String): Flow<Int> = flow { emit(currentPeople().count { it.origin == origin }) }

            override suspend fun softDelete(
                id: Uuid,
                deletedAt: Instant,
            ) {
                yield()
                val existing = people[id] ?: return
                people[id] = existing.copy(deletedAt = deletedAt)
            }
        }

    val clusterDao: InferredPersonClusterDao =
        object : InferredPersonClusterDao {
            override suspend fun insertAll(clusters: List<InferredPersonClusterEntity>) {
                yield()
                clusters.forEach { this@FakePeopleDaos.clusters[it.id] = it }
            }

            override suspend fun update(cluster: InferredPersonClusterEntity) {
                yield()
                clusters[cluster.id] = cluster
            }

            override suspend fun getById(id: Uuid): InferredPersonClusterEntity? {
                yield()
                return clusters[id]
            }

            override fun observeByStatus(status: String): Flow<List<InferredPersonClusterEntity>> =
                flow { emit(clustersWithStatus(status)) }

            override suspend fun getByStatus(status: String): List<InferredPersonClusterEntity> {
                yield()
                return clustersWithStatus(status)
            }

            override suspend fun deleteByStatus(status: String) {
                yield()
                clusters.values.removeAll { it.status == status }
            }

            override suspend fun deleteEvidenceByClusterStatus(status: String) {
                yield()
                val clusterIds = clustersWithStatus(status).map { it.id }.toSet()
                evidence.values.removeAll { it.clusterId in clusterIds }
            }

            override suspend fun insertEvidence(evidence: List<InferredPersonEvidenceEntity>) {
                yield()
                evidence.forEach { this@FakePeopleDaos.evidence[it.id] = it }
            }

            override suspend fun deleteLinksByProvenance(provenance: String) {
                yield()
                links.values.removeAll { it.provenance == provenance }
            }

            override suspend fun insertLinksIfAbsent(links: List<PersonLinkEntity>) {
                yield()
                links
                    .filter { link -> existingLink(link) == null }
                    .forEach { this@FakePeopleDaos.links[it.id] = it }
            }
        }

    val evidenceDao: InferredPersonEvidenceDao =
        object : InferredPersonEvidenceDao {
            override fun observeForCluster(clusterId: Uuid): Flow<List<InferredPersonEvidenceEntity>> =
                flow { emit(evidence.values.filter { it.clusterId == clusterId }) }

            override suspend fun getForClusters(clusterIds: List<Uuid>): List<InferredPersonEvidenceEntity> {
                yield()
                return evidence.values.filter { it.clusterId in clusterIds }
            }

            override suspend fun deleteForClusters(clusterIds: List<Uuid>) {
                yield()
                evidence.values.removeAll { it.clusterId in clusterIds }
            }
        }

    val linkDao: PersonLinkDao =
        object : PersonLinkDao {
            override suspend fun insertAll(links: List<PersonLinkEntity>) {
                yield()
                links.forEach { link ->
                    existingLink(link)?.let { this@FakePeopleDaos.links.remove(it.id) }
                    this@FakePeopleDaos.links[link.id] = link
                }
            }

            override suspend fun update(link: PersonLinkEntity) {
                yield()
                links[link.id] = link
            }

            override fun observeForPerson(
                personId: Uuid,
                status: String,
            ): Flow<List<PersonLinkEntity>> = flow { emit(linksFor(personId).filter { it.status == status }) }

            override fun observeForTarget(
                targetType: String,
                targetId: Uuid,
                status: String,
            ): Flow<List<PersonLinkEntity>> =
                flow {
                    emit(links.values.filter { it.targetType == targetType && it.targetId == targetId && it.status == status })
                }

            override suspend fun deleteForPerson(personId: Uuid) {
                yield()
                links.values.removeAll { it.personId == personId }
            }
        }

    val decisionDao: PersonResolutionDecisionDao =
        object : PersonResolutionDecisionDao {
            override suspend fun insert(decision: PersonResolutionDecisionEntity) {
                yield()
                decisions[decision.normalizedName] = decision
            }

            override suspend fun getAll(): List<PersonResolutionDecisionEntity> {
                yield()
                return decisions.values.toList()
            }

            override suspend fun getByNormalizedName(normalizedName: String): PersonResolutionDecisionEntity? = decisions[normalizedName]
        }

    private fun currentPeople(): List<PersonEntity> = people.values.filter { it.deletedAt == null }

    private fun existingLink(link: PersonLinkEntity): PersonLinkEntity? =
        links.values.firstOrNull { it.personId == link.personId && it.targetType == link.targetType && it.targetId == link.targetId }
}
