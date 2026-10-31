package app.logdate.client.data.notes

import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import app.logdate.client.data.fakes.FakeJournalRepository
import app.logdate.client.data.fakes.FakeSyncMetadataService
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.sync.RoomSyncTransactionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Archive metadata is durable only after the real repository and database reopen it. */
class RestoredScalarPersistenceTest {
    private val directory = Files.createTempDirectory("logdate-restored-scalar-")
    private var database = openDatabase()
    private var repository = repository()
    private val now = Instant.parse("2026-09-18T03:30:05Z")

    @AfterTest
    fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun legacyTranscriptionSurvivesDatabaseReopenAndCanBeCleared() =
        runTest {
            val note =
                JournalNote.Audio(
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "logdate-unavailable://archive/scalar-audio",
                    transcription = "  Original words.\nKeep spacing.  ",
                )
            assertTrue(repository.compareAndSetNoteForRestore(null, note))
            reopen()
            val stored = assertIs<JournalNote.Audio>(repository.getNoteById(note.uid))
            assertEquals(note.transcription, stored.transcription)
            assertTrue(repository.compareAndSetNoteForRestore(stored, stored.copy(transcription = "", lastUpdated = now + 1.seconds)))
            reopen()
            assertEquals("", assertIs<JournalNote.Audio>(repository.getNoteById(note.uid)).transcription)
        }

    @Test
    fun anUnresolvedSemanticIdentitySurvivesDatabaseReopenWithoutFabricatedCoordinates() =
        runTest {
            val location = NoteLocation(place = NotePlace(Uuid.random()))
            val note = JournalNote.Text(creationTimestamp = now, lastUpdated = now, content = "At a known place", location = location)
            assertTrue(repository.compareAndSetNoteForRestore(null, note))
            reopen()
            assertEquals(location, repository.getNoteById(note.uid)?.location)
        }

    @Test
    fun measuredCoordinatesAndIndependentPlaceCenterSurviveDatabaseReopenForEveryNoteType() =
        runTest {
            val location =
                NoteLocation(
                    coordinates = NoteCoordinates(36.1699, -115.1398, 610.25, 3.5f),
                    place = NotePlace(Uuid.random(), "Home", 36.17, -115.14),
                )
            val notes =
                listOf(
                    JournalNote.Text(creationTimestamp = now, lastUpdated = now, content = "Measured here", location = location),
                    JournalNote.Image(
                        creationTimestamp = now,
                        lastUpdated = now,
                        mediaRef = "logdate-unavailable://archive/image",
                        location = location,
                    ),
                    JournalNote.Video(
                        creationTimestamp = now,
                        lastUpdated = now,
                        mediaRef = "logdate-unavailable://archive/video",
                        durationMs = 12_345,
                        location = location,
                    ),
                    JournalNote.Audio(
                        creationTimestamp = now,
                        lastUpdated = now,
                        mediaRef = "logdate-unavailable://archive/audio",
                        transcription = "Original",
                        location = location,
                    ),
                )
            notes.forEach { assertTrue(repository.compareAndSetNoteForRestore(null, it)) }
            reopen()
            notes.forEach { assertEquals(it, repository.getNoteById(it.uid)) }
        }

    @Test
    fun duplicateCreateCannotAcknowledgeDifferentRawTranscription() =
        runTest {
            val note =
                JournalNote.Audio(
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "logdate-unavailable://archive/raw",
                    transcription = "Original",
                )
            repository.create(note)
            assertFailsWith<IllegalStateException> { repository.create(note.copy(transcription = "Conflicting words")) }
            reopen()
            assertEquals(note, repository.getNoteById(note.uid))
        }

    @Test
    fun syncCreationRetainsAnUnresolvedPlaceAndEmptyLocation() =
        runTest {
            val first =
                JournalNote.Text(
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Unresolved",
                    location = NoteLocation(place = NotePlace(Uuid.random())),
                )
            val second = first.copy(uid = Uuid.random(), content = "Empty recorded location", location = NoteLocation())
            repository.createFromSync(first)
            repository.createFromSync(second)
            reopen()
            assertEquals(first, repository.getNoteById(first.uid))
            assertEquals(second, repository.getNoteById(second.uid))
        }

    @Test
    fun aPlaceArrivingLaterHydratesEveryReadWithoutChangingStoredSourceCoordinates() =
        runTest {
            val placeId = Uuid.random()
            val coordinates = NoteCoordinates(36.1699, -115.1398, 610.25, 3.5f)
            val note =
                JournalNote.Audio(
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "logdate-unavailable://archive/hydrate",
                    location = NoteLocation(coordinates, NotePlace(placeId)),
                )
            repository.createFromSync(note)
            val originalMetadata = database.audioNoteDao().findNoteOneOff(note.uid)!!.locationMetadata
            val place = PlaceEntity(placeId, "Home", 36.17, -115.14, created = now, lastUpdated = now)
            database.placeDao().insert(place)
            reopen()
            val expected = NoteLocation(coordinates, NotePlace(placeId, "Home", 36.17, -115.14))
            assertEquals(expected, repository.getNoteById(note.uid)?.location)
            assertEquals(
                expected,
                repository
                    .observeRecentAudioNotes(10)
                    .first()
                    .single()
                    .location,
            )
            assertEquals(
                expected,
                repository
                    .observeNotesStream(10)
                    .first()
                    .single()
                    .location,
            )
            assertEquals(expected, repository.getNotesBefore(now + 1.seconds, 10).single().location)
            assertEquals(originalMetadata, database.audioNoteDao().findNoteOneOff(note.uid)!!.locationMetadata)
            database.placeDao().update(place.copy(name = "Renamed home"))
            assertEquals(
                "Renamed home",
                repository
                    .getNoteById(note.uid)
                    ?.location
                    ?.place
                    ?.name,
            )
            database.useWriterConnection { it.execSQL("DELETE FROM places WHERE id = '$placeId'") }
            assertEquals(note.location, repository.getNoteById(note.uid)?.location)
            assertEquals(originalMetadata, database.audioNoteDao().findNoteOneOff(note.uid)!!.locationMetadata)
        }

    @Test
    fun knownPlaceKeepsItsLinkedForeignKeyAndSourceCopyAfterDeletion() =
        runTest {
            val placeId = Uuid.random()
            database.placeDao().insert(PlaceEntity(placeId, "Home", 36.17, -115.14, created = now, lastUpdated = now))
            val note =
                JournalNote.Text(
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "At home",
                    location = NoteLocation(place = NotePlace(placeId, "Home", 36.17, -115.14)),
                )
            repository.create(note)
            assertEquals(placeId, database.textNoteDao().findNoteOneOff(note.uid)!!.placeId)
            val originalMetadata = database.textNoteDao().findNoteOneOff(note.uid)!!.locationMetadata
            database.useWriterConnection { it.execSQL("DELETE FROM places WHERE id = '$placeId'") }
            reopen()
            assertEquals(note, repository.getNoteById(note.uid))
            assertEquals(originalMetadata, database.textNoteDao().findNoteOneOff(note.uid)!!.locationMetadata)
        }

    @Test
    fun retryingTheSameOriginalNoteAfterPlaceRenameIsStillIdempotent() =
        runTest {
            val placeId = Uuid.random()
            val place = PlaceEntity(placeId, "Home", 36.17, -115.14, created = now, lastUpdated = now)
            database.placeDao().insert(place)
            val note =
                JournalNote.Text(
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "At home",
                    location = NoteLocation(place = NotePlace(placeId, "Home", 36.17, -115.14)),
                )
            repository.create(note)
            database.placeDao().update(place.copy(name = "Renamed home"))
            assertEquals(note.uid, repository.create(note))
            val hydrated = repository.getNoteById(note.uid) as JournalNote.Text
            assertEquals("Renamed home", hydrated.location?.place?.name)
            assertTrue(
                repository.compareAndSetNoteForRestore(
                    hydrated,
                    hydrated.copy(
                        content = "Edited after rename",
                        lastUpdated =
                            now + 1.seconds,
                    ),
                ),
            )
            database.placeDao().update(place.copy(name = "Renamed again"))
            assertEquals(
                "Renamed again",
                repository
                    .getNoteById(note.uid)
                    ?.location
                    ?.place
                    ?.name,
            )
        }

    @Test
    fun restoredKnownPlaceSnapshotIsNotReplacedByDifferentDestinationMetadata() =
        runTest {
            val placeId = Uuid.random()
            database.placeDao().insert(PlaceEntity(placeId, "Destination name", 36.2, -115.3, created = now, lastUpdated = now))
            val location =
                NoteLocation(
                    NoteCoordinates(36.1699, -115.1398, 610.25, 3.5f),
                    NotePlace(placeId, "Source name", 36.17, -115.14),
                )
            val note = JournalNote.Text(creationTimestamp = now, lastUpdated = now, content = "Imported point", location = location)
            assertTrue(repository.compareAndSetNoteForRestore(null, note))
            reopen()
            assertEquals(location, repository.getNoteById(note.uid)?.location)
            assertEquals(
                location,
                repository
                    .observeNotesStream(10)
                    .first()
                    .single()
                    .location,
            )
            val imported = repository.getNoteById(note.uid) as JournalNote.Text
            assertTrue(
                repository.compareAndSetNoteForRestore(
                    imported,
                    imported.copy(
                        content = "Edited after restore",
                        lastUpdated =
                            now + 1.seconds,
                    ),
                ),
            )
            reopen()
            assertEquals(location, repository.getNoteById(note.uid)?.location)
        }

    @Test
    fun metadataEditDoesNotTurnHydratedPlaceFieldsIntoSourceFacts() =
        runTest {
            val placeId = Uuid.random()
            val note =
                JournalNote.Text(
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Imported ID only",
                    location = NoteLocation(place = NotePlace(placeId)),
                )
            assertTrue(repository.compareAndSetNoteForRestore(null, note))
            val raw = database.textNoteDao().findNoteOneOff(note.uid)!!.locationMetadata
            val place = PlaceEntity(placeId, "First resolution", 36.17, -115.14, created = now, lastUpdated = now)
            database.placeDao().insert(place)
            val hydrated = repository.getNoteById(note.uid) as JournalNote.Text
            assertTrue(
                repository.compareAndSetNoteForRestore(hydrated, hydrated.copy(content = "Edited text", lastUpdated = now + 1.seconds)),
            )
            assertEquals(raw, database.textNoteDao().findNoteOneOff(note.uid)!!.locationMetadata)
            database.placeDao().update(place.copy(name = "Later resolution", latitude = 36.2, longitude = -115.3))
            reopen()
            assertEquals(NotePlace(placeId, "Later resolution", 36.2, -115.3), repository.getNoteById(note.uid)?.location?.place)
        }

    private fun openDatabase() = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(directory.resolve("notes.db").toString()))

    private fun repository() =
        OfflineFirstJournalNotesRepository(
            textNoteDao = database.textNoteDao(),
            imageNoteDao = database.imageNoteDao(),
            audioNoteDao = database.audioNoteDao(),
            videoNoteDao = database.videoNoteDao(),
            journalContentDao = database.journalContentDao(),
            journalRepository = FakeJournalRepository(),
            mediaCaptionDao = database.mediaCaptionDao(),
            notePlaceResolver = DatabaseNotePlaceResolver(database.placeDao()),
            transactionManager = RoomSyncTransactionManager(database),
            syncMetadataService = FakeSyncMetadataService(),
        )

    private fun reopen() {
        database.close()
        database = openDatabase()
        repository = repository()
    }
}
