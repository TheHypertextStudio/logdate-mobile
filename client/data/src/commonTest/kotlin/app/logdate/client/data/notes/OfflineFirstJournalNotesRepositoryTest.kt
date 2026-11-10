package app.logdate.client.data.notes

import app.logdate.client.data.fakes.FakeAudioNoteDao
import app.logdate.client.data.fakes.FakeImageNoteDao
import app.logdate.client.data.fakes.FakeJournalContentDao
import app.logdate.client.data.fakes.FakeJournalRepository
import app.logdate.client.data.fakes.FakeMediaCaptionDao
import app.logdate.client.data.fakes.FakeSyncManager
import app.logdate.client.data.fakes.FakeSyncMetadataService
import app.logdate.client.data.fakes.FakeTextNoteDao
import app.logdate.client.data.fakes.FakeVideoNoteDao
import app.logdate.client.data.fakes.RecordingMediaClaimFence
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.MediaPayload
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.media.storage.MediaReclamationJournal
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.journals.PendingMediaType
import app.logdate.client.repository.media.ExifMetadata
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Unit tests for [OfflineFirstJournalNotesRepository].
 *
 * These tests validate the repository's implementation of [JournalNotesRepository],
 * focusing on operations with journal notes including text and image notes. The tests use
 * fake implementations of dependencies to isolate the repository's behavior.
 *
 * Test cases cover:
 * - Observing notes (empty state, combined text and image notes)
 * - Filtering notes by journal, time range, and pagination
 * - Creating and removing different types of notes
 * - Associating notes with journals and removing those associations
 */
class OfflineFirstJournalNotesRepositoryTest {
    private lateinit var textNoteDao: FakeTextNoteDao
    private lateinit var imageNoteDao: FakeImageNoteDao
    private lateinit var videoNoteDao: FakeVideoNoteDao
    private lateinit var mediaCaptionDao: FakeMediaCaptionDao
    private lateinit var journalContentDao: FakeJournalContentDao
    private lateinit var journalRepository: FakeJournalRepository
    private lateinit var syncManager: FakeSyncManager
    private lateinit var syncMetadataService: FakeSyncMetadataService
    private lateinit var indexedMediaRepository: FakeIndexedMediaRepository
    private lateinit var mediaManager: FakeMediaManager
    private lateinit var repository: OfflineFirstJournalNotesRepository
    private var draftSnapshots = emptyList<EntryDraft>()
    private var coverUris = emptyList<String?>()

    @Test
    fun `media note creation and removal run inside the media claim fence`() =
        runTest {
            val fence = RecordingMediaClaimFence()
            val local = createRepository(mediaClaimFence = fence)
            val note = createTestImageNote()
            mediaManager.mediaByUri[note.mediaRef] =
                MediaObject.Image(
                    uri = note.mediaRef,
                    size = 128,
                    name = "note.jpg",
                    timestamp = note.creationTimestamp,
                )

            local.create(note)
            assertEquals(1, fence.claims)

            local.remove(note)
            assertEquals(2, fence.claims)
        }

    @BeforeTest
    fun setup() {
        textNoteDao = FakeTextNoteDao()
        imageNoteDao = FakeImageNoteDao()
        videoNoteDao = FakeVideoNoteDao()
        mediaCaptionDao = FakeMediaCaptionDao()
        journalContentDao = FakeJournalContentDao()
        journalRepository = FakeJournalRepository()
        syncManager = FakeSyncManager()
        syncMetadataService = FakeSyncMetadataService()
        indexedMediaRepository = FakeIndexedMediaRepository()
        mediaManager = FakeMediaManager()
        draftSnapshots = emptyList()
        coverUris = emptyList()

        repository = createRepository()
    }

    @AfterTest
    fun tearDown() {
        textNoteDao.clear()
        imageNoteDao.clear()
        videoNoteDao.clear()
        mediaCaptionDao.clear()
        journalContentDao.clear()
        journalRepository.clear()
        syncManager.reset()
    }

    /**
     * Verifies that the repository initially emits an empty list of notes when no notes exist.
     *
     * Expected behavior: The flow should emit an empty list when first collected.
     */
    @Test
    fun `all notes observed emits empty list initially`() =
        runTest {
            val notes = repository.allNotesObserved.first()
            assertTrue(notes.isEmpty())
        }

    /**
     * Tests that the repository correctly combines text and image notes from different DAOs.
     *
     * Expected behavior: The repository should emit a list containing both text and image notes
     * when notes of both types exist in their respective DAOs.
     */
    @Test
    fun `all notes observed combines text and image notes`() =
        runTest {
            val textNote = createTestTextNote()
            val imageNote = createTestImageNote()

            textNoteDao.addNote(textNote.toEntity())
            imageNoteDao.addNote(imageNote.toEntity())

            val notes = repository.allNotesObserved.first()
            assertEquals(2, notes.size)
        }

    @Test
    fun `entry timestamps include the creation time of every note type`() =
        runTest {
            val textNote = createTestTextNote()
            val imageNote = createTestImageNote()
            textNoteDao.addNote(textNote.toEntity())
            imageNoteDao.addNote(imageNote.toEntity())

            val timestamps = repository.observeEntryTimestamps().first()

            assertEquals(
                setOf(textNote.creationTimestamp, imageNote.creationTimestamp).map { it.toEpochMilliseconds() }.toSet(),
                timestamps.map { it.toEpochMilliseconds() }.toSet(),
            )
        }

    @Test
    fun `observe notes in journal filters notes by journal`() =
        runTest {
            val journal = createTestJournal()
            val textNote = createTestTextNote()
            val otherNote = createTestTextNote()

            journalRepository.addJournal(journal)
            textNoteDao.addNote(textNote.toEntity())
            textNoteDao.addNote(otherNote.toEntity())
            journalContentDao.addContentToJournal(JournalContentEntityLink(journal.id, textNote.uid))

            val notes = repository.observeNotesInJournal(journal.id).first()

            assertEquals(listOf(textNote.uid), notes.map { it.uid })
            assertEquals(0, textNoteDao.wholeTableReads, "a journal read every note to find its own")
        }

    /**
     * Tests that the repository correctly filters notes by a specific time range.
     *
     * Expected behavior: When requesting notes within a specific time range, only notes with
     * creation timestamps within that range should be returned, excluding notes outside the range.
     */
    @Test
    fun `observe notes in range filters notes by time range`() =
        runTest {
            val now = Clock.System.now()
            val hourAgo = now - 1.hours
            val hourFromNow = now + 1.hours

            val noteInRange = createTestTextNote().copy(creationTimestamp = now)
            val noteOutOfRange =
                createTestTextNote().copy(
                    creationTimestamp = now - 2.hours,
                )

            textNoteDao.addNote(noteInRange.toEntity())
            textNoteDao.addNote(noteOutOfRange.toEntity())

            val notes = repository.observeNotesInRange(hourAgo, hourFromNow).first()
            assertEquals(1, notes.size)
            assertEquals(noteInRange.uid, notes.first().uid)
        }

    /**
     * Tests that the repository correctly implements pagination for notes.
     *
     * Expected behavior:
     * 1. When requesting pages of notes with a specific size and offset, the repository should return
     *    the correct number of notes for each page
     * 2. Different pages should contain different notes based on the offset
     * 3. The total number of notes across all pages should equal the total number of notes in the database
     */
    @Test
    fun `observe notes page returns paginated results`() =
        runTest {
            // Add multiple notes
            repeat(5) { index ->
                val note =
                    createTestTextNote().copy(
                        content = "Note $index",
                        creationTimestamp = Clock.System.now() + index.seconds,
                    )
                textNoteDao.addNote(note.toEntity())
            }

            val firstPage = repository.observeNotesPage(pageSize = 2, offset = 0).first()
            assertEquals(2, firstPage.size)

            val secondPage = repository.observeNotesPage(pageSize = 2, offset = 2).first()
            assertEquals(2, secondPage.size)
        }

    @Test
    fun `a page reads only the rows that can appear on it`() =
        runTest {
            val start = Clock.System.now()
            val texts = List(4) { createTestTextNote().copy(content = "Text $it", creationTimestamp = start + (it * 2).seconds) }
            val images = List(4) { createTestImageNote().copy(creationTimestamp = start + (it * 2 + 1).seconds) }
            texts.forEach { textNoteDao.addNote(it.toEntity()) }
            images.forEach { imageNoteDao.addNote(it.toEntity()) }
            val newestFirst = (texts + images).sortedByDescending { it.creationTimestamp }.map { it.uid }

            val page = repository.observeNotesPage(pageSize = 3, offset = 2).first()

            assertEquals(newestFirst.drop(2).take(3), page.map { it.uid })
            assertEquals(listOf(5), textNoteDao.recentLimits)
            assertEquals(0, textNoteDao.wholeTableReads)
        }

    @Test
    fun `notes are counted without reading them`() =
        runTest {
            textNoteDao.addNote(createTestTextNote().toEntity())
            textNoteDao.addNote(createTestTextNote().toEntity())
            imageNoteDao.addNote(createTestImageNote().toEntity())

            val counts = repository.countNotes()

            assertEquals(3, counts.total)
            assertEquals(1, counts.withMedia)
            assertEquals(0, textNoteDao.wholeTableReads)
        }

    @Test
    fun `one note is observed without reading every note`() =
        runTest {
            val note = createTestTextNote().copy(content = "Draft thought")
            textNoteDao.addNote(note.toEntity())
            textNoteDao.addNote(createTestTextNote().toEntity())

            val observed = repository.observeNoteById(note.uid)

            assertEquals("Draft thought", (observed.first() as JournalNote.Text).content)
            textNoteDao.removeNote(note.uid)
            assertEquals(null, observed.first())
            assertEquals(0, textNoteDao.wholeTableReads)
        }

    @Test
    fun `observe notes stream returns all notes`() =
        runTest {
            val textNote = createTestTextNote()
            val imageNote = createTestImageNote()

            textNoteDao.addNote(textNote.toEntity())
            imageNoteDao.addNote(imageNote.toEntity())

            val notes = repository.observeNotesStream().first()
            assertEquals(2, notes.size)
        }

    @Test
    fun `create video note adds to database`() =
        runTest {
            val videoNote =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = "file:///video.mp4",
                )
            mediaManager.mediaByUri[videoNote.mediaRef] =
                MediaObject.Video(
                    name = "video.mp4",
                    uri = videoNote.mediaRef,
                    size = 1024,
                    timestamp = videoNote.creationTimestamp,
                    duration = 5.seconds,
                )

            val noteId = repository.create(videoNote)

            assertEquals(videoNote.uid, noteId)
            val allNotes = repository.allNotesObserved.first()
            assertTrue(allNotes.any { it.uid == videoNote.uid })
        }

    @Test
    fun `create video note indexes media automatically`() =
        runTest {
            val videoNote =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = "file:///video-indexed.mp4",
                )
            mediaManager.mediaByUri[videoNote.mediaRef] =
                MediaObject.Video(
                    name = "video-indexed.mp4",
                    uri = videoNote.mediaRef,
                    size = 1024,
                    timestamp = videoNote.creationTimestamp,
                    duration = 42.seconds,
                )

            repository.create(videoNote)

            assertTrue(indexedMediaRepository.isIndexed(videoNote.mediaRef))
        }

    @Test
    fun `unavailable image and video originals persist without metadata lookup or indexing`() =
        runTest {
            val image =
                createTestImageNote().copy(
                    mediaRef = UnavailableMediaReferences.nativeRestore("logdate-media://library/missing-image.jpg"),
                )
            val video =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = UnavailableMediaReferences.nativeRestore("logdate-media://library/missing-video.mp4"),
                )

            assertEquals(image.uid, repository.create(image))
            assertEquals(video.uid, repository.create(video))
            assertEquals(image.mediaRef, (repository.getNoteById(image.uid) as JournalNote.Image).mediaRef)
            assertEquals(video.mediaRef, (repository.getNoteById(video.uid) as JournalNote.Video).mediaRef)
            assertTrue(indexedMediaRepository.observeAllMedia().first().isEmpty())
        }

    @Test
    fun `create video note stores the length the media reports`() =
        runTest {
            val videoNote =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = "file:///video-length.mp4",
                )
            mediaManager.mediaByUri[videoNote.mediaRef] =
                MediaObject.Video(
                    name = "video-length.mp4",
                    uri = videoNote.mediaRef,
                    size = 1024,
                    timestamp = videoNote.creationTimestamp,
                    duration = 42.seconds,
                )

            repository.create(videoNote)

            val stored = repository.getNoteById(videoNote.uid) as JournalNote.Video
            assertEquals(42_000L, stored.durationMs)
        }

    @Test
    fun `saving a video note again with a length the first save filled in is the same note`() =
        runTest {
            val videoNote =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = "file:///video-again.mp4",
                )
            mediaManager.mediaByUri[videoNote.mediaRef] =
                MediaObject.Video(
                    name = "video-again.mp4",
                    uri = videoNote.mediaRef,
                    size = 1024,
                    timestamp = videoNote.creationTimestamp,
                    duration = 9.seconds,
                )

            repository.create(videoNote)

            assertEquals(videoNote.uid, repository.create(videoNote))
        }

    /**
     * Tests creating a text note through the repository.
     *
     * Expected behavior:
     * 1. The create method should return the same UUID as the note
     * 2. The note should be stored in the database and be observable through the repository's flows
     * 3. The note's content should match the original note's content
     */
    @Test
    fun `create text note adds to database`() =
        runTest {
            val textNote = createTestTextNote()

            val noteId = repository.create(textNote)

            assertEquals(textNote.uid, noteId)
            val allNotes = repository.allNotesObserved.first()
            assertEquals(1, allNotes.size)
            assertEquals(textNote.content, (allNotes.first() as JournalNote.Text).content)
        }

    @Test
    fun `create image note adds to database`() =
        runTest {
            val imageNote = createTestImageNote()
            mediaManager.mediaByUri[imageNote.mediaRef] =
                MediaObject.Image(
                    uri = imageNote.mediaRef,
                    size = 128,
                    name = "test-image.jpg",
                    timestamp = imageNote.creationTimestamp,
                )

            val noteId = repository.create(imageNote)

            assertEquals(imageNote.uid, noteId)
            val allNotes = repository.allNotesObserved.first()
            assertEquals(1, allNotes.size)
            assertEquals(imageNote.mediaRef, (allNotes.first() as JournalNote.Image).mediaRef)
        }

    @Test
    fun `create image note indexes media automatically`() =
        runTest {
            val imageNote = createTestImageNote()
            mediaManager.mediaByUri[imageNote.mediaRef] =
                MediaObject.Image(
                    uri = imageNote.mediaRef,
                    size = 128,
                    name = "test-image.jpg",
                    timestamp = imageNote.creationTimestamp,
                )

            repository.create(imageNote)

            assertTrue(indexedMediaRepository.isIndexed(imageNote.mediaRef))
        }

    @Test
    fun `create image note does not duplicate already indexed media`() =
        runTest {
            val imageNote = createTestImageNote()
            mediaManager.mediaByUri[imageNote.mediaRef] =
                MediaObject.Image(
                    uri = imageNote.mediaRef,
                    size = 128,
                    name = "test-image.jpg",
                    timestamp = imageNote.creationTimestamp,
                )
            indexedMediaRepository.indexImage(
                uri = imageNote.mediaRef,
                timestamp = imageNote.creationTimestamp,
            )

            repository.create(imageNote)

            assertEquals(1, indexedMediaRepository.observeAllMedia().first().size)
        }

    @Test
    fun `create image note rolls back indexed media when note persistence fails`() =
        runTest {
            val imageNote = createTestImageNote()
            mediaManager.mediaByUri[imageNote.mediaRef] =
                MediaObject.Image(
                    uri = imageNote.mediaRef,
                    size = 128,
                    name = "test-image.jpg",
                    timestamp = imageNote.creationTimestamp,
                )
            repository =
                createRepository(
                    imageNoteDao =
                        object : ImageNoteDao by FakeImageNoteDao() {
                            override suspend fun addNote(note: app.logdate.client.database.entities.ImageNoteEntity): Unit =
                                throw IllegalStateException("image note write failed")
                        },
                    transactionManager = IndexedMediaRollbackTransactionManager(indexedMediaRepository),
                )

            assertFailsWith<IllegalStateException> {
                repository.create(imageNote)
            }

            assertTrue(!indexedMediaRepository.isIndexed(imageNote.mediaRef))
        }

    @Test
    fun `create audio note adds to database`() =
        runTest {
            val audioNote =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = "audio.mp3",
                )

            val noteId = repository.create(audioNote)

            assertEquals(audioNote.uid, noteId)
            val allNotes = repository.allNotesObserved.first()
            assertTrue(allNotes.any { it.uid == audioNote.uid })
        }

    @Test
    fun `recent audio notes are not crowded out by newer text notes`() =
        runTest {
            val base = Clock.System.now()
            val oldRecording = audioNoteAt(base - 3.hours)
            val newerRecording = audioNoteAt(base - 2.hours)
            repository.create(oldRecording)
            repository.create(newerRecording)
            repeat(5) { index -> repository.create(textNoteAt(base - index.seconds)) }

            val recent = repository.observeRecentAudioNotes(limit = 2).first()

            assertEquals(listOf(newerRecording.uid, oldRecording.uid), recent.map { it.uid })
        }

    @Test
    fun `recent audio notes are limited and newest first`() =
        runTest {
            val base = Clock.System.now()
            val recordings = (1..4).map { hoursAgo -> audioNoteAt(base - hoursAgo.hours) }
            recordings.shuffled().forEach { repository.create(it) }

            val recent = repository.observeRecentAudioNotes(limit = 3).first()

            assertEquals(recordings.take(3).map { it.uid }, recent.map { it.uid })
        }

    @Test
    fun `recent audio notes are empty when there are only other notes`() =
        runTest {
            repository.create(createTestTextNote())

            assertTrue(repository.observeRecentAudioNotes().first().isEmpty())
        }

    @Test
    fun `remove text note removes from database`() =
        runTest {
            val textNote = createTestTextNote()
            textNoteDao.addNote(textNote.toEntity())

            repository.remove(textNote)

            val allNotes = repository.allNotesObserved.first()
            assertTrue(allNotes.isEmpty())
        }

    @Test
    fun `remove image note removes from database`() =
        runTest {
            val imageNote = createTestImageNote()
            imageNoteDao.addNote(imageNote.toEntity())

            repository.remove(imageNote)

            val allNotes = repository.allNotesObserved.first()
            assertTrue(allNotes.isEmpty())
        }

    @Test
    fun `remove by id removes text note`() =
        runTest {
            val textNote = createTestTextNote()
            textNoteDao.addNote(textNote.toEntity())

            repository.removeById(textNote.uid)

            val allNotes = repository.allNotesObserved.first()
            assertTrue(allNotes.isEmpty())
        }

    @Test
    fun `create with journal adds note and links to journal`() =
        runTest {
            val journal = createTestJournal()
            val textNote = createTestTextNote()

            journalRepository.addJournal(journal)

            repository.create(textNote, journal.id)

            val allNotes = repository.allNotesObserved.first()
            assertEquals(1, allNotes.size)

            // Verify the note is linked to the journal
            val journalNotes = journalContentDao.getContentForJournal(journal.id).first()
            assertEquals(1, journalNotes.size)
            assertEquals(textNote.uid, journalNotes.first())
        }

    @Test
    fun `all notes observed hydrates semantic place from resolver`() =
        runTest {
            val place =
                NotePlace(
                    id = Uuid.random(),
                    name = "Home",
                    latitude = 37.3317,
                    longitude = -122.0301,
                )
            repository = createRepository(FakeNotePlaceResolver(place))

            val textNote =
                createTestTextNote().copy(
                    location =
                        NoteLocation(
                            coordinates = NoteCoordinates(latitude = 37.3317, longitude = -122.0301),
                            place = place,
                        ),
                )
            textNoteDao.addNote(textNote.toEntity())

            val observedNote = repository.allNotesObserved.first().single() as JournalNote.Text

            assertEquals("Home", observedNote.location?.displayName)
            assertEquals(place.id, observedNote.location?.place?.id)
        }

    @Test
    fun `notesReferencingMediaPaths returns only the paths a note still owns`() =
        runTest {
            val referencedAudioPath = "audio-referenced.mp3"
            val referencedImagePath = "test-image.jpg"
            val orphanedPath = "not-referenced-anywhere.mp3"

            repository.create(
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = referencedAudioPath,
                ),
            )
            imageNoteDao.addNote(createTestImageNote().copy(mediaRef = referencedImagePath).toEntity())

            val result =
                repository.notesReferencingMediaPaths(
                    setOf(referencedAudioPath, referencedImagePath, orphanedPath),
                )

            assertEquals(setOf(referencedAudioPath, referencedImagePath), result)
        }

    @Test
    fun `notesReferencingMediaPaths returns empty set for an empty query`() =
        runTest {
            val result = repository.notesReferencingMediaPaths(emptySet())

            assertTrue(result.isEmpty())
        }

    @Test
    fun `notesReferencingMediaPaths recognizes a legacy spelling of the same owned file`() =
        runTest {
            val legacy = "file:/data/user/0/studio.hypertext.logdate/files/audio_notes/shared.m4a"
            val owned = "logdate-media://recordings/shared.m4a"
            repository = createRepository(mediaReferences = StoredMediaReferences { if (it == legacy) owned else it })
            imageNoteDao.addNote(createTestImageNote().copy(mediaRef = legacy).toEntity())

            assertEquals(setOf(owned), repository.notesReferencingMediaPaths(setOf(owned)))
        }

    @Test
    fun `removing one note retains a file still named by a legacy note URI`() =
        runTest {
            val legacy = "file:/data/user/0/studio.hypertext.logdate/files/media/shared.jpg"
            val owned = "logdate-media://library/shared.jpg"
            repository = createRepository(mediaReferences = StoredMediaReferences { if (it == legacy) owned else it })
            val deleted = createTestImageNote().copy(mediaRef = owned)
            val retained = createTestImageNote().copy(uid = Uuid.random(), mediaRef = legacy)
            imageNoteDao.addNote(deleted.toEntity())
            imageNoteDao.addNote(retained.toEntity())

            repository.remove(deleted)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
            assertEquals(legacy, imageNoteDao.findNoteOneOff(retained.uid)?.contentUri)
        }

    @Test
    fun `remove from journal removes note journal link`() =
        runTest {
            val journal = createTestJournal()
            val textNote = createTestTextNote()

            journalRepository.addJournal(journal)
            textNoteDao.addNote(textNote.toEntity())
            journalContentDao.addContentToJournal(
                JournalContentEntityLink(journal.id, textNote.uid),
            )

            repository.removeFromJournal(textNote.uid, journal.id)

            val journalNotes = journalContentDao.getContentForJournal(journal.id).first()
            assertTrue(journalNotes.isEmpty())
        }

    private fun audioNoteAt(created: kotlin.time.Instant) =
        JournalNote.Audio(
            uid = Uuid.random(),
            creationTimestamp = created,
            lastUpdated = created,
            mediaRef = "audio-${Uuid.random()}.m4a",
            durationMs = 5_000,
        )

    private fun textNoteAt(created: kotlin.time.Instant) =
        JournalNote.Text(
            uid = Uuid.random(),
            content = "Text at $created",
            creationTimestamp = created,
            lastUpdated = created,
        )

    /**
     * Creates a test text note with random UUID and test content.
     * Used as a helper method for tests that need text note instances.
     */
    private fun createTestTextNote() =
        JournalNote.Text(
            uid = Uuid.random(),
            content = "Test text content",
            creationTimestamp = Clock.System.now(),
            lastUpdated = Clock.System.now(),
        )

    /**
     * Creates a test image note with random UUID and test media reference.
     * Used as a helper method for tests that need image note instances.
     */
    private fun createTestImageNote() =
        JournalNote.Image(
            uid = Uuid.random(),
            mediaRef = "test-image.jpg",
            creationTimestamp = Clock.System.now(),
            lastUpdated = Clock.System.now(),
        )

    /**
     * Creates a test journal with random UUID and test data.
     * Used as a helper method for tests that need journal instances.
     */
    private fun createTestJournal() =
        Journal(
            id = Uuid.random(),
            title = "Test Journal",
            description = "Test Description",
            created = Clock.System.now(),
            lastUpdated = Clock.System.now(),
        )

    @Test
    fun `media candidate is durable before a note is deleted`() =
        runTest {
            val recorded = mutableListOf<String>()
            val journal = MediaReclamationJournal { reference -> recorded += reference }
            val local = createRepository(mediaReclamation = journal)
            val note = createTestImageNote().copy(mediaRef = "file:///data/user/0/studio.hypertext.logdate/files/media/owned.jpg")
            imageNoteDao.addNote(note.toEntity())

            local.remove(note)

            assertEquals(listOf(note.mediaRef), recorded)
        }

    @Test
    fun `failed media candidate write prevents note deletion`() =
        runTest {
            val local = createRepository(mediaReclamation = MediaReclamationJournal { error("Candidate storage failed") })
            val note = createTestImageNote().copy(mediaRef = "file:///data/user/0/studio.hypertext.logdate/files/media/owned.jpg")
            imageNoteDao.addNote(note.toEntity())

            assertFailsWith<IllegalStateException> { local.remove(note) }

            assertEquals(note, local.getNoteById(note.uid))
        }

    @Test
    fun `removing a note removes the media it was the last to reference`() =
        runTest {
            val repository = createRepository()
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())

            repository.remove(note)

            assertEquals(listOf(note.mediaRef), mediaManager.deletedOwnedMedia)
        }

    /**
     * Media is stored content-addressed, so two entries holding identical bytes share one file.
     * Deleting one must not take the other's photo with it.
     */
    @Test
    fun `removing a note leaves media another note still references`() =
        runTest {
            val repository = createRepository()
            val shared = "shared-image.jpg"
            val first = createTestImageNote().copy(mediaRef = shared)
            val second = createTestImageNote().copy(mediaRef = shared)
            imageNoteDao.addNote(first.toEntity())
            imageNoteDao.addNote(second.toEntity())

            repository.remove(first)

            assertTrue(
                mediaManager.deletedOwnedMedia.isEmpty(),
                "media still referenced by another entry must not be deleted",
            )

            repository.remove(second)

            assertEquals(listOf(shared), mediaManager.deletedOwnedMedia)
        }

    @Test
    fun `removing a note preserves media referenced by an editor draft`() =
        runTest {
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())
            draftSnapshots =
                listOf(
                    EntryDraft(
                        id = Uuid.random(),
                        notes = listOf(note.copy(uid = Uuid.random())),
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                    ),
                )

            repository.remove(note)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
        }

    @Test
    fun `removing a note preserves a shared original in a draft video`() =
        runTest {
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())
            draftSnapshots =
                listOf(
                    EntryDraft(
                        id = Uuid.random(),
                        notes =
                            listOf(
                                JournalNote.Video(
                                    mediaRef = note.mediaRef,
                                    creationTimestamp = Clock.System.now(),
                                    lastUpdated = Clock.System.now(),
                                ),
                            ),
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                    ),
                )

            repository.remove(note)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
        }

    @Test
    fun `removing a note preserves media referenced by a journal cover`() =
        runTest {
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())
            coverUris = listOf(note.mediaRef)

            repository.remove(note)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
        }

    @Test
    fun `removing a note preserves a recording checkpoint in an editor draft`() =
        runTest {
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())
            draftSnapshots =
                listOf(
                    EntryDraft(
                        id = Uuid.random(),
                        notes = emptyList(),
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                        pendingMedia =
                            listOf(
                                PendingMediaRecord(
                                    blockId = Uuid.random(),
                                    mediaType = PendingMediaType.AUDIO,
                                    createdAt = Clock.System.now(),
                                    fallbackFilePath = note.mediaRef,
                                ),
                            ),
                    ),
                )

            repository.remove(note)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
        }

    @Test
    fun `removing a note preserves an active recording path in an editor draft`() =
        runTest {
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())
            draftSnapshots =
                listOf(
                    EntryDraft(
                        id = Uuid.random(),
                        notes = emptyList(),
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                        pendingMedia =
                            listOf(
                                PendingMediaRecord(
                                    blockId = Uuid.random(),
                                    mediaType = PendingMediaType.AUDIO,
                                    createdAt = Clock.System.now(),
                                    filePath = note.mediaRef,
                                ),
                            ),
                    ),
                )

            repository.remove(note)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
        }

    @Test
    fun `removing a text note deletes no media`() =
        runTest {
            val repository = createRepository()
            val note = createTestTextNote()
            textNoteDao.addNote(note.toEntity())

            repository.remove(note)

            assertTrue(mediaManager.deletedOwnedMedia.isEmpty())
        }

    @Test
    fun `removing by id also removes the media`() =
        runTest {
            val repository = createRepository()
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())

            repository.removeById(note.uid)

            assertEquals(listOf(note.mediaRef), mediaManager.deletedOwnedMedia)
        }

    @Test
    fun `removing a note rethrows cancellation from media deletion`() =
        runTest {
            val repository = createRepository()
            val note = createTestImageNote()
            imageNoteDao.addNote(note.toEntity())
            mediaManager.failDeleteWith = kotlinx.coroutines.CancellationException("Media delete cancelled")

            assertFailsWith<kotlinx.coroutines.CancellationException> {
                repository.remove(note)
            }
        }

    /**
     * Converts a Journal model to a JournalEntity for database operations.
     */
    private fun Journal.toEntity() =
        JournalEntity(
            id = id,
            title = title,
            description = description,
            created = created,
            lastUpdated = lastUpdated,
        )

    private fun createRepository(
        notePlaceResolver: NotePlaceResolver = EmptyNotePlaceResolver,
        imageNoteDao: ImageNoteDao = this.imageNoteDao,
        transactionManager: SyncTransactionManager = PassthroughTransactionManager,
        mediaReferences: StoredMediaReferences = StoredMediaReferences.Unchanged,
        mediaReclamation: MediaReclamationJournal = MediaReclamationJournal.None,
        mediaClaimFence: MediaClaimFence = MediaClaimFence.Disabled,
    ) = OfflineFirstJournalNotesRepository(
        textNoteDao = textNoteDao,
        imageNoteDao = imageNoteDao,
        audioNoteDao = FakeAudioNoteDao(),
        videoNoteDao = videoNoteDao,
        journalContentDao = journalContentDao,
        journalRepository = journalRepository,
        mediaCaptionDao = mediaCaptionDao,
        notePlaceResolver = notePlaceResolver,
        indexedMediaRepository = indexedMediaRepository,
        mediaManager = mediaManager,
        transactionManager = transactionManager,
        syncManagerProvider = { syncManager },
        syncMetadataService = syncMetadataService,
        draftSnapshots = { draftSnapshots },
        journalCoverUris = { coverUris },
        mediaReferences = mediaReferences,
        mediaReclamation = mediaReclamation,
        mediaClaimFence = mediaClaimFence,
    )

    private class FakeNotePlaceResolver(
        private val places: Map<Uuid, NotePlace>,
    ) : NotePlaceResolver {
        constructor(vararg places: NotePlace) : this(places.associateBy(NotePlace::id))

        override suspend fun get(placeId: Uuid): NotePlace? = places[placeId]

        override fun observeAll(): Flow<Map<Uuid, NotePlace>> = flowOf(places)
    }

    private class FakeIndexedMediaRepository : IndexedMediaRepository {
        private val media = mutableListOf<IndexedMedia>()

        override suspend fun indexImage(
            uri: String,
            timestamp: kotlin.time.Instant,
        ): IndexedMedia.Image {
            val image = IndexedMedia.Image(uid = Uuid.random(), uri = uri, timestamp = timestamp)
            media.removeAll { it.uri == uri }
            media += image
            return image
        }

        override suspend fun indexVideo(
            uri: String,
            timestamp: kotlin.time.Instant,
            duration: kotlin.time.Duration,
        ): IndexedMedia.Video {
            val video = IndexedMedia.Video(uid = Uuid.random(), uri = uri, timestamp = timestamp, duration = duration)
            media.removeAll { it.uri == uri }
            media += video
            return video
        }

        override suspend fun getByUid(uid: Uuid): IndexedMedia? = media.find { it.uid == uid }

        override fun getForPeriod(
            startTime: kotlin.time.Instant,
            endTime: kotlin.time.Instant,
        ): Flow<List<IndexedMedia>> = flowOf(media.filter { it.timestamp in startTime..endTime })

        override suspend fun isIndexed(uri: String): Boolean = media.any { it.uri == uri }

        override suspend fun remove(uid: Uuid): Boolean {
            val originalSize = media.size
            media.removeAll { it.uid == uid }
            return media.size != originalSize
        }

        override suspend fun updateCaption(
            uid: Uuid,
            caption: String?,
        ): IndexedMedia? = media.find { it.uid == uid }

        override fun observeAllMedia(): Flow<List<IndexedMedia>> = flowOf(media.toList())

        override fun getMediaCount(): Flow<Int> = flowOf(media.size)

        override suspend fun getExifMetadata(uid: Uuid): ExifMetadata? = null

        fun snapshot(): List<IndexedMedia> = media.toList()

        fun restore(snapshot: List<IndexedMedia>) {
            media.clear()
            media.addAll(snapshot)
        }
    }

    private class FakeMediaManager : MediaManager {
        val mediaByUri = mutableMapOf<String, MediaObject>()

        override suspend fun getMedia(uri: String): MediaObject = mediaByUri.getValue(uri)

        val deletedOwnedMedia = mutableListOf<String>()
        var failDeleteWith: Throwable? = null

        override suspend fun deleteOwnedMedia(uri: String): Boolean {
            failDeleteWith?.let { throw it }
            return deletedOwnedMedia.add(uri)
        }

        override suspend fun exists(mediaId: String): Boolean = mediaByUri.containsKey(mediaId)

        override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = flowOf(mediaByUri.values.toList())

        override suspend fun queryMediaByDate(
            start: kotlin.time.Instant,
            end: kotlin.time.Instant,
        ): Flow<List<MediaObject>> = flowOf(mediaByUri.values.filter { it.timestamp in start..end })

        override suspend fun addToDefaultCollection(uri: String) = Unit

        override suspend fun readMedia(uri: String): MediaPayload = throw UnsupportedOperationException()

        override suspend fun saveMedia(payload: MediaPayload): String = throw UnsupportedOperationException()

        override suspend fun saveMediaFromFile(
            sourceFilePath: String,
            fileName: String,
            mimeType: String,
        ): String = throw UnsupportedOperationException()
    }

    private class IndexedMediaRollbackTransactionManager(
        private val indexedMediaRepository: FakeIndexedMediaRepository,
    ) : SyncTransactionManager {
        override suspend fun <T> withTransaction(block: suspend () -> T): T {
            val snapshot = indexedMediaRepository.snapshot()
            return try {
                block()
            } catch (error: Throwable) {
                indexedMediaRepository.restore(snapshot)
                throw error
            }
        }
    }

    private object PassthroughTransactionManager : SyncTransactionManager {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }
}
