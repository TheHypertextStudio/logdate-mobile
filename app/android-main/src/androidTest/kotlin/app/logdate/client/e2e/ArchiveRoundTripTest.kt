package app.logdate.client.e2e

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.logdate.client.data.journals.OfflineFirstJournalContentRepository
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.data.notes.EmptyNotePlaceResolver
import app.logdate.client.data.notes.OfflineFirstJournalNotesRepository
import app.logdate.client.data.places.InMemoryUserPlacesRepository
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.AppInfo
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.archive.ArchiveExportOptions
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.RestoreArchiveBundle
import app.logdate.client.domain.restore.RestoreArchiveReader
import app.logdate.client.domain.restore.RestoreOptions
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.media.AndroidMediaManager
import app.logdate.client.media.MediaManager
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.feature.core.export.ZipStreamArchiveContainer
import app.logdate.feature.core.restore.RestoreLauncher
import app.logdate.feature.core.restore.RestoreWorker
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.profile.LogDateProfile
import app.logdate.shared.model.user.UserData
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Instrumented end-to-end test for ZIP archive round-trip fidelity.
 *
 * Unlike [ExportImportE2ETest], which exercises the use-case layer directly,
 * these tests go through the full stack: data is exported to an actual ZIP file
 * on disk using the same serialization logic as [ExportWorker], then the archive
 * is opened and parsed with [ZipFile] exactly as [RestoreWorker] does, and the
 * resulting bundle is fed to [RestoreUserDataUseCase]. This proves that the
 * JSON serialization, ZIP entry naming, and ZIP file reading all form a
 * lossless round-trip.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var sourceDb: LogDateDatabase
    private lateinit var destDb: LogDateDatabase

    private lateinit var sourceJournalRepo: OfflineFirstJournalRepository
    private lateinit var sourceNotesRepo: OfflineFirstJournalNotesRepository
    private lateinit var sourceContentRepo: OfflineFirstJournalContentRepository
    private lateinit var exportUseCase: ExportArchiveUseCase
    private val sourceMedia = mutableMapOf<String, ByteArray>()
    private val restoredMedia = mutableMapOf<String, ByteArray>()

    private lateinit var destJournalRepo: OfflineFirstJournalRepository
    private lateinit var destNotesRepo: OfflineFirstJournalNotesRepository
    private lateinit var destContentRepo: OfflineFirstJournalContentRepository
    private lateinit var restoreUseCase: RestoreUserDataUseCase

    @Before
    fun setup() {
        val dispatcher = Dispatchers.Unconfined

        sourceDb =
            Room
                .inMemoryDatabaseBuilder(context, LogDateDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        destDb =
            Room
                .inMemoryDatabaseBuilder(context, LogDateDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val sourceSyncMeta = InMemorySyncMetadataService()
        val sourceDraftRepo = InMemoryDraftRepository()

        sourceJournalRepo =
            OfflineFirstJournalRepository(
                journalDao = sourceDb.journalDao(),
                remoteDataSource = NoOpRemoteJournalDataSource(),
                draftRepository = sourceDraftRepo,
                syncMetadataService = sourceSyncMeta,
                dispatcher = dispatcher,
                externalScope = kotlinx.coroutines.CoroutineScope(dispatcher),
            )

        sourceNotesRepo =
            OfflineFirstJournalNotesRepository(
                textNoteDao = sourceDb.textNoteDao(),
                imageNoteDao = sourceDb.imageNoteDao(),
                audioNoteDao = sourceDb.audioNoteDao(),
                videoNoteDao = sourceDb.videoNoteDao(),
                journalContentDao = sourceDb.journalContentDao(),
                journalRepository = sourceJournalRepo,
                mediaCaptionDao = sourceDb.mediaCaptionDao(),
                notePlaceResolver = EmptyNotePlaceResolver,
                syncMetadataService = sourceSyncMeta,
                syncScope = kotlinx.coroutines.CoroutineScope(dispatcher),
            )

        sourceContentRepo =
            OfflineFirstJournalContentRepository(
                journalContentDao = sourceDb.journalContentDao(),
                journalRepository = sourceJournalRepo,
                journalNotesRepository = sourceNotesRepo,
                syncMetadataService = sourceSyncMeta,
                dispatcher = dispatcher,
            )

        exportUseCase =
            ExportArchiveUseCase(
                journalRepository = sourceJournalRepo,
                journalNotesRepository = sourceNotesRepo,
                profileRepository = StoredProfileRepository(),
                userPlacesRepository = InMemoryUserPlacesRepository(),
                locationHistoryRepository = InMemoryLocationHistoryRepository(),
                userStateRepository = InMemoryUserStateRepository(),
                appInfoProvider = FixedAppInfoProvider(),
                mediaSourceOpener = MediaSourceOpener { reference -> sourceMedia[reference]?.let { Buffer().write(it) as Source } },
            )

        val destSyncMeta = InMemorySyncMetadataService()
        val destDraftRepo = InMemoryDraftRepository()

        destJournalRepo =
            OfflineFirstJournalRepository(
                journalDao = destDb.journalDao(),
                remoteDataSource = NoOpRemoteJournalDataSource(),
                draftRepository = destDraftRepo,
                syncMetadataService = destSyncMeta,
                dispatcher = dispatcher,
                externalScope = kotlinx.coroutines.CoroutineScope(dispatcher),
            )

        destNotesRepo =
            OfflineFirstJournalNotesRepository(
                textNoteDao = destDb.textNoteDao(),
                imageNoteDao = destDb.imageNoteDao(),
                audioNoteDao = destDb.audioNoteDao(),
                videoNoteDao = destDb.videoNoteDao(),
                journalContentDao = destDb.journalContentDao(),
                journalRepository = destJournalRepo,
                mediaCaptionDao = destDb.mediaCaptionDao(),
                notePlaceResolver = EmptyNotePlaceResolver,
                syncMetadataService = destSyncMeta,
                syncScope = kotlinx.coroutines.CoroutineScope(dispatcher),
            )

        destContentRepo =
            OfflineFirstJournalContentRepository(
                journalContentDao = destDb.journalContentDao(),
                journalRepository = destJournalRepo,
                journalNotesRepository = destNotesRepo,
                syncMetadataService = destSyncMeta,
                dispatcher = dispatcher,
            )

        restoreUseCase =
            RestoreUserDataUseCase(
                journalRepository = destJournalRepo,
                journalNotesRepository = destNotesRepo,
                journalContentRepository = destContentRepo,
                profileRepository = StoredProfileRepository(),
                userPlacesRepository = InMemoryUserPlacesRepository(),
                locationHistoryRepository = InMemoryLocationHistoryRepository(),
                canonicalOwnerProvider = FixedCanonicalOwnerProvider(),
                deviceIdProvider = FixedDeviceIdProvider(),
            )
    }

    @After
    fun teardown() {
        sourceDb.close()
        destDb.close()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun Instant.truncateToMillis(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())

    private fun now(): Instant = Clock.System.now().truncateToMillis()

    /**
     * Exports from the source database to a real ZIP archive on disk, then reads
     * that archive back and restores into the destination database — the exact path
     * [ExportWorker] and [RestoreWorker] take in production. Restored media is recorded
     * under the reference the restore assigned it.
     */
    private suspend fun archiveRoundTrip(options: RestoreOptions = RestoreOptions()) {
        val archiveFile = File.createTempFile("roundtrip_test", ".zip", context.cacheDir)
        try {
            ZipOutputStream(FileOutputStream(archiveFile)).use { zip -> writeArchive(zip) }
            ZipFile(archiveFile).use { zip ->
                val bundle =
                    RestoreArchiveReader.read(zip.entries().toList().map { it.name }) { name ->
                        zip.getEntry(name)?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                    }
                val importer =
                    object : MediaImporter {
                        override suspend fun importMedia(exportPath: String): String? {
                            val entry = zip.getEntry(exportPath) ?: return null
                            val reference = "file:///restored/$exportPath"
                            restoredMedia[reference] = zip.getInputStream(entry).use { it.readBytes() }
                            return reference
                        }
                    }
                restoreUseCase.restore(bundle, options, mediaImporter = importer)
            }
        } finally {
            archiveFile.delete()
        }
    }

    private suspend fun writeArchive(zip: ZipOutputStream) {
        val progress = exportUseCase.export(ArchiveExportOptions(), ZipStreamArchiveContainer(zip)).last()
        check(progress is ArchiveExportProgress.Completed) { "Export did not complete: $progress" }
    }

    private fun writeStoredPadding(
        zip: ZipOutputStream,
        sizeBytes: Long,
    ) {
        val chunk = ByteArray(64 * 1024) { index -> ((index * 31 + 7) and 0xff).toByte() }
        val crc = CRC32()
        var remaining = sizeBytes
        while (remaining > 0) {
            val count = min(chunk.size.toLong(), remaining).toInt()
            crc.update(chunk, 0, count)
            remaining -= count
        }

        zip.putNextEntry(
            ZipEntry("padding.bin").apply {
                method = ZipEntry.STORED
                size = sizeBytes
                compressedSize = sizeBytes
                this.crc = crc.value
            },
        )
        remaining = sizeBytes
        while (remaining > 0) {
            val count = min(chunk.size.toLong(), remaining).toInt()
            zip.write(chunk, 0, count)
            remaining -= count
        }
        zip.closeEntry()
    }

    private data class NetworkState(
        val airplaneModeEnabled: Boolean,
        val wifiEnabled: Boolean,
    )

    private fun networkState(context: Context): NetworkState =
        NetworkState(
            airplaneModeEnabled =
                Settings.Global.getInt(
                    context.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    0,
                ) != 0,
            wifiEnabled = context.getSystemService(WifiManager::class.java).isWifiEnabled,
        )

    private fun setNetworkEnabled(
        device: UiDevice,
        enabled: Boolean,
    ) {
        val mode = if (enabled) "disable" else "enable"
        device.executeShellCommand("cmd connectivity airplane-mode $mode")
        device.executeShellCommand(if (enabled) "svc wifi enable" else "svc wifi disable")
    }

    private fun restoreNetworkState(
        device: UiDevice,
        state: NetworkState,
    ) {
        val airplaneMode = if (state.airplaneModeEnabled) "enable" else "disable"
        device.executeShellCommand("cmd connectivity airplane-mode $airplaneMode")
        device.executeShellCommand(if (state.wifiEnabled) "svc wifi enable" else "svc wifi disable")
    }

    private fun assertDeviceOffline(context: Context) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (connectivityManager.activeNetwork != null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250L)
        }

        assertEquals(
            1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0),
            "airplane mode must be enabled before reading restored media",
        )
        assertFalse(
            context.getSystemService(WifiManager::class.java).isWifiEnabled,
            "Wi-Fi must be disabled before reading restored media",
        )
        assertNull(connectivityManager.activeNetwork, "device still has an active network")
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun portableArchiveMetadataSurvivesTheProductionRepositories() =
        runTest {
            val at = now()
            val placeId = Uuid.random()
            val journal = Journal(id = Uuid.random(), title = "Portable metadata", created = at, lastUpdated = at)
            sourceJournalRepo.create(journal)
            val location =
                NoteLocation(
                    NoteCoordinates(36.1699, -115.1398, 610.25, 3.5f),
                    NotePlace(placeId, "Home", 36.17, -115.14),
                )
            val text = JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "Measured here", location = location)
            val audio =
                JournalNote.Audio(
                    creationTimestamp = at,
                    lastUpdated = at,
                    mediaRef = "content://synthetic/audio",
                    durationMs = 4_000,
                    transcription = "  Original words.\nKeep spacing.  ",
                )
            val video =
                JournalNote.Video(
                    creationTimestamp = at,
                    lastUpdated = at,
                    mediaRef = "content://synthetic/video",
                    durationMs = 12_345,
                )
            listOf(text, audio, video).forEach { sourceNotesRepo.create(it, journal.id) }
            val exporter =
                ExportArchiveUseCase(
                    journalRepository = sourceJournalRepo,
                    journalNotesRepository = sourceNotesRepo,
                    profileRepository = StoredProfileRepository(),
                    userPlacesRepository = InMemoryUserPlacesRepository(),
                    locationHistoryRepository = InMemoryLocationHistoryRepository(),
                    userStateRepository = InMemoryUserStateRepository(),
                    appInfoProvider = FixedAppInfoProvider(),
                    mediaSourceOpener =
                        object : MediaSourceOpener {
                            override suspend fun open(reference: String): Source? = null
                        },
                )
            val file = File.createTempFile("portable-metadata-", ".zip", context.cacheDir)
            try {
                ZipOutputStream(FileOutputStream(file)).use { zip ->
                    val progress =
                        exporter
                            .export(
                                ArchiveExportOptions(),
                                ZipStreamArchiveContainer(zip),
                            ).last()
                    assertIs<ArchiveExportProgress.Completed>(progress)
                }
                ZipFile(file).use { zip ->
                    val bundle =
                        RestoreArchiveReader.read(zip.entries().toList().map { it.name }) { name ->
                            zip.getEntry(name)?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                        }
                    val current = assertIs<RestoreArchiveBundle.V2>(bundle)
                    val invalid = current.bundle.copy(notesJson = current.bundle.notesJson!!.replace(placeId.toString(), "not-a-uuid"))
                    assertFailsWith<IllegalArgumentException> { restoreUseCase.restore(invalid) }
                    assertNull(destJournalRepo.getJournalById(journal.id), "Invalid canonical data must not create the earlier journal")
                    assertNull(destNotesRepo.getNoteById(text.uid))
                    val result = restoreUseCase.restore(bundle)
                    assertEquals(ExportSchemaVersion.V2_1, result.metadata.version)
                    assertEquals(3, result.notesImported)
                    assertEquals(2, result.unavailableMedia.size)
                }
                assertEquals(location, destNotesRepo.getNoteById(text.uid)?.location)
                assertEquals(audio.transcription, assertIs<JournalNote.Audio>(destNotesRepo.getNoteById(audio.uid)).transcription)
                assertEquals(video.durationMs, assertIs<JournalNote.Video>(destNotesRepo.getNoteById(video.uid)).durationMs)
                assertEquals(3, destContentRepo.observeContentForJournal(journal.id).first().size)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `archive all content types survive round trip`() =
        runTest {
            val now = now()

            val journal1 = Journal(id = Uuid.random(), title = "Daily", created = now, lastUpdated = now)
            val journal2 = Journal(id = Uuid.random(), title = "Travel", created = now - 48.hours, lastUpdated = now)
            sourceJournalRepo.create(journal1)
            sourceJournalRepo.create(journal2)

            val textNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now - 2.hours,
                    lastUpdated = now,
                    content = "A regular text entry",
                )
            val imageNote =
                JournalNote.Image(
                    uid = Uuid.random(),
                    creationTimestamp = now - 1.hours,
                    lastUpdated = now,
                    mediaRef = "content://media/external/images/1",
                    caption = "Sunrise",
                )
            val audioNote =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "content://media/external/audio/1",
                    durationMs = 30000,
                )
            val videoNote =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "content://media/external/video/1",
                    caption = "Timelapse",
                )
            val geoNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "At the park",
                    location = NoteLocation(coordinates = NoteCoordinates(latitude = 51.5074, longitude = -0.1278)),
                )

            sourceNotesRepo.create(textNote, journal1.id)
            sourceNotesRepo.create(imageNote, journal1.id)
            sourceNotesRepo.create(audioNote, journal2.id)
            sourceNotesRepo.create(videoNote, journal2.id)
            sourceNotesRepo.create(geoNote, journal1.id)
            val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "jpeg".encodeToByteArray()
            val audioBytes = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".encodeToByteArray() + ByteArray(12)
            val videoBytes = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".encodeToByteArray() + ByteArray(12)
            sourceMedia[imageNote.mediaRef] = imageBytes
            sourceMedia[audioNote.mediaRef] = audioBytes
            sourceMedia[videoNote.mediaRef] = videoBytes

            archiveRoundTrip()

            // Journals
            assertNotNull(destJournalRepo.getJournalById(journal1.id), "journal1 missing after archive round-trip")
            assertNotNull(destJournalRepo.getJournalById(journal2.id), "journal2 missing after archive round-trip")

            // Notes
            val rt = destNotesRepo.getNoteById(textNote.uid) as? JournalNote.Text
            assertNotNull(rt, "text note missing")
            assertEquals(textNote.content, rt.content)

            val ri = destNotesRepo.getNoteById(imageNote.uid) as? JournalNote.Image
            assertNotNull(ri, "image note missing")
            assertContentEquals(imageBytes, restoredMedia[ri.mediaRef])
            assertEquals(imageNote.caption, ri.caption)

            val ra = destNotesRepo.getNoteById(audioNote.uid) as? JournalNote.Audio
            assertNotNull(ra, "audio note missing")
            assertContentEquals(audioBytes, restoredMedia[ra.mediaRef])
            assertEquals(audioNote.durationMs, ra.durationMs)

            val rv = destNotesRepo.getNoteById(videoNote.uid) as? JournalNote.Video
            assertNotNull(rv, "video note missing")
            assertContentEquals(videoBytes, restoredMedia[rv.mediaRef])
            assertEquals(videoNote.caption, rv.caption)

            val rg = destNotesRepo.getNoteById(geoNote.uid) as? JournalNote.Text
            assertNotNull(rg, "geo note missing")
            val coords = rg.location?.coordinates
            assertNotNull(coords, "location coordinates missing after archive round-trip")
            assertEquals(51.5074, coords.latitude, 0.0001)
            assertEquals(-0.1278, coords.longitude, 0.0001)

            // Journal-note links
            val j1Notes = destContentRepo.observeContentForJournal(journal1.id).first()
            assertEquals(3, j1Notes.size, "journal1 should have 3 notes after archive round-trip")

            val j2Notes = destContentRepo.observeContentForJournal(journal2.id).first()
            assertEquals(2, j2Notes.size, "journal2 should have 2 notes after archive round-trip")
        }

    @Test
    fun `RestoreWorker imports a one gibibyte archive and restored image remains readable offline`() =
        runTest {
            val paddingSize = 1L shl 30
            assertTrue(Runtime.getRuntime().maxMemory() < paddingSize)

            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Offline restore", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)
            val originalUri = "content://archive-fixture/source/photo.png"
            val imageNote =
                JournalNote.Image(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = originalUri,
                    caption = "Restored offline",
                )
            sourceNotesRepo.create(imageNote, journal.id)

            val pngBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val imageBytes =
                ByteArrayOutputStream().use { output ->
                    try {
                        assertTrue(pngBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        output.toByteArray()
                    } finally {
                        pngBitmap.recycle()
                    }
                }

            val archiveFile = File.createTempFile("restore-worker-1gib", ".zip", context.noBackupFilesDir)
            var koinStarted = false
            var restoredMediaUri: String? = null
            val mediaManager = AndroidMediaManager(context.contentResolver, context, Dispatchers.IO)
            val restoreLauncher = mockk<RestoreLauncher>(relaxed = true)
            try {
                sourceMedia[originalUri] = imageBytes
                ZipOutputStream(FileOutputStream(archiveFile)).use { zip ->
                    writeArchive(zip)
                    writeStoredPadding(zip, paddingSize)
                }
                assertTrue(archiveFile.length() >= paddingSize, "fixture must be at least 1 GiB on disk")

                runCatching { stopKoin() }
                startKoin {
                    modules(
                        module {
                            single<RestoreUserDataUseCase> { restoreUseCase }
                            single<MediaManager> { mediaManager }
                            single<RestoreLauncher> { restoreLauncher }
                        },
                    )
                }
                koinStarted = true

                val worker =
                    TestListenableWorkerBuilder<RestoreWorker>(context)
                        .setInputData(
                            androidx.work.Data
                                .Builder()
                                .putString(RestoreWorker.SOURCE_URI_KEY, Uri.fromFile(archiveFile).toString())
                                .putBoolean(RestoreWorker.INCLUDE_MEDIA_KEY, true)
                                .build(),
                        ).build()

                assertIs<ListenableWorker.Result.Success>(worker.doWork())
                val restoredImage = assertIs<JournalNote.Image>(destNotesRepo.getNoteById(imageNote.uid))
                val mediaUri = restoredImage.mediaRef
                restoredMediaUri = mediaUri
                assertTrue(mediaUri.startsWith("file:"), "restored attachment must be app-local for offline access")

                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val previousNetworkState = networkState(context)
                try {
                    setNetworkEnabled(device, false)
                    assertDeviceOffline(context)
                    val media = mediaManager.readMedia(mediaUri)
                    assertContentEquals(imageBytes, media.data)
                    val decoded = assertNotNull(BitmapFactory.decodeByteArray(media.data, 0, media.data.size))
                    try {
                        assertEquals(1, decoded.width)
                        assertEquals(1, decoded.height)
                    } finally {
                        decoded.recycle()
                    }
                } finally {
                    restoreNetworkState(device, previousNetworkState)
                }
            } finally {
                if (koinStarted) stopKoin()
                restoredMediaUri?.let { mediaManager.deleteOwnedMedia(it) }
                archiveFile.delete()
            }
        }

    @Test
    fun `archive unicode and special characters preserved exactly`() =
        runTest {
            val now = now()
            val journal =
                Journal(
                    id = Uuid.random(),
                    title = "日記 📖",
                    created = now,
                    lastUpdated = now,
                )
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "こんにちは 🌸 \"quoted\" \\ newline\nend",
                )
            sourceNotesRepo.create(note, journal.id)

            archiveRoundTrip()

            val restoredJournal = destJournalRepo.getJournalById(journal.id)
            assertNotNull(restoredJournal)
            assertEquals(journal.title, restoredJournal.title, "Unicode journal title corrupted in archive")

            val restoredNote = destNotesRepo.getNoteById(note.uid) as? JournalNote.Text
            assertNotNull(restoredNote)
            assertEquals(note.content, restoredNote.content, "Unicode/special-char content corrupted in archive")
        }

    @Test
    fun `archive timestamps preserved with millisecond precision`() =
        runTest {
            val created = Instant.fromEpochMilliseconds(1_700_000_000_123L)
            val updated = Instant.fromEpochMilliseconds(1_700_000_060_456L)

            val journal = Journal(id = Uuid.random(), title = "Precision", created = created, lastUpdated = updated)
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = created,
                    lastUpdated = updated,
                    content = "Timestamp precision test",
                )
            sourceNotesRepo.create(note, journal.id)

            archiveRoundTrip()

            val rj = destJournalRepo.getJournalById(journal.id)!!
            assertEquals(created, rj.created, "Journal created timestamp lost precision in archive")
            assertEquals(updated, rj.lastUpdated, "Journal updated timestamp lost precision in archive")

            val rn = destNotesRepo.getNoteById(note.uid) as JournalNote.Text
            assertEquals(created, rn.creationTimestamp, "Note creation timestamp lost precision in archive")
            assertEquals(updated, rn.lastUpdated, "Note updated timestamp lost precision in archive")
        }

    @Test
    fun `archive many items none dropped`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Bulk", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val noteIds =
                (1..50).map { i ->
                    val note =
                        JournalNote.Text(
                            uid = Uuid.random(),
                            creationTimestamp = now - i.hours,
                            lastUpdated = now,
                            content = "Entry number $i",
                        )
                    sourceNotesRepo.create(note, journal.id)
                    note.uid
                }

            archiveRoundTrip()

            noteIds.forEachIndexed { index, id ->
                assertNotNull(
                    destNotesRepo.getNoteById(id),
                    "Note ${index + 1} of ${noteIds.size} was dropped during archive round-trip",
                )
            }
        }

    @Test
    fun `archive drafts survived when included`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val draft =
                EditorDraft(
                    id = Uuid.random(),
                    blocks =
                        listOf(
                            SerializableTextBlock(id = Uuid.random(), timestamp = now, content = "Draft text 📝"),
                        ),
                    selectedJournalIds = listOf(journal.id),
                    createdAt = now - 1.hours,
                    lastModifiedAt = now,
                )
            sourceJournalRepo.saveDraft(draft)

            archiveRoundTrip(RestoreOptions(includeDrafts = true))

            val restoredDrafts = destJournalRepo.getAllDrafts()
            assertTrue(restoredDrafts.isNotEmpty(), "Draft was lost during archive round-trip")
            assertEquals(
                "Draft text 📝",
                (restoredDrafts.first().blocks.first() as SerializableTextBlock).content,
                "Draft content corrupted in archive",
            )
        }

    @Test
    fun `archive drafts absent when excluded`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            sourceJournalRepo.saveDraft(
                EditorDraft(
                    id = Uuid.random(),
                    blocks = listOf(SerializableTextBlock(id = Uuid.random(), timestamp = now, content = "Skip me")),
                    selectedJournalIds = listOf(journal.id),
                    createdAt = now,
                    lastModifiedAt = now,
                ),
            )

            archiveRoundTrip(RestoreOptions(includeDrafts = false))

            assertTrue(destJournalRepo.getAllDrafts().isEmpty(), "Excluded drafts appeared after archive round-trip")
        }

    @Test
    fun `archive empty database produces valid archive and restores cleanly`() =
        runTest {
            // No data seeded — archive must still be well-formed and restore must succeed.
            archiveRoundTrip()

            assertTrue(
                destJournalRepo.allJournalsObserved.first().isEmpty(),
                "Empty export should restore to an empty destination",
            )
        }

    // ── Inline fakes ─────────────────────────────────────────────────────

    private class InMemorySyncMetadataService : SyncMetadataService {
        private val pending = mutableMapOf<EntityType, MutableMap<String, PendingUpload>>()
        private val syncTimes = mutableMapOf<EntityType, Instant>()
        private val pendingCountFlow = MutableStateFlow(0)

        override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> =
            pending[entityType]?.values?.toList() ?: emptyList()

        override suspend fun markAsSynced(
            entityId: String,
            entityType: EntityType,
            syncedAt: Instant,
            version: Long,
        ) {
            pending[entityType]?.remove(entityId)
            updateCount()
        }

        override suspend fun getLastSyncTime(entityType: EntityType): Instant? = syncTimes[entityType]

        override suspend fun updateLastSyncTime(
            entityType: EntityType,
            syncedAt: Instant,
        ) {
            syncTimes[entityType] = syncedAt
        }

        override suspend fun enqueuePending(
            entityId: String,
            entityType: EntityType,
            operation: PendingOperation,
        ) {
            pending.getOrPut(entityType) { mutableMapOf() }[entityId] = PendingUpload(entityId, operation, 0)
            updateCount()
        }

        override suspend fun resetSyncStatus(
            entityId: String,
            entityType: EntityType,
        ) {
            pending.getOrPut(entityType) { mutableMapOf() }[entityId] = PendingUpload(entityId, PendingOperation.UPDATE, 0)
            updateCount()
        }

        override suspend fun getPendingCount(): Int = pending.values.sumOf { it.size }

        override fun observePendingCount(): Flow<Int> = pendingCountFlow

        override fun observePendingUploads(): Flow<List<QueuedUpload>> = flowOf(emptyList())

        override suspend fun incrementRetryCount(
            entityId: String,
            entityType: EntityType,
        ) {
            val existing = pending[entityType]?.get(entityId) ?: return
            pending[entityType]!![entityId] = existing.copy(retryCount = existing.retryCount + 1)
        }

        override suspend fun clearPending() {
            pending.clear()
            pendingCountFlow.value = 0
        }

        override suspend fun resetAllCursors() {
            syncTimes.clear()
        }

        private fun updateCount() {
            pendingCountFlow.value = pending.values.sumOf { it.size }
        }
    }

    private class InMemoryDraftRepository : DraftRepository {
        private val drafts = mutableMapOf<Uuid, EditorDraft>()
        private val draftsFlow = MutableStateFlow<List<EditorDraft>>(emptyList())

        override suspend fun saveDraft(draft: EditorDraft) {
            drafts[draft.id] = draft
            draftsFlow.value = drafts.values.toList()
        }

        override suspend fun getLatestDraft(): EditorDraft? = drafts.values.maxByOrNull { it.lastModifiedAt }

        override suspend fun getAllDrafts(): List<EditorDraft> = drafts.values.toList()

        override val allDrafts: Flow<List<EditorDraft>> get() = draftsFlow

        override suspend fun getDraft(id: Uuid): EditorDraft? = drafts[id]

        override suspend fun deleteDraft(id: Uuid) {
            drafts.remove(id)
            draftsFlow.value = drafts.values.toList()
        }

        override suspend fun clearAllDrafts() {
            drafts.clear()
            draftsFlow.value = emptyList()
        }
    }

    private class NoOpRemoteJournalDataSource : RemoteJournalDataSource {
        override suspend fun observeAllJournals(): List<Journal> = emptyList()

        override suspend fun addJournal(journal: Journal): String = journal.id.toString()

        override suspend fun editJournal(journal: Journal) {}

        override suspend fun deleteJournal(journalId: String) {}
    }

    private class StoredProfileRepository : ProfileRepository {
        private val profile = MutableStateFlow(LogDateProfile())
        override val currentProfile: Flow<LogDateProfile> = profile

        override suspend fun updateDisplayName(displayName: String): Result<LogDateProfile> =
            Result.success(profile.value.copy(displayName = displayName)).also { profile.value = it.getOrThrow() }

        override suspend fun updateBirthday(birthday: Instant?): Result<LogDateProfile> =
            Result.success(profile.value.copy(birthday = birthday)).also { profile.value = it.getOrThrow() }

        override suspend fun updateProfilePhoto(profilePhotoUri: String?): Result<LogDateProfile> =
            Result.success(profile.value.copy(profilePhotoUri = profilePhotoUri)).also { profile.value = it.getOrThrow() }

        override suspend fun updateBio(
            bio: String?,
            originalBio: String?,
        ): Result<LogDateProfile> =
            Result.success(profile.value.copy(bio = bio, originalBio = originalBio)).also { profile.value = it.getOrThrow() }

        override suspend fun getCurrentProfile(): LogDateProfile = profile.value

        override suspend fun clearProfile(): Result<Unit> = Result.success(Unit).also { profile.value = LogDateProfile() }
    }

    private class FixedDeviceIdProvider : DeviceIdProvider {
        private val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        private val flow = MutableStateFlow(id)

        override fun getDeviceId(): StateFlow<Uuid> = flow

        override suspend fun refreshDeviceId() {}
    }

    private class FixedCanonicalOwnerProvider : CanonicalOwnerProvider {
        override suspend fun getCanonicalOwnerId(): String = "00000000-0000-4000-8000-000000000002"

        override suspend fun hasBoundOwner(): Boolean = true
    }

    private class FixedAppInfoProvider : AppInfoProvider {
        override fun getAppInfo(): AppInfo =
            AppInfo(
                versionName = "1.0.0-test",
                versionCode = 1,
                packageName = "app.logdate.test",
            )
    }

    private class InMemoryUserStateRepository : UserStateRepository {
        override val userData: Flow<UserData> = MutableStateFlow(UserData())

        override suspend fun setBirthday(birthday: Instant) {}

        override suspend fun setIsOnboardingComplete(isComplete: Boolean) {}

        override suspend fun setBiometricEnabled(isEnabled: Boolean) {}

        override suspend fun addFavoriteNote(vararg noteId: String) {}
    }
}
