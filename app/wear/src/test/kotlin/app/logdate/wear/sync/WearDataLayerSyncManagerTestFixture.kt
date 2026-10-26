package app.logdate.wear.sync

import app.logdate.client.database.dao.HealthSnapshotDao
import app.logdate.client.media.storage.AndroidMediaDirectories
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.HealthSnapshotDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Shared data-layer doubles and note fixtures for the manager's behavior suites. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class WearDataLayerSyncManagerTestFixture {
    protected lateinit var dataLayerClient: WearDataLayerClient
    protected lateinit var syncMetadataService: SyncMetadataService
    protected lateinit var retryScheduleStore: SyncRetryScheduleStore
    protected lateinit var deadLetterStore: SyncDeadLetterStore
    protected lateinit var notesRepository: JournalNotesRepository
    protected lateinit var journalRepository: JournalRepository
    protected lateinit var healthSnapshotDao: HealthSnapshotDao
    protected lateinit var noteDataMapper: NoteDataMapper
    protected lateinit var journalDataMapper: JournalDataMapper
    protected lateinit var associationDataMapper: AssociationDataMapper
    protected lateinit var healthSnapshotDataMapper: HealthSnapshotDataMapper
    protected lateinit var syncManager: WearDataLayerSyncManager

    protected val fixedTime = Instant.fromEpochMilliseconds(1_700_000_000_000)
    protected val noteId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    protected val textNote =
        JournalNote.Text(
            uid = noteId,
            creationTimestamp = fixedTime,
            lastUpdated = fixedTime,
            content = "Hello from watch",
        )

    protected val audioNote =
        JournalNote.Audio(
            uid = noteId,
            creationTimestamp = fixedTime,
            lastUpdated = fixedTime,
            mediaRef = "/storage/audio/recording.aac",
            durationMs = 4200,
        )

    protected val audioNoteWithLocation =
        JournalNote.Audio(
            uid = noteId,
            creationTimestamp = fixedTime,
            lastUpdated = fixedTime,
            mediaRef = "/storage/audio/recording_with_loc.aac",
            durationMs = 10000,
            location =
                NoteLocation(
                    coordinates = NoteCoordinates(latitude = 37.7749, longitude = -122.4194),
                ),
        )

    @Before
    fun setUp() {
        dataLayerClient = mockk(relaxed = true)
        syncMetadataService = mockk(relaxed = true)
        retryScheduleStore = mockk(relaxed = true)
        deadLetterStore = mockk(relaxed = true)
        notesRepository = mockk(relaxed = true)
        journalRepository = mockk(relaxed = true)
        healthSnapshotDao = mockk(relaxed = true)
        noteDataMapper = NoteDataMapper()
        journalDataMapper = JournalDataMapper()
        associationDataMapper = AssociationDataMapper()
        healthSnapshotDataMapper = HealthSnapshotDataMapper()

        coEvery { dataLayerClient.isPhoneConnected(any()) } returns true
        coEvery { dataLayerClient.putDataItem(any(), any()) } returns true
        coEvery { dataLayerClient.deleteDataItem(any()) } returns true
        coEvery { dataLayerClient.sendFile(any(), any()) } returns true
        every { syncMetadataService.observePendingCount() } returns MutableStateFlow(0)
        coEvery { syncMetadataService.getPendingUploads(EntityType.JOURNAL) } returns emptyList()
        coEvery { syncMetadataService.getPendingUploads(EntityType.ASSOCIATION) } returns emptyList()
        coEvery { syncMetadataService.getPendingUploads(EntityType.MEDIA) } returns emptyList()
        coEvery { healthSnapshotDao.getAfter(any()) } returns emptyList()
        coEvery { retryScheduleStore.nextAttemptAt(any(), any()) } returns null
        coEvery { deadLetterStore.list() } returns emptyList()
        coEvery { syncMetadataService.incrementRetryIfCurrent(any(), any()) } coAnswers {
            val pending = secondArg<PendingUpload>()
            syncMetadataService.incrementRetryCount(pending.entityId, firstArg())
            true
        }
        coEvery { syncMetadataService.settleIfCurrent(any(), any(), any(), any()) } coAnswers {
            val pending = secondArg<PendingUpload>()
            syncMetadataService.markAsSynced(pending.entityId, firstArg(), thirdArg(), arg(3))
            true
        }

        syncManager =
            WearDataLayerSyncManager(
                dataLayerClient = dataLayerClient,
                syncMetadataService = syncMetadataService,
                retryScheduleStore = retryScheduleStore,
                deadLetterStore = deadLetterStore,
                notesRepository = notesRepository,
                journalRepository = journalRepository,
                healthSnapshotDao = healthSnapshotDao,
                noteDataMapper = noteDataMapper,
                journalDataMapper = journalDataMapper,
                associationDataMapper = associationDataMapper,
                healthSnapshotDataMapper = healthSnapshotDataMapper,
                deliveryStore = WearAudioDeliveryStore(),
                mediaFiles = MediaFileResolver(AndroidMediaDirectories(createTempDirectory().toFile())),
            )
    }

    protected fun fixedClockSyncManager(): WearDataLayerSyncManager =
        WearDataLayerSyncManager(
            dataLayerClient = dataLayerClient,
            syncMetadataService = syncMetadataService,
            retryScheduleStore = retryScheduleStore,
            deadLetterStore = deadLetterStore,
            notesRepository = notesRepository,
            journalRepository = journalRepository,
            healthSnapshotDao = healthSnapshotDao,
            noteDataMapper = noteDataMapper,
            journalDataMapper = journalDataMapper,
            associationDataMapper = associationDataMapper,
            healthSnapshotDataMapper = healthSnapshotDataMapper,
            deliveryStore = WearAudioDeliveryStore(),
            mediaFiles = MediaFileResolver(AndroidMediaDirectories(createTempDirectory().toFile())),
            clock =
                object : Clock {
                    override fun now(): Instant = fixedTime
                },
        )
}
