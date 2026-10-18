package app.logdate.client.database

import androidx.room.Room
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.PostcardEntity
import app.logdate.client.database.entities.StickerEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.VideoNoteEntity
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.database.entities.rewind.RewindEntity
import app.logdate.client.database.entities.rewind.RewindImageContentEntity
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Verifies the batched lookups that describe search results with their media, dates and covers. */
class SearchDetailsDaoJvmTest {
    private var database: LogDateDatabase? = null
    private var databasePath = Files.createTempFile("logdate-search-details", ".db")
    private val created = Instant.parse("2025-07-19T15:00:00Z")

    @AfterTest
    fun tearDown() {
        database?.close()
        database = null
        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-wal"))
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-shm"))
    }

    @Test
    fun `image and video notes return their media uri and video length`() =
        runTest {
            val db = openDatabase()
            val image = Uuid.random()
            val video = Uuid.random()
            db.imageNoteDao().addNote(ImageNoteEntity(contentUri = "file:///a.jpg", uid = image, created = created, lastUpdated = created))
            db.videoNoteDao().addNote(
                VideoNoteEntity(contentUri = "file:///b.mp4", durationMs = 5_000, uid = video, created = created, lastUpdated = created),
            )
            val dao = db.searchDetailsDao()

            assertEquals(
                listOf(image.toString() to "file:///a.jpg"),
                dao.imageDetails(listOf(image.toString(), video.toString())).map {
                    it.uid to
                        it.contentUri
                },
            )
            val videos = dao.videoDetails(listOf(image.toString(), video.toString()))
            assertEquals(listOf(video.toString() to "file:///b.mp4"), videos.map { it.uid to it.contentUri })
            assertEquals(5_000L, videos.single().durationMs)
        }

    @Test
    fun `deleted media is not described`() =
        runTest {
            val db = openDatabase()
            val image = Uuid.random()
            db.imageNoteDao().addNote(ImageNoteEntity(contentUri = "file:///a.jpg", uid = image, created = created, lastUpdated = created))
            db.imageNoteDao().removeNote(image)

            assertTrue(db.searchDetailsDao().imageDetails(listOf(image.toString())).isEmpty())
        }

    @Test
    fun `audio notes return their duration`() =
        runTest {
            val db = openDatabase()
            val audio = Uuid.random()
            db.audioNoteDao().addNote(
                AudioNoteEntity(contentUri = "memo.m4a", durationMs = 48_000, uid = audio, created = created, lastUpdated = created),
            )

            val rows = db.searchDetailsDao().audioDetails(listOf(audio.toString()))

            assertEquals(listOf(audio.toString() to 48_000L), rows.map { it.uid to it.durationMs })
        }

    @Test
    fun `journals return their cover and entry count`() =
        runTest {
            val db = openDatabase()
            val journal =
                JournalEntity(
                    title = "Lake Days",
                    description = "On the water",
                    created = created,
                    lastUpdated = created,
                    coverImageUri = "file:///cover.jpg",
                )
            val bare = JournalEntity(title = "Empty", description = "", created = created, lastUpdated = created)
            db.journalDao().create(journal)
            db.journalDao().create(bare)
            db.journalContentDao().addContentToJournal(JournalContentEntityLink(journal.id, Uuid.random()))
            db.journalContentDao().addContentToJournal(JournalContentEntityLink(journal.id, Uuid.random()))

            val rows = db.searchDetailsDao().journalDetails(listOf(journal.id.toString(), bare.id.toString())).associateBy { it.id }

            assertEquals("file:///cover.jpg", rows.getValue(journal.id.toString()).coverUri)
            assertEquals(2, rows.getValue(journal.id.toString()).entryCount)
            assertEquals(null, rows.getValue(bare.id.toString()).coverUri)
            assertEquals(0, rows.getValue(bare.id.toString()).entryCount)
        }

    @Test
    fun `rewinds return their dates and the first image as a cover`() =
        runTest {
            val db = openDatabase()
            val rewind = Uuid.random()
            val start = Instant.parse("2025-06-21T00:00:00Z")
            val end = Instant.parse("2025-09-22T00:00:00Z")
            db.rewindDao().insertRewind(
                RewindEntity(uid = rewind, startDate = start, endDate = end, generationDate = end, label = "Summer", title = "Summer 2025"),
            )
            db.rewindDao().insertImageContent(
                listOf(
                    RewindImageContentEntity(
                        id = Uuid.random(),
                        rewindId = rewind,
                        sourceId = Uuid.random(),
                        timestamp = start,
                        uri = "file:///r.jpg",
                        caption = null,
                    ),
                ),
            )

            val row = db.searchDetailsDao().rewindDetails(listOf(rewind.toString())).single()

            assertEquals(start.toEpochMilliseconds(), row.startDate)
            assertEquals(end.toEpochMilliseconds(), row.endDate)
            assertEquals("file:///r.jpg", row.coverUri)
        }

    @Test
    fun `stickers return their art`() =
        runTest {
            val db = openDatabase()
            val sticker = Uuid.random()
            db.stickerDao().insert(
                StickerEntity(
                    id = sticker,
                    sourcePhotoUri = "file:///p.jpg",
                    imageUri = "file:///s.png",
                    createdAt = created,
                    label = "Lake",
                ),
            )

            assertEquals(
                "file:///s.png",
                db
                    .searchDetailsDao()
                    .stickerDetails(listOf(sticker.toString()))
                    .single()
                    .imageUri,
            )
        }

    @Test
    fun `postcards use the photo they were made from as a cover`() =
        runTest {
            val db = openDatabase()
            val photo = Uuid.random()
            db.imageNoteDao().addNote(
                ImageNoteEntity(contentUri = "file:///front.jpg", uid = photo, created = created, lastUpdated = created),
            )
            val withPhoto =
                PostcardEntity(
                    id = Uuid.random(),
                    title = "To Dad",
                    createdAt = created,
                    modifiedAt = created,
                    sourceMomentRef = photo,
                    documentJson = "{}",
                )
            val without =
                PostcardEntity(id = Uuid.random(), title = "Hello", createdAt = created, modifiedAt = created, documentJson = "{}")
            db.postcardDao().insert(withPhoto)
            db.postcardDao().insert(without)

            val rows = db.searchDetailsDao().postcardDetails(listOf(withPhoto.id.toString(), without.id.toString())).associateBy { it.id }

            assertEquals("file:///front.jpg", rows.getValue(withPhoto.id.toString()).coverUri)
            assertEquals(null, rows.getValue(without.id.toString()).coverUri)
        }

    @Test
    fun `places return their coordinates`() =
        runTest {
            val db = openDatabase()
            val place = Uuid.random()
            db.placeDao().insert(
                PlaceEntity(id = place, name = "Lake Mead", latitude = 36.1, longitude = -114.7, created = created, lastUpdated = created),
            )

            val row = db.searchDetailsDao().placeDetails(listOf(place.toString())).single()

            assertEquals(36.1, row.latitude)
            assertEquals(-114.7, row.longitude)
        }

    @Test
    fun `place names are returned for the places asked about and skip deleted places`() =
        runTest {
            val db = openDatabase()
            val lake = Uuid.random()
            val gone = Uuid.random()
            db.placeDao().insert(
                PlaceEntity(id = lake, name = "Lake Mead", latitude = 36.1, longitude = -114.7, created = created, lastUpdated = created),
            )
            db.placeDao().insert(
                PlaceEntity(
                    id = gone,
                    name = "Old place",
                    latitude = 1.0,
                    longitude = 2.0,
                    created = created,
                    lastUpdated = created,
                    deletedAt = created,
                ),
            )

            val rows = db.searchDetailsDao().placeNames(listOf(lake.toString(), gone.toString()))

            assertEquals(listOf(lake.toString() to "Lake Mead"), rows.map { it.id to it.name })
        }

    @Test
    fun `a journal preview returns its title, description, creation and cover`() =
        runTest {
            val db = openDatabase()
            val journal =
                JournalEntity(
                    title = "Lake Days",
                    description = "On the water",
                    created = created,
                    lastUpdated = created,
                    coverImageUri = "file:///cover.jpg",
                )
            db.journalDao().create(journal)

            val row = db.searchDetailsDao().journalPreview(journal.id.toString())

            assertEquals("Lake Days", row?.title)
            assertEquals("On the water", row?.description)
            assertEquals(created.toEpochMilliseconds(), row?.created)
            assertEquals("file:///cover.jpg", row?.coverUri)
        }

    @Test
    fun `a deleted or unknown journal has no preview`() =
        runTest {
            val db = openDatabase()
            val journal = JournalEntity(title = "Gone", description = "", created = created, lastUpdated = created)
            db.journalDao().create(journal)
            db.journalDao().delete(journal.id)

            assertEquals(null, db.searchDetailsDao().journalPreview(journal.id.toString()))
            assertEquals(null, db.searchDetailsDao().journalPreview(Uuid.random().toString()))
        }

    @Test
    fun `the latest entries of a journal are its newest text notes, newest first, without deleted ones`() =
        runTest {
            val db = openDatabase()
            val journal = JournalEntity(title = "Lake Days", description = "", created = created, lastUpdated = created)
            db.journalDao().create(journal)
            val oldest = textNote("first", created)
            val middle = textNote("second", created.plus(1.days))
            val newest = textNote("third", created.plus(2.days))
            val deleted = textNote("deleted", created.plus(3.days))
            val elsewhere = textNote("another journal", created.plus(4.days))
            listOf(oldest, middle, newest, deleted, elsewhere).forEach { db.textNoteDao().addNote(it) }
            listOf(oldest, middle, newest, deleted).forEach {
                db.journalContentDao().addContentToJournal(JournalContentEntityLink(journal.id, it.uid))
            }
            db.textNoteDao().removeNote(deleted.uid)

            val latest = db.searchDetailsDao().latestJournalEntries(journal.id.toString(), limit = 2)

            assertEquals(listOf("third", "second"), latest.map { it.content })
            assertEquals(listOf(newest.uid.toString(), middle.uid.toString()), latest.map { it.uid })
            assertEquals(created.plus(2.days).toEpochMilliseconds(), latest.first().created)
        }

    private fun textNote(
        content: String,
        at: Instant,
    ) = TextNoteEntity(content = content, uid = Uuid.random(), created = at, lastUpdated = at)

    private fun openDatabase(): LogDateDatabase {
        database?.close()
        Files.deleteIfExists(databasePath)
        databasePath = Files.createTempFile("logdate-search-details", ".db")
        Files.deleteIfExists(databasePath)

        return getRoomDatabase(
            builder = Room.databaseBuilder<LogDateDatabase>(databasePath.absolutePathString()),
        ).also { database = it }
    }
}
