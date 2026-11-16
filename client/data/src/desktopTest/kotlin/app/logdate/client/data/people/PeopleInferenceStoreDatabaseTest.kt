package app.logdate.client.data.people

import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.dao.people.PersonLinkDao
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * A confirmation, a dismissal and a removal each write several tables. Against the real database,
 * one that is interrupted leaves every table as it was, and can then be repeated.
 */
class PeopleInferenceStoreDatabaseTest {
    @Test
    fun `an interrupted confirmation adds nothing and can be repeated`() =
        withJournalDatabase { database ->
            val first = database.addTextEntry("Dinner with Maya")
            val second = database.addTextEntry("Long call with Maya tonight")
            store(database).refresh()
            val suggestion = database.openSuggestions().single()

            assertFailsWith<IllegalStateException> {
                store(database, linkDao = LinksThatCannotBeWritten(database.personLinkDao())).confirmClusterAsPerson(suggestion.id)
            }

            assertEquals(emptyList(), database.personDao().getAll())
            assertEquals(listOf(suggestion), database.openSuggestions())
            assertEquals(2, database.inferredPersonEvidenceDao().getForClusters(listOf(suggestion.id)).size)
            assertEquals(emptyList(), database.personResolutionDecisionDao().getAll())

            store(database).confirmClusterAsPerson(suggestion.id)

            val person = database.personDao().getAll().single()
            assertEquals("Maya", person.name)
            assertEquals(setOf(first, second), database.linkedEntries(person.id))
            assertEquals(emptyList(), database.openSuggestions())
        }

    @Test
    fun `an interrupted removal keeps the person and writes no decision`() =
        withJournalDatabase { database ->
            val samantha = person("Samantha Lee", aliases = listOf("Sam"))
            database.personDao().insert(samantha)

            assertFailsWith<IllegalStateException> {
                store(database, linkDao = LinksThatCannotBeWritten(database.personLinkDao())).removePerson(samantha.id)
            }

            assertEquals(samantha, database.personDao().getById(samantha.id))
            assertEquals(emptyList(), database.personResolutionDecisionDao().getAll())
        }

    @Test
    fun `removing a person records every one of their names and hides them`() =
        withJournalDatabase { database ->
            val samantha = person("Samantha Lee", aliases = listOf("Sam"))
            database.personDao().insert(samantha)
            database.addTextEntry("texted Sam about the weekend")
            database.addTextEntry("coffee with Sam")

            store(database).removePerson(samantha.id)
            store(database).refresh()

            assertNull(database.personDao().getById(samantha.id))
            assertEquals(
                setOf("sam" to "SUPPRESS", "samantha lee" to "SUPPRESS"),
                database
                    .personResolutionDecisionDao()
                    .getAll()
                    .map { it.normalizedName to it.action }
                    .toSet(),
            )
            assertEquals(emptyList(), database.openSuggestions())
        }

    private fun store(
        database: LogDateDatabase,
        linkDao: PersonLinkDao = database.personLinkDao(),
    ) = PeopleInferenceStore(
        personDao = database.personDao(),
        clusterDao = database.inferredPersonClusterDao(),
        evidenceDao = database.inferredPersonEvidenceDao(),
        linkDao = linkDao,
        decisionDao = database.personResolutionDecisionDao(),
        textNoteDao = database.textNoteDao(),
        audioNoteDao = database.audioNoteDao(),
        transcriptionDao = database.transcriptionDao(),
        eventDao = database.eventDao(),
        transactions = RoomSyncTransactionManager(database),
        dispatcher = Dispatchers.Default,
        readsEvents = false,
    )

    private suspend fun LogDateDatabase.openSuggestions() = inferredPersonClusterDao().getByStatus(InferredPersonClusterStatus.OPEN.name)

    private suspend fun LogDateDatabase.linkedEntries(personId: Uuid): Set<Uuid> =
        personLinkDao().observeForPerson(personId).first().mapTo(mutableSetOf()) { it.targetId }

    private fun person(
        name: String,
        aliases: List<String>,
    ) = PersonEntity(
        id = Uuid.random(),
        name = name,
        aliases = aliases,
        origin = PersonOrigin.CONTACT_FULL.name,
        created = journalDay,
        lastUpdated = journalDay,
    )
}

/** Fails every write to the links table, the way a full disk or a closed database would. */
private class LinksThatCannotBeWritten(
    private val links: PersonLinkDao,
) : PersonLinkDao by links {
    override suspend fun insertAll(links: List<PersonLinkEntity>): Unit = error("links cannot be written")

    override suspend fun deleteForPerson(personId: Uuid): Unit = error("links cannot be written")
}
