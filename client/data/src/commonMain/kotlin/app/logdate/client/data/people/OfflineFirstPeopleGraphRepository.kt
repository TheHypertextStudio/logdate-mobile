package app.logdate.client.data.people

import app.logdate.client.database.dao.EventDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.TranscriptionDao
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
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.repository.knowledge.PeopleProfileRepository
import app.logdate.client.repository.knowledge.PersonLinkRepository
import app.logdate.client.repository.knowledge.PersonRelatedContent
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.model.Event
import app.logdate.shared.model.InferredPersonCluster
import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.InferredPersonEvidence
import app.logdate.shared.model.InferredPersonEvidenceSourceType
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonLink
import app.logdate.shared.model.PersonLinkProvenance
import app.logdate.shared.model.PersonLinkStatus
import app.logdate.shared.model.PersonLinkTargetType
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class OfflineFirstPeopleGraphRepository(
    private val personDao: PersonDao,
    private val inferredPersonClusterDao: InferredPersonClusterDao,
    private val inferredPersonEvidenceDao: InferredPersonEvidenceDao,
    private val personLinkDao: PersonLinkDao,
    private val personResolutionDecisionDao: PersonResolutionDecisionDao,
    private val textNoteDao: TextNoteDao,
    private val transcriptionDao: TranscriptionDao,
    private val eventDao: EventDao,
    private val journalNotesRepository: JournalNotesRepository,
    private val eventRepository: EventRepository,
    private val dispatcher: CoroutineDispatcher = platformIODispatcher,
) : InferredPeopleRepository,
    PersonLinkRepository,
    PeopleProfileRepository {
    private val refreshMutex = Mutex()
    private val refreshRequestLock = Mutex()
    private var refreshRequests = 0L
    private var completedRefreshCovers = 0L

    override fun observeOpenClusters(): Flow<List<InferredPersonCluster>> =
        inferredPersonClusterDao.observeByStatus(InferredPersonClusterStatus.OPEN.name).map { clusters ->
            clusters.map(InferredPersonClusterEntity::toModel)
        }

    override fun observeEvidence(clusterId: Uuid): Flow<List<InferredPersonEvidence>> =
        inferredPersonEvidenceDao.observeForCluster(clusterId).map { evidence ->
            evidence.map(InferredPersonEvidenceEntity::toModel)
        }

    override fun observeLinksForPerson(personId: Uuid): Flow<List<PersonLink>> =
        personLinkDao.observeForPerson(personId).map { links ->
            links.map(PersonLinkEntity::toModel)
        }

    override fun observeLinksForTarget(
        targetType: PersonLinkTargetType,
        targetId: Uuid,
    ): Flow<List<PersonLink>> =
        personLinkDao.observeForTarget(targetType.name, targetId).map { links ->
            links.map(PersonLinkEntity::toModel)
        }

    override fun observePerson(personId: Uuid): Flow<Person?> =
        personDao
            .observeById(personId)
            .map { it?.toModel() }

    override fun observeProfile(personId: Uuid): Flow<PersonRelatedContent?> =
        combine(
            personDao.observeById(personId),
            personLinkDao.observeForPerson(personId),
        ) { person, links -> person to links }
            .mapLatest { (personEntity, links) ->
                val person = personEntity?.toModel() ?: return@mapLatest null
                val linkedEntries = loadLinkedEntries(links)
                val linkedEvents = loadLinkedEvents(links)
                PersonRelatedContent(
                    person = person,
                    linkedEntries = linkedEntries,
                    linkedEvents = linkedEvents,
                )
            }

    /**
     * Recomputes inferred people from every entry, transcript and event. Several screens ask for a
     * refresh as they open; a call made while another refresh runs waits for it and reuses its
     * result when that refresh started after the call was made.
     */
    override suspend fun refresh() {
        val requested = refreshRequestLock.withLock { ++refreshRequests }
        refreshMutex.withLock {
            if (completedRefreshCovers >= requested) return
            val covers = refreshRequestLock.withLock { refreshRequests }
            withContext(dispatcher) { recomputeInference() }
            completedRefreshCovers = covers
        }
    }

    private suspend fun recomputeInference() {
        val canonicalPeople = personDao.getAll()
        val suppressedNames =
            personResolutionDecisionDao
                .getAll()
                .filter { it.action == ACTION_SUPPRESS }
                .map(PersonResolutionDecisionEntity::normalizedName)
                .toSet()
        val canonicalKeys =
            canonicalPeople.flatMapTo(mutableSetOf()) { person ->
                (listOf(person.name) + person.aliases).map(::normalizePersonKey)
            }
        val excludedNames = suppressedNames + canonicalKeys
        val canonicalMatchers = canonicalPeople.map(::CanonicalPersonMatcher)
        val canonicalEvidence = mutableMapOf<Uuid, MutableList<CandidateEvidence>>()
        val inferredEvidence = mutableMapOf<String, MutableList<CandidateEvidence>>()

        loadPeopleInferenceArtifacts(textNoteDao, transcriptionDao, eventDao).forEach { artifact ->
            collectEvidence(artifact, canonicalMatchers, excludedNames, canonicalEvidence, inferredEvidence)
        }

        val plan = InferencePlan()
        planCanonicalLinks(canonicalEvidence, plan)
        planInferredCandidates(inferredEvidence, plan)
        inferredPersonClusterDao.replaceOpenInference(
            openStatus = InferredPersonClusterStatus.OPEN.name,
            inferredProvenance = PersonLinkProvenance.INFERRED.name,
            people = plan.people,
            links = plan.links,
            clusters = plan.clusters,
            evidence = plan.evidence,
        )
    }

    /**
     * Records which known people [artifact] mentions, and which other capitalised names in it could
     * be people, skipping names that are suppressed or already belong to someone known.
     */
    private fun collectEvidence(
        artifact: InferenceArtifact,
        canonicalMatchers: List<CanonicalPersonMatcher>,
        excludedNames: Set<String>,
        canonicalEvidence: MutableMap<Uuid, MutableList<CandidateEvidence>>,
        inferredEvidence: MutableMap<String, MutableList<CandidateEvidence>>,
    ) {
        val normalizedText = artifact.text.lowercase()
        val label = previewEvidenceLabel(artifact.text)
        canonicalMatchers.filter { it.matches(normalizedText) }.forEach { matcher ->
            val person = matcher.person
            canonicalEvidence.getOrPut(person.id) { mutableListOf() } +=
                CandidateEvidence(
                    displayName = person.name,
                    normalizedName = normalizePersonKey(person.name),
                    sourceType = artifact.sourceType,
                    sourceId = artifact.sourceId,
                    createdAt = artifact.createdAt,
                    label = label,
                    confidence = CANONICAL_EVIDENCE_CONFIDENCE,
                )
        }
        extractPotentialPersonNames(artifact.text).forEach { candidateName ->
            val normalizedName = normalizePersonKey(candidateName)
            if (normalizedName.isBlank() || normalizedName in excludedNames) return@forEach
            inferredEvidence.getOrPut(normalizedName) { mutableListOf() } +=
                CandidateEvidence(
                    displayName = candidateName,
                    normalizedName = normalizedName,
                    sourceType = artifact.sourceType,
                    sourceId = artifact.sourceId,
                    createdAt = artifact.createdAt,
                    label = label,
                    confidence = CLUSTER_EVIDENCE_CONFIDENCE,
                )
        }
    }

    override suspend fun confirmClusterAsPerson(clusterId: Uuid) {
        withContext(dispatcher) {
            val cluster = inferredPersonClusterDao.getById(clusterId) ?: return@withContext
            val evidence = inferredPersonEvidenceDao.getForClusters(listOf(clusterId))
            val existingPerson =
                personDao.getAll().firstOrNull { entity ->
                    entity.name.equals(cluster.displayNameHint, ignoreCase = true) ||
                        entity.aliases.any { alias -> alias.equals(cluster.displayNameHint, ignoreCase = true) }
                }
            val now = Clock.System.now()
            val personId =
                existingPerson?.id ?: Uuid.random().also { newId ->
                    personDao.insert(
                        Person(
                            uid = newId,
                            name = cluster.displayNameHint,
                            origin = PersonOrigin.INFERRED,
                        ).toEntity(createdAt = now),
                    )
                }

            clusterToLinks(personId = personId, evidence = evidence).forEach { link ->
                personLinkDao.insert(link)
            }
            inferredPersonClusterDao.update(
                cluster.copy(
                    status = InferredPersonClusterStatus.RESOLVED.name,
                    linkedPersonId = personId,
                    lastUpdated = now,
                ),
            )
            personResolutionDecisionDao.insert(
                PersonResolutionDecisionEntity(
                    id = Uuid.random(),
                    normalizedName = cluster.normalizedName,
                    action = ACTION_CONFIRM,
                    personId = personId,
                    created = now,
                    lastUpdated = now,
                ),
            )
        }
    }

    override suspend fun rejectCluster(clusterId: Uuid) {
        withContext(dispatcher) {
            val cluster = inferredPersonClusterDao.getById(clusterId) ?: return@withContext
            val now = Clock.System.now()
            inferredPersonClusterDao.update(
                cluster.copy(
                    status = InferredPersonClusterStatus.REJECTED.name,
                    lastUpdated = now,
                ),
            )
            personResolutionDecisionDao.insert(
                PersonResolutionDecisionEntity(
                    id = Uuid.random(),
                    normalizedName = cluster.normalizedName,
                    action = ACTION_SUPPRESS,
                    created = now,
                    lastUpdated = now,
                ),
            )
        }
    }

    private fun planCanonicalLinks(
        evidenceByPersonId: Map<Uuid, List<CandidateEvidence>>,
        plan: InferencePlan,
    ) {
        evidenceByPersonId.forEach { (personId, evidence) ->
            val distinctArtifacts = evidence.distinctBy { it.sourceId }
            if (distinctArtifacts.size < MIN_CANONICAL_LINK_ARTIFACTS) {
                return@forEach
            }
            distinctArtifacts.mapNotNullTo(plan.links) { candidate ->
                candidate.toPersonLink(personId, confidence = CANONICAL_LINK_CONFIDENCE)
            }
        }
    }

    private fun planInferredCandidates(
        candidates: Map<String, List<CandidateEvidence>>,
        plan: InferencePlan,
    ) {
        candidates.forEach { (_, evidence) ->
            val distinctArtifacts = evidence.distinctBy { it.sourceId }
            if (distinctArtifacts.isEmpty()) {
                return@forEach
            }

            val nameHint = evidence.first().displayName
            val normalizedName = evidence.first().normalizedName
            val distinctDays =
                evidence
                    .map { candidate ->
                        candidate.createdAt
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                    }.toSet()

            if (distinctArtifacts.size >= AUTO_PROMOTE_MIN_ARTIFACTS && distinctDays.size >= AUTO_PROMOTE_MIN_DAYS) {
                val now = Clock.System.now()
                val personId = Uuid.random()
                plan.people +=
                    Person(
                        uid = personId,
                        name = nameHint,
                        origin = PersonOrigin.INFERRED,
                    ).toEntity(createdAt = now)
                distinctArtifacts.mapNotNullTo(plan.links) { candidate ->
                    candidate.toPersonLink(personId, confidence = AUTO_PROMOTE_LINK_CONFIDENCE)
                }
                return@forEach
            }

            val now = Clock.System.now()
            val clusterId = Uuid.random()
            plan.clusters +=
                InferredPersonClusterEntity(
                    id = clusterId,
                    displayNameHint = nameHint,
                    normalizedName = normalizedName,
                    status = InferredPersonClusterStatus.OPEN.name,
                    created = now,
                    lastUpdated = now,
                )
            plan.evidence +=
                evidence.map { candidate ->
                    InferredPersonEvidenceEntity(
                        id = Uuid.random(),
                        clusterId = clusterId,
                        sourceType = candidate.sourceType.name,
                        sourceId = candidate.sourceId,
                        label = candidate.label,
                        confidence = candidate.confidence,
                        created = candidate.createdAt,
                    )
                }
        }
    }

    private suspend fun loadLinkedEntries(links: List<PersonLinkEntity>): List<JournalNote> =
        links
            .filter { it.targetType == PersonLinkTargetType.ENTRY.name }
            .mapNotNull { link -> journalNotesRepository.getNoteById(link.targetId) }
            .sortedByDescending(JournalNote::creationTimestamp)

    private suspend fun loadLinkedEvents(links: List<PersonLinkEntity>): List<Event> =
        links
            .filter { it.targetType == PersonLinkTargetType.EVENT.name }
            .mapNotNull { link -> eventRepository.getEventById(link.targetId) }
            .sortedByDescending(Event::startTime)

    private fun clusterToLinks(
        personId: Uuid,
        evidence: List<InferredPersonEvidenceEntity>,
    ): List<PersonLinkEntity> {
        val now = Clock.System.now()
        return evidence.mapNotNull { item ->
            when (item.sourceType) {
                InferredPersonEvidenceSourceType.ENTRY_TEXT.name,
                InferredPersonEvidenceSourceType.TRANSCRIPT.name,
                ->
                    PersonLinkEntity(
                        id = Uuid.random(),
                        personId = personId,
                        targetType = PersonLinkTargetType.ENTRY.name,
                        targetId = item.sourceId,
                        provenance = PersonLinkProvenance.INFERRED.name,
                        confidence = item.confidence,
                        status = PersonLinkStatus.ACTIVE.name,
                        created = now,
                        lastUpdated = now,
                    )

                InferredPersonEvidenceSourceType.EVENT_TEXT.name ->
                    PersonLinkEntity(
                        id = Uuid.random(),
                        personId = personId,
                        targetType = PersonLinkTargetType.EVENT.name,
                        targetId = item.sourceId,
                        provenance = PersonLinkProvenance.INFERRED.name,
                        confidence = item.confidence,
                        status = PersonLinkStatus.ACTIVE.name,
                        created = now,
                        lastUpdated = now,
                    )

                else -> null
            }
        }
    }

    private fun CandidateEvidence.toPersonLink(
        personId: Uuid,
        confidence: Double,
    ): PersonLinkEntity? {
        val targetType =
            when (sourceType) {
                InferredPersonEvidenceSourceType.ENTRY_TEXT,
                InferredPersonEvidenceSourceType.TRANSCRIPT,
                -> PersonLinkTargetType.ENTRY
                InferredPersonEvidenceSourceType.EVENT_TEXT -> PersonLinkTargetType.EVENT
                else -> null
            } ?: return null
        val now = Clock.System.now()
        return PersonLinkEntity(
            id = Uuid.random(),
            personId = personId,
            targetType = targetType.name,
            targetId = sourceId,
            provenance = PersonLinkProvenance.INFERRED.name,
            confidence = confidence,
            status = PersonLinkStatus.ACTIVE.name,
            created = now,
            lastUpdated = now,
        )
    }

    /** Everything one refresh writes, applied together by [InferredPersonClusterDao.replaceOpenInference]. */
    private class InferencePlan {
        val people = mutableListOf<PersonEntity>()
        val links = mutableListOf<PersonLinkEntity>()
        val clusters = mutableListOf<InferredPersonClusterEntity>()
        val evidence = mutableListOf<InferredPersonEvidenceEntity>()
    }

    private data class CandidateEvidence(
        val displayName: String,
        val normalizedName: String,
        val sourceType: InferredPersonEvidenceSourceType,
        val sourceId: Uuid,
        val createdAt: Instant,
        val label: String,
        val confidence: Double,
    )
}

private const val ACTION_CONFIRM = "CONFIRM"
private const val ACTION_SUPPRESS = "SUPPRESS"
private const val AUTO_PROMOTE_LINK_CONFIDENCE = 0.96
private const val AUTO_PROMOTE_MIN_ARTIFACTS = 3
private const val AUTO_PROMOTE_MIN_DAYS = 2
private const val CANONICAL_EVIDENCE_CONFIDENCE = 0.92
private const val CANONICAL_LINK_CONFIDENCE = 0.94
private const val CLUSTER_EVIDENCE_CONFIDENCE = 0.72
private const val MIN_CANONICAL_LINK_ARTIFACTS = 2
