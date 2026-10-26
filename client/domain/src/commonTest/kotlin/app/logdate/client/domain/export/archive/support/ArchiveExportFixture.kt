package app.logdate.client.domain.export.archive.support

import app.logdate.client.device.AppInfo
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.domain.export.support.RoundTripJournalNotesRepository
import app.logdate.client.domain.export.support.RoundTripJournalRepository
import app.logdate.client.domain.export.support.RoundTripLocationHistoryRepository
import app.logdate.client.domain.export.support.RoundTripProfileRepository
import app.logdate.client.domain.export.support.RoundTripUserPlacesRepository
import app.logdate.client.domain.export.support.StubAppInfoProvider
import app.logdate.client.domain.export.support.StubUserStateRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.PhotoPresentation
import app.logdate.shared.model.Place
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.TimeZone
import okio.Buffer
import okio.Source
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A small, realistic set of app data with poisoned device references, shared by the export tests. */
open class ArchiveExportFixture {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "jpeg-bytes".encodeToByteArray()
    val m4a = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".encodeToByteArray() + ByteArray(24)

    val instantOf = { text: String -> Instant.parse(text) }
    val denver = TimeZone.of("America/Denver")

    val daily =
        Journal(
            id = Uuid.random(),
            title = "Daily",
            description = "",
            created = instantOf("2026-01-01T00:00:00Z"),
            lastUpdated = instantOf("2026-01-02T00:00:00Z"),
            coverImageUri = "content://media/external/images/media/55",
        )
    val travel =
        Journal(
            id = Uuid.random(),
            title = "Travel",
            description = "Trips",
            created = instantOf("2026-01-01T00:00:00Z"),
            lastUpdated = instantOf("2026-01-02T00:00:00Z"),
        )

    val imageReference = "content://media/external/images/media/1000025292"
    val audioReference = "/data/user/0/app.logdate/files/audio_notes/recording_abc.m4a"
    val videoReference = "file:///var/mobile/Containers/Data/Application/XYZ/Documents/imports/clip.mov"
    val draftReference = "content://media/external/images/media/77"

    val textNote =
        JournalNote.Text(
            uid = Uuid.random(),
            creationTimestamp = instantOf("2026-09-18T03:30:05Z"),
            lastUpdated = instantOf("2026-09-18T03:30:05Z"),
            content = "Walk by the *river* http://example.com",
            timeZoneId = "Asia/Tokyo",
        )
    val imageNote =
        JournalNote.Image(
            uid = Uuid.random(),
            creationTimestamp = instantOf("2026-09-18T03:30:05Z"),
            lastUpdated = instantOf("2026-09-18T03:30:05Z"),
            mediaRef = imageReference,
            caption = "Sunset",
            presentation = PhotoPresentation.Framed,
        )
    val audioNote =
        JournalNote.Audio(
            uid = Uuid.random(),
            creationTimestamp = instantOf("2026-09-19T10:00:00Z"),
            lastUpdated = instantOf("2026-09-19T10:00:00Z"),
            mediaRef = audioReference,
            durationMs = 4_000,
        )
    val videoNote =
        JournalNote.Video(
            uid = Uuid.random(),
            creationTimestamp = instantOf("2026-09-20T10:00:00Z"),
            lastUpdated = instantOf("2026-09-20T10:00:00Z"),
            mediaRef = videoReference,
        )

    val journalsRepository =
        RoundTripJournalRepository().apply {
            testJournals = listOf(daily, travel)
            testDrafts =
                listOf(
                    EditorDraft(
                        blocks =
                            listOf(
                                SerializableImageBlock(
                                    id = Uuid.random(),
                                    timestamp = instantOf("2026-09-17T10:00:00Z"),
                                    uri = draftReference,
                                ),
                            ),
                        createdAt = instantOf("2026-09-17T10:00:00Z"),
                        lastModifiedAt = instantOf("2026-09-17T10:00:00Z"),
                    ),
                )
        }
    val notesRepository =
        RoundTripJournalNotesRepository().apply {
            testNotes = listOf(textNote, imageNote, audioNote, videoNote)
            notesByJournal = mapOf(daily.id to listOf(textNote, imageNote), travel.id to listOf(textNote, videoNote))
        }
    val profileRepository =
        RoundTripProfileRepository().apply {
            profile =
                LogDateProfile(
                    displayName = "Sam",
                    profilePhotoUri = "content://media/external/images/media/99",
                    createdAt = instantOf("2026-01-01T00:00:00Z"),
                )
        }
    val placesRepository =
        RoundTripUserPlacesRepository().apply {
            places = listOf(Place.UserDefined(id = Uuid.random(), displayName = "Home", lat = 39.7, lng = -104.9))
        }
    val locationRepository =
        RoundTripLocationHistoryRepository().apply {
            entries =
                listOf(
                    LocationHistoryItem(
                        sampleId = "sample-1",
                        userId = "user-1",
                        deviceId = "device-1",
                        timestamp = instantOf("2026-09-18T04:00:00Z"),
                        location =
                            Location(
                                latitude = 39.7392,
                                longitude = -104.9903,
                                altitude = LocationAltitude(1609.3, AltitudeUnit.METERS),
                            ),
                        confidence = 0.85f,
                        isGenuine = true,
                        capturePipeline = LocationCapturePipeline.HIGH_DETAIL,
                        captureSource = LocationCaptureSource.MANUAL,
                        accuracyMeters = 6.0f,
                    ),
                )
        }

    class Files(
        val files: Map<String, ByteArray>,
    ) : MediaSourceOpener {
        val opened = mutableListOf<String>()

        override suspend fun open(reference: String): Source? {
            opened += reference
            return files[reference]?.let { Buffer().write(it) }
        }
    }

    val readable = Files(mapOf(imageReference to jpeg, audioReference to m4a, draftReference to jpeg))

    val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-21T15:00:00Z")
        }

    fun useCase(
        opener: MediaSourceOpener = readable,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        clock: Clock = fixedClock,
    ) = ExportArchiveUseCase(
        journalRepository = journalsRepository,
        journalNotesRepository = notesRepository,
        profileRepository = profileRepository,
        userPlacesRepository = placesRepository,
        locationHistoryRepository = locationRepository,
        userStateRepository = StubUserStateRepository(),
        appInfoProvider = StubAppInfoProvider(AppInfo(versionName = "9.9.9", versionCode = 1, packageName = "app.logdate.test")),
        mediaSourceOpener = opener,
        clock = clock,
        exportZone = { denver },
        ioDispatcher = ioDispatcher,
    )
}
