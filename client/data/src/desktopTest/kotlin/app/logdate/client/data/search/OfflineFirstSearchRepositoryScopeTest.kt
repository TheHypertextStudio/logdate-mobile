package app.logdate.client.data.search

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OfflineFirstSearchRepositoryScopeTest {
    private val file = Files.createTempFile("logdate-search-scope-", ".db")
    private val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
    private val repository = OfflineFirstSearchRepository(database.searchDao(), database.personDao(), PeopleTurnedOn)

    private val samId = Uuid.random()
    private val mayaId = Uuid.random()
    private val lakeId = Uuid.random()
    private val linkedNote = Uuid.random()
    private val unlinkedNote = Uuid.random()
    private val created = Instant.parse("2025-07-19T15:00:00Z")

    @AfterTest
    fun tearDown() {
        database.close()
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
    }

    @Test
    fun `without a scope people and entries both appear`() =
        runTest {
            seed()

            val results = repository.searchScoped("sam", SearchScope()).first()

            assertEquals(setOf(samId, linkedNote, unlinkedNote), results.uids())
        }

    @Test
    fun `person scope keeps that person and only the entries linked to them`() =
        runTest {
            seed()

            val results = repository.searchScoped("sam", SearchScope(personId = samId)).first()

            assertEquals(setOf(samId, linkedNote), results.uids())
        }

    @Test
    fun `person scope for someone else hides this person`() =
        runTest {
            seed()

            val results = repository.searchScoped("sam", SearchScope(personId = mayaId)).first()

            assertEquals(emptySet(), results.uids())
        }

    @Test
    fun `place scope hides people and keeps entries at that place`() =
        runTest {
            seed()

            val results = repository.searchScoped("sam", SearchScope(placeId = lakeId)).first()

            assertEquals(setOf(linkedNote), results.uids())
        }

    @Test
    fun `date window hides people because they have no capture date`() =
        runTest {
            seed()

            val results =
                repository
                    .searchScoped(
                        "sam",
                        SearchScope(
                            createdFrom = Instant.parse("2025-01-01T00:00:00Z"),
                            createdBefore = Instant.parse("2026-01-01T00:00:00Z"),
                        ),
                    ).first()

            assertEquals(setOf(linkedNote, unlinkedNote), results.uids())
        }

    @Test
    fun `content type scope without people hides people`() =
        runTest {
            seed()

            val results = repository.searchScoped("sam", SearchScope(contentTypes = setOf(SearchContentType.TEXT_NOTE))).first()

            assertEquals(setOf(linkedNote, unlinkedNote), results.uids())
        }

    @Test
    fun `content type scope that includes people keeps them`() =
        runTest {
            seed()

            val results = repository.searchScoped("sam", SearchScope(contentTypes = setOf(SearchContentType.PERSON))).first()

            assertEquals(setOf(samId), results.uids())
        }

    @Test
    fun `blank query returns nothing`() =
        runTest {
            seed()

            assertEquals(emptyList(), repository.searchScoped(" ", SearchScope(personId = samId)).first())
        }

    private fun List<SearchResult>.uids(): Set<Uuid> = map { it.uid }.toSet()

    private suspend fun seed() {
        database.placeDao().insert(
            PlaceEntity(id = lakeId, name = "Lake Mead", latitude = 36.0, longitude = -114.7, created = created, lastUpdated = created),
        )
        database.personDao().insert(PersonEntity(id = samId, name = "Sam", origin = "MANUAL", created = created, lastUpdated = created))
        database.personDao().insert(PersonEntity(id = mayaId, name = "Maya", origin = "MANUAL", created = created, lastUpdated = created))
        database.textNoteDao().addNote(
            TextNoteEntity(content = "A day out with Sam", uid = linkedNote, created = created, lastUpdated = created, placeId = lakeId),
        )
        database.textNoteDao().addNote(
            TextNoteEntity(content = "Sam texted about dinner", uid = unlinkedNote, created = created, lastUpdated = created),
        )
        database.personLinkDao().insertAll(
            listOf(
                PersonLinkEntity(
                    id = Uuid.random(),
                    personId = samId,
                    targetType = "ENTRY",
                    targetId = linkedNote,
                    provenance = "EXPLICIT",
                    confidence = 1.0,
                    status = "ACTIVE",
                    created = created,
                    lastUpdated = created,
                ),
            ),
        )
    }

    /** People search results appear only while People is turned on in Labs. */
    private object PeopleTurnedOn : FeatureFlagStore {
        override fun observe(flag: FeatureFlag): Flow<Boolean> = flowOf(flag == FeatureFlag.PEOPLE)

        override suspend fun setEnabled(
            flag: FeatureFlag,
            enabled: Boolean,
        ) = Unit
    }
}
