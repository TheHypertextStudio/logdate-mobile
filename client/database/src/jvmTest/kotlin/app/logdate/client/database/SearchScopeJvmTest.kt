package app.logdate.client.database

import androidx.room.Room
import app.logdate.client.database.dao.RankedSearchResultEntity
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies that person, place, content type and date narrowing is applied inside the search
 * query, before the result limit, so narrowing never hides matches that a limit would have cut.
 */
class SearchScopeJvmTest {
    private var database: LogDateDatabase? = null
    private var databasePath = Files.createTempFile("logdate-search-scope", ".db")

    private val lake = Uuid.random()
    private val home = Uuid.random()
    private val sam = Uuid.random()

    private val summer2025 = Instant.parse("2025-07-19T15:00:00Z")
    private val winter2024 = Instant.parse("2024-01-05T15:00:00Z")
    private val summerNext = Instant.parse("2025-07-20T15:00:00Z")

    @AfterTest
    fun tearDown() {
        database?.close()
        database = null
        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-wal"))
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-shm"))
    }

    @Test
    fun `unscoped search returns every match`() =
        runTest {
            val fixture = seed()

            val results = scoped("lake*")

            assertEquals(
                setOf(fixture.sunnyNote, fixture.winterNote, fixture.homeNote, fixture.transcript, fixture.lakeName, fixture.journal),
                results.uids(),
            )
        }

    @Test
    fun `place scope keeps notes recordings and the place itself and drops other places`() =
        runTest {
            val fixture = seed()

            val results = scoped("lake*", placeId = lake)

            assertEquals(
                setOf(fixture.sunnyNote, fixture.winterNote, fixture.transcript, fixture.lakeName),
                results.uids(),
            )
        }

    @Test
    fun `person scope keeps only active links to that person`() =
        runTest {
            val fixture = seed()

            val results = scoped("lake*", personId = sam)

            assertEquals(setOf(fixture.sunnyNote, fixture.transcript), results.uids())
        }

    @Test
    fun `person and place scopes combine`() =
        runTest {
            val fixture = seed()

            val results = scoped("lake*", personId = sam, placeId = lake)

            assertEquals(setOf(fixture.sunnyNote, fixture.transcript), results.uids())
        }

    @Test
    fun `date window keeps results created inside it`() =
        runTest {
            val fixture = seed()

            val results =
                scoped(
                    "lake*",
                    createdFrom = Instant.parse("2025-01-01T00:00:00Z"),
                    createdBefore = Instant.parse("2026-01-01T00:00:00Z"),
                )

            assertEquals(
                setOf(fixture.sunnyNote, fixture.homeNote, fixture.transcript, fixture.lakeName, fixture.journal),
                results.uids(),
            )
        }

    @Test
    fun `content type scope keeps only the requested types`() =
        runTest {
            val fixture = seed()

            val results = scoped("lake*", types = listOf("text_note"))

            assertEquals(setOf(fixture.sunnyNote, fixture.winterNote, fixture.homeNote), results.uids())
        }

    @Test
    fun `scope is applied before the limit`() =
        runTest {
            val fixture = seed()
            val dao = requireNotNull(database).textNoteDao()
            repeat(5) { index ->
                dao.addNote(
                    TextNoteEntity(
                        content = "lake lake lake lake lake $index",
                        uid = Uuid.random(),
                        created = summer2025,
                        lastUpdated = summer2025,
                        placeId = lake,
                    ),
                )
            }

            val results = scoped("lake*", placeId = home, limit = 1)

            assertEquals(setOf(fixture.homeNote), results.uids())
        }

    @Test
    fun `type counts cover every indexed type that matches`() =
        runTest {
            seed()

            assertEquals(
                mapOf("text_note" to 3, "transcription" to 1, "journal" to 1, "place" to 1),
                typeCounts("lake*"),
            )
        }

    @Test
    fun `type counts follow the other narrowing`() =
        runTest {
            seed()

            assertEquals(
                mapOf("text_note" to 2, "transcription" to 1, "place" to 1),
                typeCounts("lake*", placeId = lake),
            )
        }

    @Test
    fun `place counts count entries at each place and not the places themselves`() =
        runTest {
            seed()

            assertEquals(mapOf(lake.toString() to 3, home.toString() to 1), placeCounts("lake*"))
        }

    @Test
    fun `place counts follow person narrowing and skip places with no matches`() =
        runTest {
            seed()

            assertEquals(mapOf(lake.toString() to 2), placeCounts("lake*", personId = sam))
        }

    @Test
    fun `person counts include only active links`() =
        runTest {
            seed()

            assertEquals(mapOf(sam.toString() to 2), personCounts("lake*"))
        }

    @Test
    fun `person counts follow place narrowing`() =
        runTest {
            seed()

            assertEquals(emptyMap(), personCounts("lake*", placeId = home))
        }

    @Test
    fun `created times list every match inside the narrowing`() =
        runTest {
            seed()

            val all = createdTimes("lake*")
            val withinYear =
                createdTimes(
                    "lake*",
                    createdFrom = Instant.parse("2025-01-01T00:00:00Z"),
                    createdBefore = Instant.parse("2026-01-01T00:00:00Z"),
                )

            assertEquals(6, all.size)
            assertEquals(5, withinYear.size)
            assertEquals(winter2024.toEpochMilliseconds(), all.min())
        }

    private suspend fun typeCounts(
        query: String,
        personId: Uuid? = null,
        placeId: Uuid? = null,
    ): Map<String, Int> =
        requireNotNull(database)
            .searchDao()
            .countByContentType(query, false, listOf(""), personId?.toString(), placeId?.toString(), null, null)
            .associate { it.contentType to it.total }

    private suspend fun placeCounts(
        query: String,
        personId: Uuid? = null,
    ): Map<String, Int> =
        requireNotNull(database)
            .searchDao()
            .countByPlace(query, false, listOf(""), personId?.toString(), null, null, null)
            .associate { it.placeId to it.total }

    private suspend fun personCounts(
        query: String,
        placeId: Uuid? = null,
    ): Map<String, Int> =
        requireNotNull(database)
            .searchDao()
            .countByPerson(query, false, listOf(""), null, placeId?.toString(), null, null)
            .associate { it.personId to it.total }

    private suspend fun createdTimes(
        query: String,
        createdFrom: Instant? = null,
        createdBefore: Instant? = null,
    ): List<Long> =
        requireNotNull(database)
            .searchDao()
            .findCreatedTimes(
                query,
                false,
                listOf(""),
                null,
                null,
                createdFrom?.toEpochMilliseconds(),
                createdBefore?.toEpochMilliseconds(),
            )

    private suspend fun scoped(
        query: String,
        types: List<String>? = null,
        personId: Uuid? = null,
        placeId: Uuid? = null,
        createdFrom: Instant? = null,
        createdBefore: Instant? = null,
        limit: Int = 50,
    ): List<RankedSearchResultEntity> =
        requireNotNull(database).searchDao().searchScoped(
            query = query,
            typesEnabled = types != null,
            types = types ?: listOf(""),
            personId = personId?.toString(),
            placeId = placeId?.toString(),
            createdFrom = createdFrom?.toEpochMilliseconds(),
            createdBefore = createdBefore?.toEpochMilliseconds(),
            limit = limit,
        )

    private fun List<RankedSearchResultEntity>.uids(): Set<String> = map { it.uid }.toSet()

    private class Fixture(
        val sunnyNote: String,
        val winterNote: String,
        val homeNote: String,
        val transcript: String,
        val lakeName: String,
        val journal: String,
    )

    private suspend fun seed(): Fixture {
        val db = openDatabase()
        val now = summer2025

        db.placeDao().insert(
            PlaceEntity(id = lake, name = "Lake Mead", latitude = 36.0, longitude = -114.7, created = now, lastUpdated = now),
        )
        db.placeDao().insert(PlaceEntity(id = home, name = "Home", latitude = 36.1, longitude = -115.1, created = now, lastUpdated = now))
        db.personDao().insert(PersonEntity(id = sam, name = "Sam", origin = "MANUAL", created = now, lastUpdated = now))

        val sunny =
            TextNoteEntity(
                content = "A lake day with Sam",
                uid = Uuid.random(),
                created = summer2025,
                lastUpdated = summer2025,
                placeId = lake,
            )
        val winter =
            TextNoteEntity(
                content = "The lake in winter",
                uid = Uuid.random(),
                created = winter2024,
                lastUpdated = winter2024,
                placeId = lake,
            )
        val homeNote =
            TextNoteEntity(
                content = "Planning the next lake trip",
                uid = Uuid.random(),
                created = summerNext,
                lastUpdated = summerNext,
                placeId = home,
            )
        listOf(sunny, winter, homeNote).forEach { db.textNoteDao().addNote(it) }

        val audio =
            AudioNoteEntity(contentUri = "story.m4a", uid = Uuid.random(), created = summer2025, lastUpdated = summer2025, placeId = lake)
        db.audioNoteDao().addNote(audio)
        db.transcriptionDao().insertTranscription(
            TranscriptionEntity(
                id = Uuid.random(),
                noteId = audio.uid,
                text = "A story about the lake",
                status = TranscriptionStatus.COMPLETED,
                created = summer2025,
                lastUpdated = summer2025,
            ),
        )

        val journal = JournalEntity(title = "Lake Days", description = "Afternoons on the water", created = now, lastUpdated = now)
        db.journalDao().create(journal)

        db.personLinkDao().insertAll(
            listOf(
                link(sam, sunny.uid, "ACTIVE"),
                link(sam, audio.uid, "ACTIVE"),
                link(sam, homeNote.uid, "REJECTED"),
            ),
        )

        return Fixture(
            sunnyNote = sunny.uid.toString(),
            winterNote = winter.uid.toString(),
            homeNote = homeNote.uid.toString(),
            transcript = audio.uid.toString(),
            lakeName = lake.toString(),
            journal = journal.id.toString(),
        )
    }

    private fun link(
        personId: Uuid,
        targetId: Uuid,
        status: String,
    ) = PersonLinkEntity(
        id = Uuid.random(),
        personId = personId,
        targetType = "ENTRY",
        targetId = targetId,
        provenance = "EXPLICIT",
        confidence = 1.0,
        status = status,
        created = summer2025,
        lastUpdated = summer2025,
    )

    private fun openDatabase(): LogDateDatabase {
        database?.close()
        Files.deleteIfExists(databasePath)
        databasePath = Files.createTempFile("logdate-search-scope", ".db")
        Files.deleteIfExists(databasePath)

        return getRoomDatabase(
            builder = Room.databaseBuilder<LogDateDatabase>(databasePath.absolutePathString()),
        ).also { database = it }
    }
}
