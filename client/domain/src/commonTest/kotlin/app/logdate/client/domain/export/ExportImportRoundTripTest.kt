package app.logdate.client.domain.export

import app.logdate.client.device.AppInfo
import app.logdate.client.domain.export.archive.ArchiveExportOptions
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.domain.export.archive.support.InMemoryArchiveContainer
import app.logdate.client.domain.export.support.RoundTripJournalContentRepository
import app.logdate.client.domain.export.support.RoundTripJournalNotesRepository
import app.logdate.client.domain.export.support.RoundTripJournalRepository
import app.logdate.client.domain.export.support.RoundTripLocationHistoryRepository
import app.logdate.client.domain.export.support.RoundTripProfileRepository
import app.logdate.client.domain.export.support.RoundTripUserPlacesRepository
import app.logdate.client.domain.export.support.StubAppInfoProvider
import app.logdate.client.domain.export.support.StubUserStateRepository
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.RestoreArchiveBundle
import app.logdate.client.domain.restore.RestoreArchiveReader
import app.logdate.client.domain.restore.RestoreOptions
import app.logdate.client.domain.restore.RestoreStrategy
import app.logdate.client.domain.restore.RestoreTestDeviceIdProvider
import app.logdate.client.domain.restore.RestoreTestOwnerProvider
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.Place
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * End-to-end round-trip test: export an archive → read it back → import into fresh repositories → verify.
 *
 * Uses realistic payloads covering every note type, locations, captions, media files,
 * drafts, and journal-note relations to prove the pipeline is lossless.
 */
class ExportImportRoundTripTest {
    // Source repositories (populated with test data, used by export)
    private lateinit var sourceJournalRepo: RoundTripJournalRepository
    private lateinit var sourceNotesRepo: RoundTripJournalNotesRepository
    private lateinit var sourceProfileRepo: RoundTripProfileRepository
    private lateinit var sourcePlacesRepo: RoundTripUserPlacesRepository
    private lateinit var sourceLocationHistoryRepo: RoundTripLocationHistoryRepository

    // Destination repositories (empty, used by import)
    private lateinit var destJournalRepo: RoundTripJournalRepository
    private lateinit var destNotesRepo: RoundTripJournalNotesRepository
    private lateinit var destContentRepo: RoundTripJournalContentRepository
    private lateinit var destProfileRepo: RoundTripProfileRepository
    private lateinit var destPlacesRepo: RoundTripUserPlacesRepository
    private lateinit var destLocationHistoryRepo: RoundTripLocationHistoryRepository

    private lateinit var exportUseCase: ExportArchiveUseCase
    private lateinit var importUseCase: RestoreUserDataUseCase
    private var archive = InMemoryArchiveContainer()

    private val now = Clock.System.now()

    // -- Fixed IDs for deterministic assertions --
    private val journalDaily = Uuid.random()
    private val journalTravel = Uuid.random()

    private val noteText = Uuid.random()
    private val noteImage = Uuid.random()
    private val noteVideo = Uuid.random()
    private val noteAudio = Uuid.random()
    private val noteTextWithLocation = Uuid.random()

    private val draftId = Uuid.random()

    private val sunsetJpeg = jpeg("sunset")
    private val wavesMp4 = mp4("waves")
    private val voiceMemoM4a = m4a("voice memo")
    private val sourceMedia =
        mapOf(
            "file:///storage/photos/sunset.jpg" to sunsetJpeg,
            "file:///storage/videos/waves.mp4" to wavesMp4,
            "file:///storage/audio/voice_memo.m4a" to voiceMemoM4a,
            "file:///storage/photos/draft_photo.jpg" to jpeg("draft photo"),
            "file:///storage/photos/draft_image.jpg" to jpeg("draft image"),
            "file:///storage/videos/draft_video.mp4" to mp4("draft video"),
            "file:///storage/videos/draft_video_thumb.jpg" to jpeg("draft video thumbnail"),
            "file:///storage/audio/draft_audio.m4a" to m4a("draft audio"),
        )

    /** Restores each archived file to a path named after it, as the app's importer copies it into storage. */
    private val restoredMedia =
        object : MediaImporter {
            override suspend fun importMedia(exportPath: String): String? = if (archive.has(exportPath)) "$RESTORED$exportPath" else null
        }

    @BeforeTest
    fun setUp() {
        sourceJournalRepo = RoundTripJournalRepository()
        sourceNotesRepo = RoundTripJournalNotesRepository()
        sourceProfileRepo = RoundTripProfileRepository()
        sourcePlacesRepo = RoundTripUserPlacesRepository()
        sourceLocationHistoryRepo = RoundTripLocationHistoryRepository()

        destJournalRepo = RoundTripJournalRepository()
        destNotesRepo = RoundTripJournalNotesRepository()
        destContentRepo = RoundTripJournalContentRepository()
        destProfileRepo = RoundTripProfileRepository()
        destPlacesRepo = RoundTripUserPlacesRepository()
        destLocationHistoryRepo = RoundTripLocationHistoryRepository()

        val appInfoProvider =
            StubAppInfoProvider(
                AppInfo(
                    versionName = "2.1.0",
                    versionCode = 210,
                    packageName = "app.logdate.test",
                ),
            )

        exportUseCase =
            ExportArchiveUseCase(
                journalRepository = sourceJournalRepo,
                journalNotesRepository = sourceNotesRepo,
                profileRepository = sourceProfileRepo,
                userPlacesRepository = sourcePlacesRepo,
                locationHistoryRepository = sourceLocationHistoryRepo,
                userStateRepository = StubUserStateRepository(),
                appInfoProvider = appInfoProvider,
                mediaSourceOpener = MediaSourceOpener { reference -> sourceMedia[reference]?.let { Buffer().write(it) as Source } },
                ioDispatcher = Dispatchers.Unconfined,
            )

        importUseCase =
            RestoreUserDataUseCase(
                journalRepository = destJournalRepo,
                journalNotesRepository = destNotesRepo,
                journalContentRepository = destContentRepo,
                profileRepository = destProfileRepo,
                userPlacesRepository = destPlacesRepo,
                locationHistoryRepository = destLocationHistoryRepo,
                canonicalOwnerProvider = RestoreTestOwnerProvider(),
                deviceIdProvider = RestoreTestDeviceIdProvider(),
            )
    }

    // region Round-trip tests

    @Test
    fun `full round-trip preserves all journals`() =
        runTest {
            seedSourceData()
            val result = exportThenImport()

            assertEquals(2, result.journalsImported)
            val daily = destJournalRepo.getJournalById(journalDaily)
            assertNotNull(daily, "Daily journal should exist after import")
            assertEquals("Daily Reflections", daily.title)
            assertEquals("A place for daily thoughts", daily.description)

            val travel = destJournalRepo.getJournalById(journalTravel)
            assertNotNull(travel, "Travel journal should exist after import")
            assertEquals("Road Trip 2026", travel.title)
        }

    @Test
    fun `full round-trip preserves text note content`() =
        runTest {
            seedSourceData()
            val result = exportThenImport()

            assertEquals(5, result.notesImported)
            val text = destNotesRepo.getNoteById(noteText)
            assertTrue(text is JournalNote.Text, "Should be text note")
            assertEquals("Today I learned about export pipelines. Fascinating stuff!", text.content)
        }

    @Test
    fun `full round-trip preserves image note with caption`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val image = destNotesRepo.getNoteById(noteImage)
            assertTrue(image is JournalNote.Image, "Should be image note")
            assertContentEquals(sunsetJpeg, archivedBytes(image.mediaRef))
            assertEquals("Golden hour at Ocean Beach", image.caption)
        }

    @Test
    fun `full round-trip preserves video note with caption`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val video = destNotesRepo.getNoteById(noteVideo)
            assertTrue(video is JournalNote.Video, "Should be video note")
            assertContentEquals(wavesMp4, archivedBytes(video.mediaRef))
            assertEquals("Waves crashing on the shore", video.caption)
        }

    @Test
    fun `full round-trip preserves audio note`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val audio = destNotesRepo.getNoteById(noteAudio)
            assertTrue(audio is JournalNote.Audio, "Should be audio note")
            assertContentEquals(voiceMemoM4a, archivedBytes(audio.mediaRef))
            assertEquals("A walk by the water", audio.caption)
            assertEquals(TranscriptDocument.fromPlainText("Spoken walk by the water").copy(revision = 12), audio.transcript)
            assertEquals(CaptureDevice.WATCH, audio.capturedOn)
        }

    @Test
    fun `full round-trip preserves note location and place name`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val note = destNotesRepo.getNoteById(noteTextWithLocation)
            assertTrue(note is JournalNote.Text, "Should be text note")
            assertNotNull(note.location, "Location should be preserved")
            assertEquals(37.7749, note.location!!.coordinates?.latitude)
            assertEquals(-122.4194, note.location!!.coordinates?.longitude)
            assertEquals("San Francisco", note.location!!.displayName)
        }

    @Test
    fun `full round-trip preserves journal-note relations`() =
        runTest {
            seedSourceData()
            val result = exportThenImport()

            assertTrue(result.journalLinksImported > 0, "Should import journal-note relations")
            val links = destContentRepo.allLinks()
            assertTrue(
                links.any { it.first == noteText && it.second == journalDaily },
                "Text note should be linked to Daily journal",
            )
            assertTrue(
                links.any { it.first == noteImage && it.second == journalTravel },
                "Image note should be linked to Travel journal",
            )
        }

    @Test
    fun `full round-trip preserves drafts`() =
        runTest {
            seedSourceData()
            val result = exportThenImport()

            assertEquals(1, result.draftsImported)
            val drafts = destJournalRepo.getAllDrafts()
            assertEquals(1, drafts.size)
            val draft = drafts.first()
            assertTrue(
                draft.blocks.any { it is SerializableTextBlock && it.content.contains("work in progress") },
                "Draft text block should be preserved",
            )
            assertTrue(draft.blocks.any { it is SerializableImageBlock }, "Image draft block should be preserved")
            assertTrue(draft.blocks.any { it is SerializableVideoBlock }, "Video draft block should be preserved")
            assertTrue(draft.blocks.any { it is SerializableAudioBlock }, "Audio draft block should be preserved")
        }

    @Test
    fun `full round-trip restores notes as not yet synced on this device`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val text = destNotesRepo.getNoteById(noteText)
            assertTrue(text is JournalNote.Text)
            assertEquals(0, text.syncVersion, "an archive does not carry the source device's sync versions")

            val image = destNotesRepo.getNoteById(noteImage)
            assertTrue(image is JournalNote.Image)
            assertEquals(0, image.syncVersion)
        }

    @Test
    fun `full round-trip metadata counts match source data`() =
        runTest {
            seedSourceData()
            val result = exportThenImport()

            assertEquals(2, result.metadata.stats.journalCount)
            assertEquals(5, result.metadata.stats.noteCount)
            assertEquals(1, result.metadata.stats.draftCount)
            assertEquals(1, result.metadata.stats.placeCount)
            assertEquals(1, result.metadata.stats.locationHistoryCount)
            assertTrue(result.metadata.stats.hasProfile)
            assertTrue(result.warnings.isEmpty(), "Round-trip should produce no warnings, got: ${result.warnings}")
        }

    @Test
    fun `full round-trip preserves profile places and location history`() =
        runTest {
            seedSourceData()
            exportThenImport()

            assertEquals("Willie", destProfileRepo.profile.displayName)
            assertEquals(Instant.parse("1992-06-03T00:00:00Z"), destProfileRepo.profile.birthday)
            assertEquals("Keeps travel journals", destProfileRepo.profile.bio)
            assertEquals("I remember every trip", destProfileRepo.profile.originalBio)
            assertEquals(1, destPlacesRepo.places.size)
            assertEquals("Home Base", (destPlacesRepo.places.first() as Place.UserDefined).displayName)
            assertEquals(1, destLocationHistoryRepo.entries.size)
            val sample = destLocationHistoryRepo.entries.single()
            val source = sourceLocationHistoryRepo.entries.single()
            assertEquals(source.timestamp, sample.timestamp)
            assertEquals(source.location.latitude, sample.location.latitude)
            assertEquals(source.location.longitude, sample.location.longitude)
            assertEquals(source.accuracyMeters, sample.accuracyMeters)
        }

    @Test
    fun `full round-trip with media importer resolves paths`() =
        runTest {
            seedSourceData()

            val bundle = export()
            val mediaImporter =
                object : MediaImporter {
                    override suspend fun importMedia(exportPath: String): String? =
                        if (archive.has(exportPath) && archive.bytes(exportPath).contentEquals(sunsetJpeg)) {
                            "content://media/imported/sunset_new.jpg"
                        } else {
                            null
                        }
                }

            val result = importUseCase.restore(bundle, mediaImporter = mediaImporter)

            val image = destNotesRepo.getNoteById(noteImage)
            assertTrue(image is JournalNote.Image)
            // The importer received the archive path that holds the photo's bytes.
            assertEquals("content://media/imported/sunset_new.jpg", image.mediaRef)
            assertTrue(result.mediaImported > 0)
        }

    @Test
    fun `round-trip with REPLACE_EXISTING overwrites pre-existing data`() =
        runTest {
            seedSourceData()
            val bundle = export()

            // Pre-populate destination with stale data
            destJournalRepo.create(
                Journal(
                    id = journalDaily,
                    title = "Old Title",
                    description = "Old description",
                    lastUpdated = now - 30.days,
                ),
            )

            val result =
                importUseCase.restore(
                    bundle,
                    options = RestoreOptions(strategy = RestoreStrategy.REPLACE_EXISTING),
                    mediaImporter = restoredMedia,
                )

            assertEquals(2, result.journalsImported)
            val updated = destJournalRepo.getJournalById(journalDaily)
            assertEquals("Daily Reflections", updated?.title, "Title should be overwritten")
        }

    @Test
    fun `round-trip with MERGE_KEEP_NEWEST keeps newer local data`() =
        runTest {
            seedSourceData()
            val bundle = export()

            // Pre-populate destination with NEWER data
            destJournalRepo.create(
                Journal(
                    id = journalDaily,
                    title = "Locally Updated Title",
                    description = "Locally updated",
                    lastUpdated = now + 1.days,
                ),
            )

            val result =
                importUseCase.restore(
                    bundle,
                    options = RestoreOptions(strategy = RestoreStrategy.MERGE_KEEP_NEWEST),
                    mediaImporter = restoredMedia,
                )

            val local = destJournalRepo.getJournalById(journalDaily)
            assertEquals("Locally Updated Title", local?.title, "Newer local title should be kept")
        }

    @Test
    fun `round-trip preserves note timestamps`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val text = destNotesRepo.getNoteById(noteText) as JournalNote.Text
            val sourceNote = sourceNotesRepo.getNoteById(noteText) as JournalNote.Text

            assertEquals(sourceNote.creationTimestamp, text.creationTimestamp)
            assertEquals(sourceNote.lastUpdated, text.lastUpdated)
        }

    @Test
    fun `round-trip preserves image location`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val image = destNotesRepo.getNoteById(noteImage) as JournalNote.Image
            assertNotNull(image.location, "Image location should be preserved")
            assertEquals(34.0522, image.location!!.coordinates?.latitude)
            assertEquals(-118.2437, image.location!!.coordinates?.longitude)
        }

    @Test
    fun `round-trip with empty data succeeds with zero counts`() =
        runTest {
            // Don't seed any data — source repos are empty
            val result = exportThenImport()

            assertEquals(0, result.journalsImported)
            assertEquals(0, result.notesImported)
            assertEquals(0, result.draftsImported)
            assertEquals(0, result.journalLinksImported)
            assertTrue(result.warnings.isEmpty())
        }

    @Test
    fun `round-trip note appears in multiple journals`() =
        runTest {
            seedSourceData()
            val result = exportThenImport()

            // noteTextWithLocation is in both journals
            val links = destContentRepo.allLinks()
            val linkedJournals = links.filter { it.first == noteTextWithLocation }.map { it.second }
            assertTrue(linkedJournals.contains(journalDaily), "Should be linked to Daily journal")
            assertTrue(linkedJournals.contains(journalTravel), "Should be linked to Travel journal")
        }

    @Test
    fun `round-trip preserves audio note duration`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val audio = destNotesRepo.getNoteById(noteAudio)
            assertTrue(audio is JournalNote.Audio, "Should be audio note")
            assertEquals(45000L, audio.durationMs, "Audio duration should survive round-trip")
        }

    @Test
    fun `round-trip preserves location altitude and accuracy`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val note = destNotesRepo.getNoteById(noteTextWithLocation)
            assertTrue(note is JournalNote.Text, "Should be text note")
            val coords = note.location?.coordinates
            assertNotNull(coords, "Coordinates should be preserved")
            assertEquals(52.3, coords.altitude, "Altitude should survive round-trip")
            assertEquals(8.5f, coords.accuracy, "Accuracy should survive round-trip")
        }

    @Test
    fun `round-trip preserves draft with multiple journal associations`() =
        runTest {
            seedSourceData()
            exportThenImport()

            val drafts = destJournalRepo.getAllDrafts()
            assertEquals(1, drafts.size, "Should have one draft")
            val draft = drafts.first()
            assertEquals(2, draft.selectedJournalIds.size, "Draft should keep both journal IDs")
            assertTrue(draft.selectedJournalIds.contains(journalDaily))
            assertTrue(draft.selectedJournalIds.contains(journalTravel))
        }

    // endregion

    // region Helpers

    private fun seedSourceData() {
        val dailyJournal =
            Journal(
                id = journalDaily,
                title = "Daily Reflections",
                description = "A place for daily thoughts",
                created = now - 90.days,
                lastUpdated = now - 1.hours,
            )
        val travelJournal =
            Journal(
                id = journalTravel,
                title = "Road Trip 2026",
                description = "Cross-country adventure",
                created = now - 14.days,
                lastUpdated = now - 2.days,
            )

        sourceJournalRepo.testJournals = listOf(dailyJournal, travelJournal)

        val sfLocation =
            NoteLocation(
                coordinates =
                    NoteCoordinates(
                        latitude = 37.7749,
                        longitude = -122.4194,
                        altitude = 52.3,
                        accuracy = 8.5f,
                    ),
                place =
                    NotePlace(
                        id = Uuid.random(),
                        name = "San Francisco",
                        latitude = 37.7749,
                        longitude = -122.4194,
                    ),
            )
        val laLocation =
            NoteLocation(
                coordinates = NoteCoordinates(latitude = 34.0522, longitude = -118.2437),
                place =
                    NotePlace(
                        id = Uuid.random(),
                        name = "Los Angeles",
                        latitude = 34.0522,
                        longitude = -118.2437,
                    ),
            )

        val textNote =
            JournalNote.Text(
                uid = noteText,
                creationTimestamp = now - 7.days,
                lastUpdated = now - 7.days,
                content = "Today I learned about export pipelines. Fascinating stuff!",
                syncVersion = 7,
            )
        val imageNote =
            JournalNote.Image(
                uid = noteImage,
                creationTimestamp = now - 5.days,
                lastUpdated = now - 5.days,
                mediaRef = "file:///storage/photos/sunset.jpg",
                caption = "Golden hour at Ocean Beach",
                location = laLocation,
                syncVersion = 11,
            )
        val videoNote =
            JournalNote.Video(
                uid = noteVideo,
                creationTimestamp = now - 3.days,
                lastUpdated = now - 3.days,
                mediaRef = "file:///storage/videos/waves.mp4",
                caption = "Waves crashing on the shore",
                syncVersion = 13,
            )
        val audioNote =
            JournalNote.Audio(
                uid = noteAudio,
                creationTimestamp = now - 2.days,
                lastUpdated = now - 2.days,
                mediaRef = "file:///storage/audio/voice_memo.m4a",
                durationMs = 45000,
                syncVersion = 17,
                caption = "A walk by the water",
                transcript = TranscriptDocument.fromPlainText("Spoken walk by the water").copy(revision = 12),
                capturedOn = CaptureDevice.WATCH,
            )
        val textWithLocationNote =
            JournalNote.Text(
                uid = noteTextWithLocation,
                creationTimestamp = now - 1.days,
                lastUpdated = now - 1.days,
                content = "Sitting in Dolores Park writing in my journal",
                location = sfLocation,
                syncVersion = 19,
            )

        sourceNotesRepo.testNotes = listOf(textNote, imageNote, videoNote, audioNote, textWithLocationNote)
        sourceNotesRepo.notesByJournal =
            mapOf(
                journalDaily to listOf(textNote, audioNote, textWithLocationNote),
                journalTravel to listOf(imageNote, videoNote, textWithLocationNote),
            )
        sourceProfileRepo.profile =
            LogDateProfile(
                displayName = "Willie",
                bio = "Keeps travel journals",
                originalBio = "I remember every trip",
                birthday = Instant.parse("1992-06-03T00:00:00Z"),
                createdAt = now - 30.days,
                lastUpdatedAt = now - 1.days,
            )
        sourcePlacesRepo.places =
            listOf(
                Place.UserDefined(
                    id = Uuid.random(),
                    displayName = "Home Base",
                    lat = 37.7749,
                    lng = -122.4194,
                    radiusMeters = 150.0,
                    description = "Apartment",
                ),
            )
        sourceLocationHistoryRepo.entries =
            listOf(
                LocationHistoryItem(
                    sampleId = "sample-1",
                    userId = "test-user",
                    deviceId = "device-1",
                    timestamp = now - 1.days,
                    loggedAt = now - 1.days,
                    location =
                        Location(
                            latitude = 37.7749,
                            longitude = -122.4194,
                            altitude = LocationAltitude(12.0, AltitudeUnit.METERS),
                        ),
                    confidence = 0.85f,
                    isGenuine = true,
                    capturePipeline = LocationCapturePipeline.HIGH_DETAIL,
                    captureSource = LocationCaptureSource.MANUAL,
                    accuracyMeters = 6.0f,
                ),
            )

        val draft =
            EditorDraft(
                id = draftId,
                blocks =
                    listOf(
                        SerializableTextBlock(
                            id = Uuid.random(),
                            timestamp = now,
                            locationLat = 37.7749,
                            locationLng = -122.4194,
                            content = "This is a work in progress entry about my day",
                        ),
                        SerializableCameraBlock(
                            id = Uuid.random(),
                            timestamp = now,
                            uri = "file:///storage/photos/draft_photo.jpg",
                        ),
                        SerializableImageBlock(
                            id = Uuid.random(),
                            timestamp = now,
                            uri = "file:///storage/photos/draft_image.jpg",
                            caption = "Draft image",
                        ),
                        SerializableVideoBlock(
                            id = Uuid.random(),
                            timestamp = now,
                            uri = "file:///storage/videos/draft_video.mp4",
                            thumbnailUri = "file:///storage/videos/draft_video_thumb.jpg",
                            caption = "Draft video",
                        ),
                        SerializableAudioBlock(
                            id = Uuid.random(),
                            timestamp = now,
                            uri = "file:///storage/audio/draft_audio.m4a",
                            duration = 1234L,
                            transcription = "draft audio",
                        ),
                    ),
                selectedJournalIds = listOf(journalDaily, journalTravel),
                createdAt = now - 1.hours,
                lastModifiedAt = now,
            )
        sourceJournalRepo.testDrafts = listOf(draft)
    }

    private suspend fun export(): RestoreArchiveBundle {
        archive = InMemoryArchiveContainer()
        val last = exportUseCase.export(ArchiveExportOptions(), archive).toList().last()
        assertTrue(last is ArchiveExportProgress.Completed, "Export should complete, got: $last")
        return RestoreArchiveReader.read(archive.paths) { path -> if (archive.has(path)) archive.text(path) else null }
    }

    private suspend fun exportThenImport(): app.logdate.client.domain.restore.RestoreResult =
        importUseCase.restore(export(), mediaImporter = restoredMedia)

    private fun archivedBytes(restoredReference: String): ByteArray {
        assertTrue(restoredReference.startsWith(RESTORED), "Media was not restored from the archive: $restoredReference")
        return archive.bytes(restoredReference.removePrefix(RESTORED))
    }

    private fun jpeg(label: String) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + label.encodeToByteArray()

    private fun mp4(label: String) = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".encodeToByteArray() + ByteArray(12) + label.encodeToByteArray()

    private fun m4a(label: String) = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".encodeToByteArray() + ByteArray(12) + label.encodeToByteArray()

    // endregion

    private companion object {
        const val RESTORED = "file:///restored/"
    }
}
