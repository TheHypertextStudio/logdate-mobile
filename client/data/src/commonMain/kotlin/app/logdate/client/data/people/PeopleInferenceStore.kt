@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.client.data.people

import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.EventDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.dao.people.InferredPersonClusterDao
import app.logdate.client.database.dao.people.InferredPersonEvidenceDao
import app.logdate.client.database.dao.people.PersonDao
import app.logdate.client.database.dao.people.PersonLinkDao
import app.logdate.client.database.dao.people.PersonResolutionDecisionDao
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonResolutionDecisionEntity
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonLinkProvenance
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Finds names in entries and transcripts, and records what the person decides about them.
 *
 * Nothing found here enters the directory on its own: every name waits in the review inbox until
 * it is confirmed. Refreshing, confirming, rejecting and removing each wait for the one before to
 * finish, so two of them never work from the same stale rows. Each of them writes in one
 * transaction, so an interrupted one leaves the rows as they were.
 *
 * @param transactions runs the writes of one confirmation, rejection or removal together
 * @param readsEvents whether event titles and descriptions are read for names too. They are read
 *   only once Events has launched, so a suggestion never rests on an event nobody can open.
 */
internal class PeopleInferenceStore(
    private val personDao: PersonDao,
    private val clusterDao: InferredPersonClusterDao,
    private val evidenceDao: InferredPersonEvidenceDao,
    private val linkDao: PersonLinkDao,
    private val decisionDao: PersonResolutionDecisionDao,
    private val textNoteDao: TextNoteDao,
    private val audioNoteDao: AudioNoteDao,
    private val transcriptionDao: TranscriptionDao,
    private val eventDao: EventDao,
    private val transactions: SyncTransactionManager,
    private val dispatcher: CoroutineDispatcher,
    private val readsEvents: Boolean = FeatureFlag.EVENTS.availableForLaunch,
) {
    private val writeLock = Mutex()

    /**
     * Rebuilds the open suggestions and the machine-made links from the current journal.
     *
     * The previous result is replaced in one transaction. Links made by confirming a suggestion
     * are left as they are.
     */
    suspend fun refresh() =
        serialized {
            val plan =
                planPeopleInference(
                    people = personDao.getAll(),
                    suppressedNames = suppressedNames(),
                    openClusters = clusterDao.getByStatus(InferredPersonClusterStatus.OPEN.name),
                    artifacts =
                        loadPeopleInferenceArtifacts(textNoteDao, audioNoteDao, transcriptionDao, eventDao.takeIf { readsEvents }),
                    now = Clock.System.now(),
                )
            clusterDao.replaceOpenInference(
                openStatus = InferredPersonClusterStatus.OPEN.name,
                replacedLinkProvenance = PersonLinkProvenance.INFERRED.name,
                clusters = plan.clusters,
                evidence = plan.evidence,
                links = plan.links,
            )
        }

    /**
     * Adds the suggested name to the directory, or to the person who already has that name, and
     * links that person to every entry behind the suggestion.
     */
    suspend fun confirmClusterAsPerson(clusterId: Uuid) =
        written {
            val cluster = clusterDao.getById(clusterId) ?: return@written
            if (cluster.status != InferredPersonClusterStatus.OPEN.name) return@written
            val evidence = evidenceDao.getForClusters(listOf(clusterId))
            val now = Clock.System.now()
            val personId = personNamed(cluster.normalizedName)?.id ?: addInferredPerson(cluster.displayNameHint, now)

            linkDao.insertAll(
                evidence
                    .mapNotNull { item -> confirmedLink(personId, item, now) }
                    .distinctBy { link -> link.targetType to link.targetId },
            )
            clusterDao.update(
                cluster.copy(
                    status = InferredPersonClusterStatus.RESOLVED.name,
                    linkedPersonId = personId,
                    lastUpdated = now,
                ),
            )
            decisionDao.insert(decision(cluster.normalizedName, ACTION_CONFIRM, now, personId))
            evidenceDao.deleteForClusters(listOf(clusterId))
        }

    /** Closes the suggestion and records that its name must not be suggested again. */
    suspend fun rejectCluster(clusterId: Uuid) =
        written {
            val cluster = clusterDao.getById(clusterId) ?: return@written
            if (cluster.status != InferredPersonClusterStatus.OPEN.name) return@written
            val now = Clock.System.now()

            decisionDao.insert(decision(cluster.normalizedName, ACTION_SUPPRESS, now))
            clusterDao.update(
                cluster.copy(
                    status = InferredPersonClusterStatus.REJECTED.name,
                    lastUpdated = now,
                ),
            )
            evidenceDao.deleteForClusters(listOf(clusterId))
        }

    /**
     * Removes a person from the directory along with their links, and records that neither their
     * name nor any of their other names is suggested again.
     */
    suspend fun removePerson(personId: Uuid) =
        written {
            val person = personDao.getById(personId) ?: return@written
            val now = Clock.System.now()

            (person.aliases + person.name)
                .map(::normalizePersonKey)
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { name -> decisionDao.insert(decision(name, ACTION_SUPPRESS, now)) }
            linkDao.deleteForPerson(personId)
            personDao.softDelete(personId, now)
        }

    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(dispatcher) { writeLock.withLock { block() } }

    private suspend fun <T> written(block: suspend () -> T): T = serialized { transactions.withTransaction(block) }

    private suspend fun suppressedNames(): Set<String> =
        decisionDao
            .getAll()
            .filter { it.action == ACTION_SUPPRESS }
            .mapTo(mutableSetOf(), PersonResolutionDecisionEntity::normalizedName)

    private suspend fun personNamed(normalizedName: String): PersonEntity? =
        personDao.getAll().firstOrNull { person ->
            (person.aliases + person.name).any { name -> normalizePersonKey(name) == normalizedName }
        }

    private suspend fun addInferredPerson(
        name: String,
        now: Instant,
    ): Uuid {
        val person = Person(name = name, origin = PersonOrigin.INFERRED)
        personDao.insert(person.toEntity(createdAt = now))
        return person.uid
    }

    private fun decision(
        normalizedName: String,
        action: String,
        now: Instant,
        personId: Uuid? = null,
    ) = PersonResolutionDecisionEntity(
        id = Uuid.random(),
        normalizedName = normalizedName,
        action = action,
        personId = personId,
        created = now,
        lastUpdated = now,
    )
}

private const val ACTION_CONFIRM = "CONFIRM"
private const val ACTION_SUPPRESS = "SUPPRESS"
