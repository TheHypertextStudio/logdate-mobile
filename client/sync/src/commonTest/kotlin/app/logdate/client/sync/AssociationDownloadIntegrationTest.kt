package app.logdate.client.sync

import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.sync.cloud.AssociationChange
import app.logdate.client.sync.cloud.AssociationChangesResponse
import app.logdate.client.sync.cloud.AssociationDeletion
import app.logdate.client.sync.cloud.DefaultCloudAssociationDataSource
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.test.FakeJournalContentRepository
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.InMemoryMediaSyncRefStore
import app.logdate.client.sync.test.InMemorySyncConflictStore
import app.logdate.client.sync.test.InMemorySyncDeadLetterStore
import app.logdate.client.sync.test.InMemorySyncRetryScheduleStore
import app.logdate.client.sync.test.TrackingJournalContentRepository
import app.logdate.client.sync.test.fakeAccountRepository
import app.logdate.client.sync.test.fakeCloudApiClient
import app.logdate.client.sync.test.fakeDataUsagePolicy
import app.logdate.client.sync.test.fakeSessionStorage
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.lastWriteWinsResolver
import app.logdate.client.sync.test.testSyncTransactionManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Regression coverage for scoped remote downloads and retained edits. */
class AssociationDownloadIntegrationTest {
    @Test
    fun `matching relationship deletions settle without a conflict`() =
        runTest {
            val mockApiClient = fakeCloudApiClient()
            val syncMetadataService = fakeSyncMetadataService()
            val mockJournalRepository = FakeJournalRepository()
            val mockNotesRepository = FakeJournalNotesRepository()
            val trackingContentRepository = TrackingJournalContentRepository()

            val journalId = Uuid.random()
            val contentId = Uuid.random()

            trackingContentRepository.addContentToJournal(contentId, journalId)
            syncMetadataService.enqueuePending(
                entityId = AssociationPendingKey(journalId, contentId).toPendingId(),
                entityType = EntityType.ASSOCIATION,
                operation = PendingOperation.DELETE,
            )

            mockApiClient.getAssociationChangesResponse =
                Result.success(
                    AssociationChangesResponse(
                        changes = emptyList(),
                        deletions =
                            listOf(
                                AssociationDeletion(
                                    journalId = journalId.toString(),
                                    contentId = contentId.toString(),
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
                    journalContentRepository = trackingContentRepository,
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

            assertFalse(trackingContentRepository.hasAssociation(contentId, journalId))
            assertEquals(0, result.conflictsResolved)
            assertFalse(syncMetadataService.hasPending(EntityType.ASSOCIATION, AssociationPendingKey(journalId, contentId).toPendingId()))
            assertTrue(syncManager.deletionRecovery.list().isEmpty())
        }

    @Test
    fun `download remote association changes`() =
        runTest {
            // Given: A sync manager with API client that returns association changes
            val mockApiClient = fakeCloudApiClient()
            val mockAccountRepository = fakeAccountRepository()
            val mockJournalNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

            val journalId = Uuid.random()
            val contentId = Uuid.random()

            // Configure API client to return association changes
            mockApiClient.getAssociationChangesResponse =
                Result.success(
                    AssociationChangesResponse(
                        changes =
                            listOf(
                                AssociationChange(
                                    journalId = journalId.toString(),
                                    contentId = contentId.toString(),
                                    createdAt = Clock.System.now().toEpochMilliseconds(),
                                    isDeleted = false,
                                    serverVersion = 1,
                                ),
                            ),
                        deletions = emptyList(),
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
                    cloudAccountRepository = mockAccountRepository,
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = mockJournalRepository,
                    journalNotesRepository = mockJournalNotesRepository,
                    journalContentRepository = mockJournalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = fakeSyncMetadataService(),
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            // When: We download remote changes
            val result = syncManager.downloadRemoteChanges()

            // Then: The download should succeed
            assertTrue(result.success, "Download should succeed")
            assertTrue(result.downloadedItems > 0, "Should have downloaded associations")
            assertTrue(mockApiClient.wasMethodCalled("getAssociationChanges"), "Should have called getAssociationChanges")
        }
}
