package app.logdate.client.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import app.logdate.client.domain.export.archive.ArchiveExportOptions
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.RestoreArchiveReader
import app.logdate.client.domain.restore.RestoreOptions
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.feature.core.export.ZipStreamArchiveContainer
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.profile.LogDateProfile
import app.logdate.shared.model.user.UserData
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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Instrumented E2E tests for the core export and import use cases.
 *
 * Unlike the archive-level tests, this suite focuses on the high-level data
 * transformation logic, using in-memory databases to ensure that journals,
 * notes, locations, drafts and media are correctly written to an archive and
 * re-materialized across the entire repository stack.
 */
@RunWith(AndroidJUnit4::class)
class ExportImportE2ETest {
    private lateinit var sourceDb: LogDateDatabase
    private lateinit var destDb: LogDateDatabase

    private lateinit var sourceJournalRepo: OfflineFirstJournalRepository
    private lateinit var sourceNotesRepo: OfflineFirstJournalNotesRepository
    private lateinit var sourceContentRepo: OfflineFirstJournalContentRepository
    private lateinit var exportUseCase: ExportArchiveUseCase
    private lateinit var context: Context
    private val sourceMedia = mutableMapOf<String, ByteArray>()
    private val restoredMedia = mutableMapOf<String, ByteArray>()

    private lateinit var destJournalRepo: OfflineFirstJournalRepository
    private lateinit var destNotesRepo: OfflineFirstJournalNotesRepository
    private lateinit var destContentRepo: OfflineFirstJournalContentRepository
    private lateinit var restoreUseCase: RestoreUserDataUseCase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
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

        // Source-side wiring
        val sourceSyncMeta = InMemorySyncMetadataService()
        val sourceDraftRepo = InMemoryDraftRepository()
        val sourceRemote = NoOpRemoteJournalDataSource()

        sourceJournalRepo =
            OfflineFirstJournalRepository(
                journalDao = sourceDb.journalDao(),
                remoteDataSource = sourceRemote,
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

        // Destination-side wiring
        val destSyncMeta = InMemorySyncMetadataService()
        val destDraftRepo = InMemoryDraftRepository()
        val destRemote = NoOpRemoteJournalDataSource()

        destJournalRepo =
            OfflineFirstJournalRepository(
                journalDao = destDb.journalDao(),
                remoteDataSource = destRemote,
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

    // ── Helper ──────────────────────────────────────────────────────────

    /** Truncate to millisecond precision (Room stores epoch millis). */
    private fun Instant.truncateToMillis(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())

    private fun now(): Instant = Clock.System.now().truncateToMillis()

    /**
     * Exports the source database to a ZIP archive on disk, reads it back and restores it into the
     * destination database, the path [app.logdate.feature.core.export.ExportWorker] and the restore
     * worker take. Restored media is recorded under the reference the restore assigned it.
     */
    private suspend fun exportAndRestore(options: RestoreOptions = RestoreOptions()): Map<String, String> {
        val file = File.createTempFile("export-import-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                val progress = exportUseCase.export(ArchiveExportOptions(), ZipStreamArchiveContainer(zip)).last()
                check(progress is ArchiveExportProgress.Completed) { "Export should complete successfully but was $progress" }
            }
            return ZipFile(file).use { zip ->
                val text = { name: String ->
                    zip.getEntry(name)?.let {
                        zip.getInputStream(it).bufferedReader().use { reader ->
                            reader.readText()
                        }
                    }
                }
                val bundle = RestoreArchiveReader.read(zip.entries().toList().map { it.name }, text)
                val importer =
                    object : MediaImporter {
                        override suspend fun importMedia(exportPath: String): String? {
                            val entry = zip.getEntry(exportPath) ?: return null
                            val reference = "$RESTORED$exportPath"
                            restoredMedia[reference] = zip.getInputStream(entry).use { it.readBytes() }
                            return reference
                        }
                    }
                restoreUseCase.restore(bundle, options, mediaImporter = importer)
                zip
                    .entries()
                    .toList()
                    .filter { it.name.endsWith(".json") }
                    .associate { it.name to checkNotNull(text(it.name)) }
            }
        } finally {
            file.delete()
        }
    }

    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "jpeg".encodeToByteArray()

    private fun mp4() = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".encodeToByteArray() + ByteArray(12)

    private fun m4a() = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".encodeToByteArray() + ByteArray(12)

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `round trip journals preserves all fields`() =
        runTest {
            val now = now()
            val journal =
                Journal(
                    id = Uuid.random(),
                    title = "Travel Log",
                    description = "My travels",
                    created = now - 48.hours,
                    lastUpdated = now,
                )
            sourceJournalRepo.create(journal)

            exportAndRestore()

            val restored = destJournalRepo.getJournalById(journal.id)
            assertNotNull(restored, "Journal should exist in destination")
            assertEquals(journal.id, restored.id)
            assertEquals(journal.title, restored.title)
            assertEquals(journal.description, restored.description)
            assertEquals(journal.created, restored.created)
            assertEquals(journal.lastUpdated, restored.lastUpdated)
        }

    @Test
    fun `round trip text note preserves content`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J1", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now - 1.hours,
                    lastUpdated = now,
                    content = "Hello, world!",
                )
            sourceNotesRepo.create(note, journal.id)

            exportAndRestore()

            val restored = destNotesRepo.getNoteById(note.uid) as? JournalNote.Text
            assertNotNull(restored, "Text note should exist in destination")
            assertEquals(note.content, restored.content)
            assertEquals(note.creationTimestamp, restored.creationTimestamp)
            assertEquals(note.lastUpdated, restored.lastUpdated)
        }

    @Test
    fun `round trip image note preserves media ref and caption`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Photos", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Image(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "content://media/external/images/1234",
                    caption = "Sunset at the beach",
                )
            sourceNotesRepo.create(note, journal.id)
            val bytes = jpeg()
            sourceMedia[note.mediaRef] = bytes

            exportAndRestore()

            val restored = destNotesRepo.getNoteById(note.uid) as? JournalNote.Image
            assertNotNull(restored, "Image note should exist in destination")
            assertContentEquals(bytes, restoredMedia[restored.mediaRef], "restored media must be the archived original")
            assertEquals(note.caption, restored.caption)
        }

    @Test
    fun `round trip video note preserves media ref and caption`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Videos", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Video(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "content://media/external/video/5678",
                    caption = "Dog playing fetch",
                )
            sourceNotesRepo.create(note, journal.id)
            val bytes = mp4()
            sourceMedia[note.mediaRef] = bytes

            exportAndRestore()

            val restored = destNotesRepo.getNoteById(note.uid) as? JournalNote.Video
            assertNotNull(restored, "Video note should exist in destination")
            assertContentEquals(bytes, restoredMedia[restored.mediaRef], "restored media must be the archived original")
            assertEquals(note.caption, restored.caption)
        }

    @Test
    fun `round trip audio note preserves media ref`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Voice Memos", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "content://media/external/audio/9999",
                    durationMs = 15000,
                )
            sourceNotesRepo.create(note, journal.id)
            val bytes = m4a()
            sourceMedia[note.mediaRef] = bytes

            exportAndRestore()

            val restored = destNotesRepo.getNoteById(note.uid) as? JournalNote.Audio
            assertNotNull(restored, "Audio note should exist in destination")
            assertContentEquals(bytes, restoredMedia[restored.mediaRef], "restored media must be the archived original")
            assertEquals(note.durationMs, restored.durationMs)
        }

    @Test
    fun `round trip note location preserves coordinates`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Geo", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val location =
                NoteLocation(
                    coordinates = NoteCoordinates(latitude = 37.7749, longitude = -122.4194),
                )
            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "In San Francisco",
                    location = location,
                )
            sourceNotesRepo.create(note, journal.id)

            exportAndRestore()

            val restored = destNotesRepo.getNoteById(note.uid) as? JournalNote.Text
            assertNotNull(restored, "Note with location should exist")
            assertNotNull(restored.location, "Location should be preserved")
            val coords = restored.location?.coordinates
            assertNotNull(coords, "Coordinates should be preserved")
            assertEquals(37.7749, coords.latitude, 0.0001)
            assertEquals(-122.4194, coords.longitude, 0.0001)
        }

    @Test
    fun `round trip journal note relations preserved`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Daily", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val note1 =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Note 1",
                )
            val note2 =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Note 2",
                )
            sourceNotesRepo.create(note1, journal.id)
            sourceNotesRepo.create(note2, journal.id)

            exportAndRestore()

            val restoredNotes = destContentRepo.observeContentForJournal(journal.id).first()
            assertEquals(2, restoredNotes.size, "Both notes should be linked to journal")
            assertTrue(restoredNotes.any { it.uid == note1.uid })
            assertTrue(restoredNotes.any { it.uid == note2.uid })
        }

    @Test
    fun `round trip multiple journals all preserved`() =
        runTest {
            val now = now()
            val journals =
                (1..3).map { i ->
                    Journal(id = Uuid.random(), title = "Journal $i", created = now, lastUpdated = now)
                }
            journals.forEach { sourceJournalRepo.create(it) }

            exportAndRestore()

            journals.forEach { journal ->
                val restored = destJournalRepo.getJournalById(journal.id)
                assertNotNull(restored, "Journal '${journal.title}' should exist")
                assertEquals(journal.title, restored.title)
            }
        }

    @Test
    fun `round trip drafts preserved when included`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J1", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val draft =
                EditorDraft(
                    id = Uuid.random(),
                    blocks =
                        listOf(
                            SerializableTextBlock(
                                id = Uuid.random(),
                                timestamp = now,
                                content = "Draft content",
                            ),
                        ),
                    selectedJournalIds = listOf(journal.id),
                    createdAt = now - 1.hours,
                    lastModifiedAt = now,
                )
            sourceJournalRepo.saveDraft(draft)

            exportAndRestore(options = RestoreOptions(includeDrafts = true))

            val restoredDrafts = destJournalRepo.getAllDrafts()
            assertTrue(restoredDrafts.isNotEmpty(), "Drafts should be imported")
        }

    @Test
    fun `round trip drafts skipped when excluded`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "J1", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val draft =
                EditorDraft(
                    id = Uuid.random(),
                    blocks =
                        listOf(
                            SerializableTextBlock(
                                id = Uuid.random(),
                                timestamp = now,
                                content = "Should not import",
                            ),
                        ),
                    selectedJournalIds = listOf(journal.id),
                    createdAt = now,
                    lastModifiedAt = now,
                )
            sourceJournalRepo.saveDraft(draft)

            exportAndRestore(options = RestoreOptions(includeDrafts = false))

            val restoredDrafts = destJournalRepo.getAllDrafts()
            assertTrue(restoredDrafts.isEmpty(), "No drafts should be imported when excluded")
        }

    @Test
    fun `round trip timestamps preserved exactly`() =
        runTest {
            val created = Instant.fromEpochMilliseconds(1700000000000L)
            val updated = Instant.fromEpochMilliseconds(1700000060000L)

            val journal = Journal(id = Uuid.random(), title = "Exact", created = created, lastUpdated = updated)
            sourceJournalRepo.create(journal)

            val note =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = created,
                    lastUpdated = updated,
                    content = "Timestamp test",
                )
            sourceNotesRepo.create(note, journal.id)

            exportAndRestore()

            val restoredJournal = destJournalRepo.getJournalById(journal.id)!!
            assertEquals(created, restoredJournal.created)
            assertEquals(updated, restoredJournal.lastUpdated)

            val restoredNote = destNotesRepo.getNoteById(note.uid) as JournalNote.Text
            assertEquals(created, restoredNote.creationTimestamp)
            assertEquals(updated, restoredNote.lastUpdated)
        }

    @Test
    fun `round trip manifest names the app version that wrote it`() =
        runTest {
            val now = now()
            val journal = Journal(id = Uuid.random(), title = "Meta", created = now, lastUpdated = now)
            sourceJournalRepo.create(journal)

            val manifest = checkNotNull(exportAndRestore().entries.single { it.key.endsWith("manifest.json") }.value)

            assertTrue(manifest.contains("1.0.0-test"), "Manifest should contain app version")
        }

    private companion object {
        const val RESTORED = "file:///restored/"
    }

    // ── Inline Fakes ────────────────────────────────────────────────────

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
            pending.getOrPut(entityType) { mutableMapOf() }[entityId] =
                PendingUpload(entityId, operation, 0)
            updateCount()
        }

        override suspend fun resetSyncStatus(
            entityId: String,
            entityType: EntityType,
        ) {
            pending.getOrPut(entityType) { mutableMapOf() }[entityId] =
                PendingUpload(entityId, PendingOperation.UPDATE, 0)
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
            Result.success(profile.value.copy(displayName = displayName)).also { result ->
                result.getOrNull()?.let { profile.value = it }
            }

        override suspend fun updateBirthday(birthday: Instant?): Result<LogDateProfile> =
            Result.success(profile.value.copy(birthday = birthday)).also { result ->
                result.getOrNull()?.let { profile.value = it }
            }

        override suspend fun updateProfilePhoto(profilePhotoUri: String?): Result<LogDateProfile> =
            Result.success(profile.value.copy(profilePhotoUri = profilePhotoUri)).also { result ->
                result.getOrNull()?.let { profile.value = it }
            }

        override suspend fun updateBio(
            bio: String?,
            originalBio: String?,
        ): Result<LogDateProfile> =
            Result.success(profile.value.copy(bio = bio, originalBio = originalBio)).also { result ->
                result.getOrNull()?.let { profile.value = it }
            }

        override suspend fun getCurrentProfile(): LogDateProfile = profile.value

        override suspend fun clearProfile(): Result<Unit> =
            Result.success(Unit).also {
                profile.value = LogDateProfile()
            }
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
