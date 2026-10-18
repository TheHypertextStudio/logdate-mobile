package app.logdate.client.data.search

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.PostcardEntity
import app.logdate.client.database.entities.StickerEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.VideoNoteEntity
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.rewind.RewindEntity
import app.logdate.client.database.entities.rewind.RewindImageContentEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultKey
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OfflineFirstSearchResultDetailsRepositoryTest {
    private val file = Files.createTempFile("logdate-search-details-", ".db")
    private val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
    private val repository = OfflineFirstSearchResultDetailsRepository(database.searchDetailsDao(), database.personDao())
    private val created = Instant.parse("2025-07-19T15:00:00Z")

    @AfterTest
    fun tearDown() {
        database.close()
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
    }

    @Test
    fun `an empty page needs no lookups`() =
        runTest {
            assertEquals(emptyMap(), repository.describe(emptyList()))
        }

    @Test
    fun `photo and video captions describe their media`() =
        runTest {
            val image = Uuid.random()
            val video = Uuid.random()
            database.imageNoteDao().addNote(
                ImageNoteEntity(contentUri = "file:///a.jpg", uid = image, created = created, lastUpdated = created),
            )
            database.videoNoteDao().addNote(
                VideoNoteEntity(contentUri = "file:///b.mp4", durationMs = 5_000, uid = video, created = created, lastUpdated = created),
            )

            val details =
                repository.describe(
                    listOf(result(image, SearchContentType.MEDIA_CAPTION), result(video, SearchContentType.MEDIA_CAPTION)),
                )

            assertEquals(SearchResultDetail.Image("file:///a.jpg"), details[key(image, SearchContentType.MEDIA_CAPTION)])
            assertEquals(SearchResultDetail.Video("file:///b.mp4", 5_000), details[key(video, SearchContentType.MEDIA_CAPTION)])
        }

    @Test
    fun `transcripts and soundscapes of one recording each get its duration`() =
        runTest {
            val audio = Uuid.random()
            database.audioNoteDao().addNote(
                AudioNoteEntity(contentUri = "memo.m4a", durationMs = 48_000, uid = audio, created = created, lastUpdated = created),
            )

            val details =
                repository.describe(
                    listOf(result(audio, SearchContentType.TRANSCRIPTION), result(audio, SearchContentType.AMBIENT_SOUND)),
                )

            assertEquals(SearchResultDetail.Audio(48_000), details[key(audio, SearchContentType.TRANSCRIPTION)])
            assertEquals(SearchResultDetail.Audio(48_000), details[key(audio, SearchContentType.AMBIENT_SOUND)])
        }

    @Test
    fun `journals rewinds stickers postcards and places are described`() =
        runTest {
            val journal =
                JournalEntity(
                    title = "Lake Days",
                    description = "On the water",
                    created = created,
                    lastUpdated = created,
                    coverImageUri = "file:///cover.jpg",
                )
            database.journalDao().create(journal)
            database.journalContentDao().addContentToJournal(JournalContentEntityLink(journal.id, Uuid.random()))

            val rewind = Uuid.random()
            val start = Instant.parse("2025-06-21T00:00:00Z")
            val end = Instant.parse("2025-09-22T00:00:00Z")
            database.rewindDao().insertRewind(
                RewindEntity(uid = rewind, startDate = start, endDate = end, generationDate = end, label = "Summer", title = "Summer 2025"),
            )
            database.rewindDao().insertImageContent(
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

            val sticker = Uuid.random()
            database.stickerDao().insert(
                StickerEntity(
                    id = sticker,
                    sourcePhotoUri = "file:///p.jpg",
                    imageUri = "file:///s.png",
                    createdAt = created,
                    label = "Lake",
                ),
            )

            val postcard = Uuid.random()
            database.postcardDao().insert(
                PostcardEntity(id = postcard, title = "To Dad", createdAt = created, modifiedAt = end, documentJson = "{}"),
            )

            val place = Uuid.random()
            database.placeDao().insert(
                PlaceEntity(id = place, name = "Lake Mead", latitude = 36.1, longitude = -114.7, created = created, lastUpdated = created),
            )

            val details =
                repository.describe(
                    listOf(
                        result(journal.id, SearchContentType.JOURNAL),
                        result(rewind, SearchContentType.REWIND),
                        result(sticker, SearchContentType.STICKER),
                        result(postcard, SearchContentType.POSTCARD),
                        result(place, SearchContentType.PLACE),
                    ),
                )

            assertEquals(SearchResultDetail.Journal("file:///cover.jpg", 1), details[key(journal.id, SearchContentType.JOURNAL)])
            assertEquals(SearchResultDetail.Rewind(start, end, "file:///r.jpg"), details[key(rewind, SearchContentType.REWIND)])
            assertEquals(SearchResultDetail.Sticker("file:///s.png"), details[key(sticker, SearchContentType.STICKER)])
            assertEquals(SearchResultDetail.Postcard(null, end), details[key(postcard, SearchContentType.POSTCARD)])
            assertEquals(SearchResultDetail.Place(36.1, -114.7), details[key(place, SearchContentType.PLACE)])
        }

    @Test
    fun `people are described from their record`() =
        runTest {
            val person = Uuid.random()
            database.personDao().insert(
                PersonEntity(
                    id = person,
                    name = "Sam",
                    photoUri = "file:///sam.jpg",
                    relationshipLabel = "Friend",
                    origin = "MANUAL",
                    created = created,
                    lastUpdated = created,
                ),
            )

            val details = repository.describe(listOf(result(person, SearchContentType.PERSON)))

            assertEquals(SearchResultDetail.Person("file:///sam.jpg", "Friend"), details[key(person, SearchContentType.PERSON)])
        }

    @Test
    fun `text notes and unknown results have no detail`() =
        runTest {
            val note = Uuid.random()
            database.textNoteDao().addNote(TextNoteEntity(content = "A lake day", uid = note, created = created, lastUpdated = created))

            val details =
                repository.describe(
                    listOf(result(note, SearchContentType.TEXT_NOTE), result(Uuid.random(), SearchContentType.STICKER)),
                )

            assertTrue(details.isEmpty())
        }

    private fun result(
        uid: Uuid,
        type: SearchContentType,
    ) = SearchResult(uid = uid, content = "", created = created, contentType = type)

    private fun key(
        uid: Uuid,
        type: SearchContentType,
    ) = SearchResultKey(type, uid)
}
