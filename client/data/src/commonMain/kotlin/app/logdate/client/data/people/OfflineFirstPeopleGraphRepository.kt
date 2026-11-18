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
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.repository.knowledge.PeopleProfileRepository
import app.logdate.client.repository.knowledge.PersonLinkRepository
import app.logdate.client.repository.knowledge.PersonRelatedContent
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.util.platformDataDispatcher
import app.logdate.shared.model.Event
import app.logdate.shared.model.InferredPersonCluster
import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.InferredPersonEvidence
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonLink
import app.logdate.shared.model.PersonLinkTargetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class OfflineFirstPeopleGraphRepository(
    private val personDao: PersonDao,
    private val inferredPersonClusterDao: InferredPersonClusterDao,
    private val inferredPersonEvidenceDao: InferredPersonEvidenceDao,
    private val personLinkDao: PersonLinkDao,
    personResolutionDecisionDao: PersonResolutionDecisionDao,
    textNoteDao: TextNoteDao,
    audioNoteDao: AudioNoteDao,
    transcriptionDao: TranscriptionDao,
    eventDao: EventDao,
    transactions: SyncTransactionManager,
    private val journalNotesRepository: JournalNotesRepository,
    private val eventRepository: EventRepository,
    dispatcher: CoroutineDispatcher = platformDataDispatcher,
) : InferredPeopleRepository,
    PersonLinkRepository,
    PeopleProfileRepository {
    private val inference =
        PeopleInferenceStore(
            personDao = personDao,
            clusterDao = inferredPersonClusterDao,
            evidenceDao = inferredPersonEvidenceDao,
            linkDao = personLinkDao,
            decisionDao = personResolutionDecisionDao,
            textNoteDao = textNoteDao,
            audioNoteDao = audioNoteDao,
            transcriptionDao = transcriptionDao,
            eventDao = eventDao,
            transactions = transactions,
            dispatcher = dispatcher,
        )
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
     * Recomputes the suggestions and machine-made links from every entry, transcript and event.
     * Several screens ask for a refresh as they open; a call made while another refresh runs waits
     * for it and reuses its result when that refresh started after the call was made.
     */
    override suspend fun refresh() {
        val requested = refreshRequestLock.withLock { ++refreshRequests }
        refreshMutex.withLock {
            if (completedRefreshCovers >= requested) return
            val covers = refreshRequestLock.withLock { refreshRequests }
            inference.refresh()
            completedRefreshCovers = covers
        }
    }

    override suspend fun confirmClusterAsPerson(clusterId: Uuid) = inference.confirmClusterAsPerson(clusterId)

    override suspend fun rejectCluster(clusterId: Uuid) = inference.rejectCluster(clusterId)

    override suspend fun removePerson(personId: Uuid) = inference.removePerson(personId)

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
}
