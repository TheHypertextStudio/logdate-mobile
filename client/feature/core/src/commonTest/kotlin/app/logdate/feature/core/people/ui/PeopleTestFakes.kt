package app.logdate.feature.core.people.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.knowledge.ContactImportSummary
import app.logdate.client.repository.knowledge.DeviceContact
import app.logdate.client.repository.knowledge.DeviceContactsReader
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.repository.knowledge.PeopleContactsRepository
import app.logdate.client.repository.knowledge.PeopleProfileRepository
import app.logdate.client.repository.knowledge.PeopleRepository
import app.logdate.client.repository.knowledge.PersonRelatedContent
import app.logdate.shared.model.InferredPersonCluster
import app.logdate.shared.model.InferredPersonEvidence
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * An in-memory people directory for view model tests.
 *
 * Each operation can be made to fail by setting the matching failure, and [confirmGate],
 * [importGate] and [refreshGate] hold a confirmation, an import or a refresh open so a test can act while it is still running.
 */
internal class FakePeopleStore :
    PeopleRepository,
    PeopleContactsRepository,
    InferredPeopleRepository,
    PeopleProfileRepository {
    val people = MutableStateFlow<List<Person>>(emptyList())
    val clusters = MutableStateFlow<List<InferredPersonCluster>>(emptyList())
    val evidence = MutableStateFlow<List<InferredPersonEvidence>>(emptyList())
    val linkedEntries = MutableStateFlow<List<JournalNote>>(emptyList())

    var importCalls: Int = 0
    var refreshCalls: Int = 0
    val confirmedClusters = mutableListOf<Uuid>()
    val rejectedClusters = mutableListOf<Uuid>()
    val removedPeople = mutableListOf<Uuid>()

    var refreshFailure: Exception? = null
    var importFailure: Exception? = null
    var confirmFailure: Exception? = null
    var rejectFailure: Exception? = null
    var removeFailure: Exception? = null
    var profileFailure: Exception? = null
    var confirmGate: CompletableDeferred<Unit>? = null
    var importGate: CompletableDeferred<Unit>? = null
    var refreshGate: CompletableDeferred<Unit>? = null

    override suspend fun getPerson(uid: Uuid): Person = people.value.first { it.uid == uid }

    override fun getAllPeople(): Flow<List<Person>> = people

    override suspend fun resolvePersonByName(name: String): Person? = null

    override suspend fun resolvePersonByDescription(description: String): Person? = null

    override suspend fun addPerson(person: Person) = Unit

    override suspend fun updatePerson(person: Person) = Unit

    override suspend fun deletePerson(uid: Uuid) = Unit

    override suspend fun addAliasToPerson(
        personUid: Uuid,
        alias: String,
    ) = Unit

    override suspend fun removeAliasFromPerson(
        personUid: Uuid,
        alias: String,
    ) = Unit

    override fun observeImportedPeopleCount(origin: PersonOrigin): Flow<Int> =
        people.map { current -> current.count { it.origin == origin } }

    override suspend fun importContacts(
        contacts: List<DeviceContact>,
        origin: PersonOrigin,
    ): ContactImportSummary {
        importCalls += 1
        importGate?.await()
        importFailure?.let { throw it }
        people.value = people.value + contacts.map { Person(name = it.displayName, origin = origin) }
        return ContactImportSummary(importedCount = contacts.size, updatedCount = 0)
    }

    override fun observeOpenClusters(): Flow<List<InferredPersonCluster>> = clusters

    override fun observeEvidence(clusterId: Uuid): Flow<List<InferredPersonEvidence>> =
        evidence.map { current -> current.filter { it.clusterId == clusterId } }

    override suspend fun refresh() {
        refreshCalls += 1
        refreshGate?.await()
        refreshFailure?.let { throw it }
    }

    override suspend fun confirmClusterAsPerson(clusterId: Uuid) {
        confirmGate?.await()
        confirmFailure?.let { throw it }
        confirmedClusters += clusterId
        clusters.value = clusters.value.filterNot { it.uid == clusterId }
    }

    override suspend fun rejectCluster(clusterId: Uuid) {
        rejectFailure?.let { throw it }
        rejectedClusters += clusterId
        clusters.value = clusters.value.filterNot { it.uid == clusterId }
    }

    override fun observePerson(personId: Uuid): Flow<Person?> = people.map { current -> current.firstOrNull { it.uid == personId } }

    override fun observeProfile(personId: Uuid): Flow<PersonRelatedContent?> {
        val failure = profileFailure
        if (failure != null) return flow { throw failure }
        return combine(people, linkedEntries) { current, entries ->
            current.firstOrNull { it.uid == personId }?.let { person ->
                PersonRelatedContent(person = person, linkedEntries = entries, linkedEvents = emptyList())
            }
        }
    }

    override suspend fun removePerson(personId: Uuid) {
        removeFailure?.let { throw it }
        removedPeople += personId
        people.value = people.value.filterNot { it.uid == personId }
    }
}

internal class FakeDeviceContactsReader(
    private val fullContacts: List<DeviceContact> = emptyList(),
) : DeviceContactsReader {
    override fun supportsSelectedContactsPicker(): Boolean = false

    override suspend fun readAllContacts(): List<DeviceContact> = fullContacts

    override suspend fun readSelectedContacts(sessionUri: String): List<DeviceContact> = emptyList()
}

internal class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
}
