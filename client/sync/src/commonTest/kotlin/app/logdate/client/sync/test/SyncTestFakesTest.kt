package app.logdate.client.sync.test

import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.InMemorySyncRouteStore
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DefaultCloudAssociationDataSource
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.metadata.InMemoryFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.InMemoryIdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.InMemoryLastSyncErrorStore
import app.logdate.client.sync.metadata.InMemoryUnreadableCloudRecordStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Unit tests for the sync testing infrastructure.
 *
 * This suite ensures that the fakes used in sync integration tests (e.g.,
 * [FakeCloudApiClient], [TrackingSyncManager]) behave consistently and
 * accurately simulate real-world conditions like network failures.
 */
class SyncTestFakesTest {
    @Test
    fun `fake cloud api client tracks calls`() =
        runTest {
            // Given: A fake API client
            val apiClient = FakeCloudApiClient()

            // When: Making API calls
            apiClient.uploadContent(
                "token",
                ContentUploadRequest(
                    id = "test",
                    type = "TEXT",
                    content = "Test content",
                    mediaUri = null,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    lastUpdated = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            apiClient.getContentChanges("token", Clock.System.now().toEpochMilliseconds())

            // Then: Calls are tracked
            assertTrue(apiClient.wasMethodCalled("uploadContent"), "Should track uploadContent call")
            assertTrue(apiClient.wasMethodCalled("getContentChanges"), "Should track getContentChanges call")
            assertEquals(2, apiClient.methodCalls.size, "Should track total method calls")
        }

    @Test
    fun `fake cloud api client configurable failures`() =
        runTest {
            // Given: A fake API client configured for content sync failure
            val apiClient = FakeCloudApiClient()
            val testError = Exception("Test network error")
            apiClient.configureContentSyncFailure(testError)

            // When: Making a content upload call
            val result =
                apiClient.uploadContent(
                    "token",
                    ContentUploadRequest(
                        id = "test",
                        type = "TEXT",
                        content = "Test content",
                        mediaUri = null,
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                        lastUpdated = Clock.System.now().toEpochMilliseconds(),
                    ),
                )

            // Then: Should return failure as configured
            assertTrue(result.isFailure, "Should return failure as configured")
            assertEquals(testError, result.exceptionOrNull(), "Should return configured exception")
        }

    @Test
    fun `fake cloud account repository authentication states`() =
        runTest {
            // Given: A fake cloud account repository
            val repository = FakeCloudAccountRepository()

            // When: Setting unauthenticated state
            repository.setAuthenticated(false)

            // Then: Should reflect unauthenticated state
            assertEquals(null, repository.getCurrentAccount(), "Should have no current account when unauthenticated")
            assertEquals(null, repository.accessToken, "Should have no access token when unauthenticated")

            // When: Setting authenticated state
            repository.setAuthenticated(true)

            // Then: Should reflect authenticated state
            assertTrue(repository.getCurrentAccount() != null, "Should have current account when authenticated")
            assertEquals("test-access-token", repository.accessToken, "Should have access token when authenticated")
        }

    @Test
    fun `tracking sync manager tracks all calls`() =
        runTest {
            // Given: A tracking sync manager
            val syncManager = TrackingSyncManager()

            // When: Making various sync calls
            syncManager.syncContent()
            syncManager.syncJournals()
            syncManager.syncContent() // Second call
            syncManager.uploadPendingChanges()
            val status = syncManager.getSyncStatus()

            // Then: All calls are properly tracked
            assertEquals(2, syncManager.syncContentCalls, "Should track multiple syncContent calls")
            assertEquals(1, syncManager.syncJournalsCalls, "Should track syncJournals calls")
            assertEquals(1, syncManager.uploadPendingChangesCalls, "Should track uploadPendingChanges calls")
            assertEquals(1, syncManager.getSyncStatusCalls, "Should track getSyncStatus calls")
            assertTrue(status.isEnabled, "Should return configured sync status")
        }

    @Test
    fun `tracking sync manager configurable results`() =
        runTest {
            // Given: A tracking sync manager configured for failure
            val syncManager = TrackingSyncManager()
            syncManager.configureSyncFailure("Test sync failure")

            // When: Performing sync operations
            val result = syncManager.syncContent()

            // Then: Should return configured failure
            assertFalse(result.success, "Should return failure as configured")
            assertEquals(1, result.errors.size, "Should have one error")
            assertEquals("Test sync failure", result.errors.first().message, "Should have configured error message")

            // When: Reconfiguring for success
            syncManager.configureSyncSuccess(uploadedItems = 3, downloadedItems = 2)
            val successResult = syncManager.syncContent()

            // Then: Should return configured success
            assertTrue(successResult.success, "Should return success as configured")
            assertEquals(3, successResult.uploadedItems, "Should have configured uploaded items")
            assertEquals(2, successResult.downloadedItems, "Should have configured downloaded items")
        }

    @Test
    fun `fake repository basic operations`() =
        runTest {
            // Given: A fake notes repository
            val repository = FakeJournalNotesRepository()

            // When: Adding test notes
            val note1 = repository.addTestNote("Test note 1")
            val note2 = repository.addTestNote("Test note 2")

            // Then: Repository should contain the notes
            // We can't easily test the flow contents without more setup,
            // but we can verify the methods work without errors

            // When: Removing a note
            repository.remove(note1)

            // Then: Should not throw an exception

            // When: Clearing the repository
            repository.clear()

            // Then: Should not throw an exception
        }

    @Test
    fun `end to end sync with fakes`() =
        runTest {
            // Given: A complete sync setup with fakes
            val apiClient = fakeCloudApiClient()
            val accountRepository = fakeAccountRepository()
            val journalNotesRepository = fakeJournalNotesRepository("Test note 1", "Test note 2")
            val journalRepository = FakeJournalRepository()
            val journalContentRepository = FakeJournalContentRepository()

            val syncManager =
                DefaultSyncManager(
                    lastErrorStore = InMemoryLastSyncErrorStore(),
                    firstSyncEnqueueStore = InMemoryFirstSyncEnqueueStore(),
                    identityRecoveryNeededStore = InMemoryIdentityRecoveryNeededStore(),
                    unreadableCloudRecordStore = InMemoryUnreadableCloudRecordStore(),
                    syncRouteStore = InMemorySyncRouteStore(),
                    cloudContentDataSource = DefaultCloudContentDataSource(apiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(apiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(apiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(apiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(apiClient),
                    cloudAccountRepository = accountRepository,
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = journalRepository,
                    journalNotesRepository = journalNotesRepository,
                    journalContentRepository = journalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = fakeSyncMetadataService(),
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            // When: Performing a content sync
            val result = syncManager.syncContent()

            // Then: Sync should succeed and API calls should be made
            assertTrue(result.success, "Sync should succeed with authenticated user and successful API")
            assertTrue(
                apiClient.wasMethodCalled("uploadContent") || apiClient.wasMethodCalled("getContentChanges"),
                "Should make API calls during sync",
            )
        }

    @Test
    fun `sync failure with unauthenticated user`() =
        runTest {
            // Given: An unauthenticated user setup
            val apiClient = fakeCloudApiClient()
            val accountRepository = fakeAccountRepository(authenticated = false)
            val journalNotesRepository = FakeJournalNotesRepository()
            val journalRepository = FakeJournalRepository()
            val journalContentRepository = FakeJournalContentRepository()

            val syncManager =
                DefaultSyncManager(
                    lastErrorStore = InMemoryLastSyncErrorStore(),
                    firstSyncEnqueueStore = InMemoryFirstSyncEnqueueStore(),
                    identityRecoveryNeededStore = InMemoryIdentityRecoveryNeededStore(),
                    unreadableCloudRecordStore = InMemoryUnreadableCloudRecordStore(),
                    syncRouteStore = InMemorySyncRouteStore(),
                    cloudContentDataSource = DefaultCloudContentDataSource(apiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(apiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(apiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(apiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(apiClient),
                    cloudAccountRepository = accountRepository,
                    sessionStorage = fakeSessionStorage(authenticated = false),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = journalRepository,
                    journalNotesRepository = journalNotesRepository,
                    journalContentRepository = journalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = fakeSyncMetadataService(),
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            // When: Attempting to sync without authentication
            val result = syncManager.syncContent()

            // Then: Sync should fail
            assertFalse(result.success, "Sync should fail without authentication")
            assertTrue(result.errors.isNotEmpty(), "Should have error information")
        }

    @Test
    fun `sync failure with network errors`() =
        runTest {
            // Given: Network errors from API
            val apiClient = failingCloudApiClient()
            val accountRepository = fakeAccountRepository()
            val journalNotesRepository = FakeJournalNotesRepository()
            val journalRepository = FakeJournalRepository()
            val journalContentRepository = FakeJournalContentRepository()

            val syncManager =
                DefaultSyncManager(
                    lastErrorStore = InMemoryLastSyncErrorStore(),
                    firstSyncEnqueueStore = InMemoryFirstSyncEnqueueStore(),
                    identityRecoveryNeededStore = InMemoryIdentityRecoveryNeededStore(),
                    unreadableCloudRecordStore = InMemoryUnreadableCloudRecordStore(),
                    syncRouteStore = InMemorySyncRouteStore(),
                    cloudContentDataSource = DefaultCloudContentDataSource(apiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(apiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(apiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(apiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(apiClient),
                    cloudAccountRepository = accountRepository,
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = journalRepository,
                    journalNotesRepository = journalNotesRepository,
                    journalContentRepository = journalContentRepository,
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = fakeSyncMetadataService(),
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            // When: Attempting to sync with network failures
            val result = syncManager.syncContent()

            // Then: Sync should fail gracefully
            assertFalse(result.success, "Sync should fail with network errors")
            assertTrue(result.errors.isNotEmpty(), "Should have error details")
        }
}
