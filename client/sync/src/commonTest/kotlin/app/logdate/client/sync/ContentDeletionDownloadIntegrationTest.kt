package app.logdate.client.sync

import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.cloud.ContentChange
import app.logdate.client.sync.cloud.ContentChangesResponse
import app.logdate.client.sync.cloud.ContentDeletion
import app.logdate.client.sync.cloud.DefaultCloudAssociationDataSource
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.test.FailingJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalContentRepository
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.InMemoryMediaSyncRefStore
import app.logdate.client.sync.test.InMemorySyncConflictStore
import app.logdate.client.sync.test.InMemorySyncDeadLetterStore
import app.logdate.client.sync.test.InMemorySyncRetryScheduleStore
import app.logdate.client.sync.test.fakeAccountRepository
import app.logdate.client.sync.test.fakeCloudApiClient
import app.logdate.client.sync.test.fakeDataUsagePolicy
import app.logdate.client.sync.test.fakeSessionStorage
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.lastWriteWinsResolver
import app.logdate.client.sync.test.testSyncTransactionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Regression coverage for scoped remote downloads and retained edits. */
class ContentDeletionDownloadIntegrationTest {
    @Test
    fun `download preserves a pending content edit before applying deletion`() =
        runTest {
            val mockApiClient = fakeCloudApiClient()
            val mockNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()
            val syncMetadataService = fakeSyncMetadataService()

            val localNote = mockNotesRepository.addTestNote("Local pending note")
            syncMetadataService.addPending(localNote.uid, EntityType.NOTE)

            mockApiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes = emptyList(),
                        deletions =
                            listOf(
                                ContentDeletion(
                                    id = localNote.uid.toString(),
                                    deletedAt = Clock.System.now().toEpochMilliseconds(),
                                ),
                            ),
                        lastTimestamp = Clock.System.now().toEpochMilliseconds(),
                    ),
                )

            val syncManager =
                DefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(mockApiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(mockApiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(mockApiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(mockApiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(mockApiClient),
                    cloudAccountRepository = fakeAccountRepository(),
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = mockJournalRepository,
                    journalNotesRepository = mockNotesRepository,
                    journalContentRepository = mockJournalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = syncMetadataService,
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            val result = syncManager.downloadRemoteChanges()
            val remainingNotes = mockNotesRepository.allNotesObserved.first()

            assertTrue(remainingNotes.none { it.uid == localNote.uid }, "The original deleted identity must stay deleted")
            assertEquals(
                localNote,
                syncManager.deletionRecovery
                    .list()
                    .single()
                    .deletedEdit
                    ?.note,
            )
            assertFalse(syncMetadataService.hasPending(EntityType.NOTE, localNote.uid.toString()))
            assertTrue(result.conflictsResolved > 0, "The retained edit should be available for recovery")
        }

    @Test
    fun `download retains a newer local note without resurrecting its deleted identity`() =
        runTest {
            val mockApiClient = fakeCloudApiClient()
            val mockNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()
            val syncMetadataService = fakeSyncMetadataService()

            val syncManager =
                DefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(mockApiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(mockApiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(mockApiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(mockApiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(mockApiClient),
                    cloudAccountRepository = fakeAccountRepository(),
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = mockJournalRepository,
                    journalNotesRepository = mockNotesRepository,
                    journalContentRepository = mockJournalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = syncMetadataService,
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            mockApiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes = emptyList(),
                        deletions = emptyList(),
                        lastTimestamp = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            val firstResult = syncManager.downloadRemoteChanges()
            assertTrue(firstResult.success, "Initial download should succeed")

            val lastSyncTime = syncManager.getSyncStatus().lastSyncTime
            assertNotNull(lastSyncTime, "Sync should record last sync time")

            val localNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = lastSyncTime,
                    lastUpdated = lastSyncTime.plus(1.seconds),
                    content = "Local update after sync",
                )
            mockNotesRepository.create(localNote)
            syncMetadataService.addPending(localNote.uid, EntityType.NOTE)

            mockApiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes = emptyList(),
                        deletions =
                            listOf(
                                ContentDeletion(
                                    id = localNote.uid.toString(),
                                    deletedAt = Clock.System.now().toEpochMilliseconds(),
                                ),
                            ),
                        lastTimestamp = Clock.System.now().toEpochMilliseconds(),
                    ),
                )

            val result = syncManager.downloadRemoteChanges()
            val remainingNotes = mockNotesRepository.allNotesObserved.first()

            assertTrue(remainingNotes.none { it.uid == localNote.uid }, "The original deleted identity must stay deleted")
            assertEquals(
                localNote,
                syncManager.deletionRecovery
                    .list()
                    .single()
                    .deletedEdit
                    ?.note,
            )
            assertFalse(syncMetadataService.hasPending(EntityType.NOTE, localNote.uid.toString()))
            assertTrue(result.conflictsResolved > 0, "The retained edit should be available for recovery")
        }

    @Test
    fun `download does not advance cursor when apply fails`() =
        runTest {
            val mockApiClient = fakeCloudApiClient()
            val syncMetadataService = fakeSyncMetadataService()

            val failingNotesRepository = FailingJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

            val remoteNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    content = "Remote note",
                )

            val remoteTimestamp = Clock.System.now().toEpochMilliseconds()
            mockApiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes =
                            listOf(
                                ContentChange(
                                    id = remoteNote.uid.toString(),
                                    type = "TEXT",
                                    content = remoteNote.content,
                                    mediaUri = null,
                                    createdAt = remoteNote.creationTimestamp.toEpochMilliseconds(),
                                    lastUpdated = remoteNote.lastUpdated.toEpochMilliseconds(),
                                    serverVersion = 1,
                                ),
                            ),
                        deletions = emptyList(),
                        lastTimestamp = remoteTimestamp,
                    ),
                )

            val syncManager =
                DefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(mockApiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(mockApiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(mockApiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(mockApiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(mockApiClient),
                    cloudAccountRepository = fakeAccountRepository(),
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = mockJournalRepository,
                    journalNotesRepository = failingNotesRepository,
                    journalContentRepository = mockJournalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = syncMetadataService,
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            val result = syncManager.downloadRemoteChanges()

            assertFalse(result.success, "Download should fail when applying a change throws")
            assertEquals(
                null,
                syncMetadataService.getLastSyncTime(EntityType.NOTE),
                "Cursor should not advance when applying changes fails",
            )

            // Without a durable inbox, every failed record still needs the original feed page.
            repeat(5) { syncManager.downloadRemoteChanges() }
            assertEquals(null, syncMetadataService.getLastSyncTime(EntityType.NOTE))
        }
}
