package app.logdate.client.database

import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.AudioTagEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for the search indexing logic on the JVM.
 *
 * These tests verify that journal entries, transcriptions, and ambient sound
 * tags are correctly indexed in the database for full-text search (FTS),
 * and that indexed content is properly updated when the source data changes.
 */
class SearchIndexBootstrapperJvmTest {
    private var database: LogDateDatabase? = null
    private var databasePath = Files.createTempFile("logdate-search-index", ".db")

    @AfterTest
    fun tearDown() {
        database?.close()
        database = null

        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-wal"))
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-shm"))
    }

    @Test
    fun `fresh database indexes inserted text notes`() =
        runTest {
            val database = openDatabase()
            val note =
                TextNoteEntity(
                    content = "Hiked the sunrise trail before breakfast.",
                    uid = Uuid.random(),
                    created = Instant.fromEpochMilliseconds(1_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_000),
                )

            database.textNoteDao().addNote(note)

            val results = database.searchDao().searchRanked("hik*", limit = 10)

            assertEquals(1, results.size)
            assertEquals(note.uid.toString(), results.single().uid)
            assertTrue(database.searchDao().observeGeneration().first() ?: 0L > 0L)
        }

    @Test
    fun `transcription search uses source audio note date and deletes with source note`() =
        runTest {
            val database = openDatabase()
            val audioNote =
                AudioNoteEntity(
                    contentUri = "memo.m4a",
                    uid = Uuid.random(),
                    created = Instant.fromEpochMilliseconds(10_000),
                    lastUpdated = Instant.fromEpochMilliseconds(10_000),
                )
            val transcription =
                TranscriptionEntity(
                    id = Uuid.random(),
                    noteId = audioNote.uid,
                    text = "Adventures on the coastal trail",
                    status = TranscriptionStatus.COMPLETED,
                    created = Instant.fromEpochMilliseconds(20_000),
                    lastUpdated = Instant.fromEpochMilliseconds(25_000),
                )

            database.audioNoteDao().addNote(audioNote)
            database.transcriptionDao().insertTranscription(transcription)

            val initialResults = database.searchDao().searchRanked("advent*", limit = 10)

            assertEquals(1, initialResults.size)
            assertEquals(audioNote.created.toEpochMilliseconds(), initialResults.single().created)

            database.audioNoteDao().removeNote(audioNote.uid)

            val afterDelete = database.searchDao().searchRanked("advent*", limit = 10)
            assertTrue(afterDelete.isEmpty())
        }

    @Test
    fun `ambient sound tags are searchable and cascade with the parent audio note`() =
        runTest {
            val database = openDatabase()
            val audioNote =
                AudioNoteEntity(
                    contentUri = "rainwalk.m4a",
                    uid = Uuid.random(),
                    created = Instant.fromEpochMilliseconds(30_000),
                    lastUpdated = Instant.fromEpochMilliseconds(30_000),
                )
            val rainTag =
                AudioTagEntity(
                    id = Uuid.random(),
                    noteId = audioNote.uid,
                    soundName = "Rain",
                    confidence = 0.92f,
                    startMs = 0L,
                    durationMs = 12_000L,
                    created = Instant.fromEpochMilliseconds(31_000),
                )
            val birdsTag =
                AudioTagEntity(
                    id = Uuid.random(),
                    noteId = audioNote.uid,
                    soundName = "Bird",
                    confidence = 0.71f,
                    startMs = 4_000L,
                    durationMs = 6_000L,
                    created = Instant.fromEpochMilliseconds(31_500),
                )

            database.audioNoteDao().addNote(audioNote)
            database.audioTagDao().insertTags(listOf(rainTag, birdsTag))

            val rainResults = database.searchDao().searchRanked("rain", limit = 10)
            assertEquals(1, rainResults.size)
            assertEquals(audioNote.uid.toString(), rainResults.single().uid)
            assertEquals("ambient_sound", rainResults.single().contentType)

            val birdResults = database.searchDao().searchRanked("bird", limit = 10)
            assertEquals(1, birdResults.size)
            assertEquals(audioNote.uid.toString(), birdResults.single().uid)

            database.audioNoteDao().removeNote(audioNote.uid)

            assertTrue(database.searchDao().searchRanked("rain", limit = 10).isEmpty())
            assertTrue(database.searchDao().searchRanked("bird", limit = 10).isEmpty())
        }

    @Test
    fun `opening a database with an outdated index leaves the rebuild to a later call`() =
        runTest {
            val first = openDatabase()
            val note =
                TextNoteEntity(
                    content = "Watched the herons at the marsh.",
                    uid = Uuid.random(),
                    created = Instant.fromEpochMilliseconds(1_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_000),
                )
            first.textNoteDao().addNote(note)
            first.useWriterConnection { connection ->
                connection.execSQL("DELETE FROM entries_fts")
                connection.execSQL("UPDATE search_index_metadata SET schemaVersion = 4 WHERE id = 1")
            }
            first.close()
            database = null

            val search = reopenDatabase().searchDao()

            assertTrue(search.searchRanked("heron*", limit = 10).isEmpty(), "the database rebuilt the index while opening")
            assertTrue(search.rebuildIfNeeded())
            val found = search.searchRanked("heron*", limit = 10)
            assertEquals(listOf(note.uid.toString()), found.map { it.uid })
            assertFalse(search.rebuildIfNeeded(), "a current index was rebuilt again")
        }

    @Test
    fun `unbounded searches return at most the requested number of matches`() =
        runTest {
            val database = openDatabase()
            repeat(30) { index ->
                database.textNoteDao().addNote(
                    TextNoteEntity(
                        content = "Lake swim number $index",
                        uid = Uuid.random(),
                        created = Instant.fromEpochMilliseconds(1_000L + index),
                        lastUpdated = Instant.fromEpochMilliseconds(1_000L + index),
                    ),
                )
            }

            assertEquals(12, database.searchDao().search("lake*", limit = 12).size)
            assertEquals(12, database.searchDao().searchWithSnippets("lake*", limit = 12).size)
        }

    private fun openDatabase(): LogDateDatabase {
        database?.close()
        Files.deleteIfExists(databasePath)
        databasePath = Files.createTempFile("logdate-search-index", ".db")
        Files.deleteIfExists(databasePath)

        return reopenDatabase()
    }

    private fun reopenDatabase(): LogDateDatabase =
        getRoomDatabase(
            builder = Room.databaseBuilder<LogDateDatabase>(databasePath.absolutePathString()),
        ).also { database = it }
}
