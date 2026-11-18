package app.logdate.client.data.people

import app.logdate.client.database.dao.people.PersonDao
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.repository.knowledge.ContactImportSummary
import app.logdate.client.repository.knowledge.DeviceContact
import app.logdate.client.repository.knowledge.PeopleContactsRepository
import app.logdate.client.repository.knowledge.PeopleRepository
import app.logdate.client.util.platformDataDispatcher
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonOrigin
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class OfflineFirstPeopleRepository(
    private val personDao: PersonDao,
    private val dispatcher: CoroutineDispatcher = platformDataDispatcher,
    private val mediaClaimFence: MediaClaimFence = MediaClaimFence.Disabled,
) : PeopleRepository,
    PeopleContactsRepository {
    override suspend fun getPerson(uid: Uuid): Person =
        withContext(dispatcher) {
            personDao.getById(uid)?.toModel() ?: error("Person $uid not found")
        }

    override fun getAllPeople(): Flow<List<Person>> =
        personDao.observeAll().map { entities ->
            entities.map(PersonEntity::toModel)
        }

    override suspend fun resolvePersonByName(name: String): Person? =
        withContext(dispatcher) {
            val normalizedName = normalizePersonName(name)
            if (normalizedName.isEmpty()) {
                return@withContext null
            }

            val matchedPerson =
                personDao.getAll().firstOrNull { entity ->
                    entity.name.equals(normalizedName, ignoreCase = true) ||
                        entity.aliases.any { alias -> alias.equals(normalizedName, ignoreCase = true) }
                }

            matchedPerson?.toModel()
        }

    override suspend fun resolvePersonByDescription(description: String): Person? = resolvePersonByName(description)

    override suspend fun addPerson(person: Person) {
        withContext(dispatcher) {
            mediaClaimFence.withClaim { personDao.insert(person.toEntity(createdAt = Clock.System.now())) }
        }
    }

    override suspend fun updatePerson(person: Person) {
        withContext(dispatcher) {
            mediaClaimFence.withClaim {
                val existing = personDao.getById(person.uid) ?: return@withClaim
                personDao.update(
                    person.toEntity(
                        createdAt = existing.created,
                        contactLookupKey = existing.contactLookupKey,
                    ),
                )
            }
        }
    }

    override suspend fun deletePerson(uid: Uuid) {
        withContext(dispatcher) {
            mediaClaimFence.withClaim { personDao.softDelete(uid, Clock.System.now()) }
        }
    }

    override suspend fun addAliasToPerson(
        personUid: Uuid,
        alias: String,
    ) {
        withContext(dispatcher) {
            val normalizedAlias = alias.trim()
            if (normalizedAlias.isEmpty()) {
                return@withContext
            }

            mediaClaimFence.withClaim {
                val existing = personDao.getById(personUid) ?: return@withClaim
                if (existing.aliases.any { it.equals(normalizedAlias, ignoreCase = true) }) return@withClaim
                personDao.update(
                    existing.copy(
                        aliases = existing.aliases + normalizedAlias,
                        lastUpdated = Clock.System.now(),
                    ),
                )
            }
        }
    }

    override suspend fun removeAliasFromPerson(
        personUid: Uuid,
        alias: String,
    ) {
        withContext(dispatcher) {
            mediaClaimFence.withClaim {
                val existing = personDao.getById(personUid) ?: return@withClaim
                personDao.update(
                    existing.copy(
                        aliases = existing.aliases.filterNot { it.equals(alias, ignoreCase = true) },
                        lastUpdated = Clock.System.now(),
                    ),
                )
            }
        }
    }

    override fun observeImportedPeopleCount(origin: PersonOrigin): Flow<Int> = personDao.observeCountByOrigin(origin.name)

    /**
     * Adds [contacts] to the directory, or updates the people already imported from them.
     *
     * A contact whose person was removed from the directory is left out, so a later import does not
     * bring a removed person back.
     */
    override suspend fun importContacts(
        contacts: List<DeviceContact>,
        origin: PersonOrigin,
    ): ContactImportSummary =
        withContext(dispatcher) {
            var importedCount = 0
            var updatedCount = 0

            mediaClaimFence.withClaim {
                val removedLookupKeys = personDao.getRemovedContactLookupKeys().toSet()
                contacts.forEach { contact ->
                    val normalizedName = normalizePersonName(contact.displayName)
                    val normalizedLookupKey = normalizeOptionalText(contact.lookupKey)
                    if (normalizedName.isBlank() || normalizedLookupKey == null) return@forEach
                    if (normalizedLookupKey in removedLookupKeys) return@forEach

                    val normalizedAliases = normalizePersonAliases(contact.aliases, normalizedName)
                    val normalizedPhotoUri = normalizeOptionalText(contact.photoUri)
                    val existing = personDao.getByContactLookupKey(normalizedLookupKey)
                    val now = Clock.System.now()
                    if (existing == null) {
                        personDao.insert(
                            PersonEntity(
                                id = Uuid.random(),
                                name = normalizedName,
                                photoUri = normalizedPhotoUri,
                                aliases = normalizedAliases,
                                origin = origin.name,
                                contactLookupKey = normalizedLookupKey,
                                created = now,
                                lastUpdated = now,
                            ),
                        )
                        importedCount += 1
                        return@forEach
                    }
                    personDao.update(
                        existing.copy(
                            name = normalizedName,
                            photoUri = normalizedPhotoUri ?: existing.photoUri,
                            aliases = normalizePersonAliases(existing.aliases + normalizedAliases, normalizedName),
                            origin = mergeImportedPersonOrigin(existing.origin, origin).name,
                            lastUpdated = now,
                        ),
                    )
                    updatedCount += 1
                }
            }

            Napier.d("Imported ${importedCount + updatedCount} contacts into People ($origin)")
            ContactImportSummary(importedCount = importedCount, updatedCount = updatedCount)
        }
}
