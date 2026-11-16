package app.logdate.client.data.search

import app.logdate.client.data.people.addTextEntry
import app.logdate.client.data.people.journalDay
import app.logdate.client.data.people.withJournalDatabase
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.repository.search.SearchContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * People is an opt-in feature in Labs whose directory stays stored while it is off. Search feeds
 * the search screen, the journal search fields and the assistant, so a person in the directory
 * comes back from it only while People is on.
 */
class SearchLeavesOutPeopleTest {
    @Test
    fun `a person in the directory is not a search result while People is off and the entries that name them still are`() =
        withJournalDatabase { database ->
            val entry = database.addPatel()
            val search = search(database, peopleEnabled = false)

            val byName =
                listOf(
                    search.search("Patel"),
                    search.searchWithLimit("Patel", 10),
                    search.searchWithSnippets("Patel"),
                    search.searchRanked("Patel", 10),
                ).map { it.first() }
            val byAlias = search.searchWithLimit("Doc", 10).first()
            val inJournal = search.searchInJournal("Patel", Uuid.random(), 10).first()

            byName.forEach { results ->
                assertEquals(listOf(entry to SearchContentType.TEXT_NOTE), results.map { it.uid to it.contentType })
            }
            assertEquals(emptyList(), byAlias)
            assertEquals(emptyList(), inJournal)
        }

    @Test
    fun `a person in the directory is a search result by name and alias while People is on`() =
        withJournalDatabase { database ->
            val entry = database.addPatel()
            val search = search(database, peopleEnabled = true)

            val byName = search.searchWithLimit("Patel", 10).first()
            val byAlias = search.searchWithLimit("Doc", 10).first()

            assertEquals(
                setOf(SearchContentType.PERSON, SearchContentType.TEXT_NOTE),
                byName.mapTo(mutableSetOf()) { it.contentType },
            )
            assertEquals(entry, byName.single { it.contentType == SearchContentType.TEXT_NOTE }.uid)
            assertEquals(listOf(SearchContentType.PERSON), byAlias.map { it.contentType })
        }

    private fun search(
        database: LogDateDatabase,
        peopleEnabled: Boolean,
    ) = OfflineFirstSearchRepository(database.searchDao(), database.personDao(), FlagStore(peopleEnabled))

    private suspend fun LogDateDatabase.addPatel(): Uuid {
        personDao().insert(
            PersonEntity(
                id = Uuid.random(),
                name = "Patel",
                aliases = listOf("Doc"),
                origin = "CONTACT_FULL",
                created = journalDay,
                lastUpdated = journalDay,
            ),
        )
        return addTextEntry("Saw Patel about the results")
    }

    private class FlagStore(
        enabled: Boolean,
    ) : FeatureFlagStore {
        val peopleEnabled = MutableStateFlow(enabled)

        override fun observe(flag: FeatureFlag): Flow<Boolean> = if (flag == FeatureFlag.PEOPLE) peopleEnabled else MutableStateFlow(false)

        override suspend fun setEnabled(
            flag: FeatureFlag,
            enabled: Boolean,
        ) {
            if (flag == FeatureFlag.PEOPLE) peopleEnabled.value = enabled
        }
    }
}
