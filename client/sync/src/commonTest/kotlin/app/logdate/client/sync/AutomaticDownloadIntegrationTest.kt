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
import app.logdate.client.sync.cloud.MediaDownloadResponse
import app.logdate.client.sync.test.FakeJournalContentRepository
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.InMemoryMediaSyncRefStore
import app.logdate.client.sync.test.InMemorySyncConflictStore
import app.logdate.client.sync.test.InMemorySyncDeadLetterStore
import app.logdate.client.sync.test.InMemorySyncRetryScheduleStore
import app.logdate.client.sync.test.failingCloudApiClient
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Regression coverage for scoped remote downloads and retained edits. */
class AutomaticDownloadIntegrationTest {
    @Test
    fun `download remote media content saves local media for all supported types`() =
        runTest {
            val apiClient = fakeCloudApiClient()
            val notesRepository = FakeJournalNotesRepository()
            val now = Clock.System.now()
            apiClient.downloadMediaResponse =
                Result.success(
                    MediaDownloadResponse(
                        contentId = Uuid.random().toString(),
                        fileName = "downloaded-media.bin",
                        mimeType = "application/octet-stream",
                        sizeBytes = 4,
                        data = byteArrayOf(1, 2, 3, 4),
                        downloadUrl = "https://example.com/media/downloaded-media",
                    ),
                )

            apiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes =
                            listOf(
                                ContentChange(
                                    id = Uuid.random().toString(),
                                    type = "IMAGE",
                                    content = null,
                                    mediaUri = "https://example.com/api/v1/media/image-media/binary",
                                    createdAt = now.toEpochMilliseconds(),
                                    lastUpdated = now.toEpochMilliseconds(),
                                    serverVersion = 1,
                                ),
                                ContentChange(
                                    id = Uuid.random().toString(),
                                    type = "VIDEO",
                                    content = null,
                                    mediaUri = "https://storage.example.com/users/account/media/video-media/clip.mp4?signature=abc",
                                    createdAt = now.toEpochMilliseconds(),
                                    lastUpdated = now.toEpochMilliseconds(),
                                    serverVersion = 1,
                                ),
                                ContentChange(
                                    id = Uuid.random().toString(),
                                    type = "AUDIO",
                                    content = null,
                                    mediaUri = "https://example.com/api/v1/media/audio-media/binary",
                                    durationMs = 12_000,
                                    createdAt = now.toEpochMilliseconds(),
                                    lastUpdated = now.toEpochMilliseconds(),
                                    serverVersion = 1,
                                ),
                            ),
                        deletions = emptyList(),
                        lastTimestamp = now.toEpochMilliseconds(),
                    ),
                )

            val syncManager =
                DefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(apiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(apiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(apiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(apiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(apiClient),
                    cloudAccountRepository = fakeAccountRepository(),
                    sessionStorage = fakeSessionStorage(),
                    mediaManager = InMemoryMediaManager(),
                    mediaSyncRefStore = InMemoryMediaSyncRefStore(),
                    journalRepository = FakeJournalRepository(),
                    journalNotesRepository = notesRepository,
                    journalContentRepository = FakeJournalContentRepository(),
                    journalConflictResolver = lastWriteWinsResolver(),
                    noteConflictResolver = lastWriteWinsResolver(),
                    conflictStore = InMemorySyncConflictStore(),
                    deadLetterStore = InMemorySyncDeadLetterStore(),
                    retryScheduleStore = InMemorySyncRetryScheduleStore(),
                    syncMetadataService = fakeSyncMetadataService(),
                    transactionManager = testSyncTransactionManager(),
                    dataUsagePolicy = fakeDataUsagePolicy(),
                )

            val result = syncManager.downloadRemoteChanges()
            val storedNotes = notesRepository.allNotesObserved.first()

            assertTrue(result.success, "Download should succeed")
            assertEquals(
                listOf("image-media", "video-media", "audio-media"),
                apiClient.downloadMediaCalls.map { it.second },
                "Each remote media note should download its binary payload",
            )
            assertTrue(storedNotes.any { it is JournalNote.Image && it.mediaRef.startsWith("file://") })
            assertTrue(storedNotes.any { it is JournalNote.Video && it.mediaRef.startsWith("file://") })
            assertTrue(storedNotes.any { it is JournalNote.Audio && it.mediaRef.startsWith("file://") })
        }

    @Test
    fun `download new remote content`() =
        runTest {
            // Given: A sync manager with authenticated account and API client that returns remote changes
            val mockApiClient = fakeCloudApiClient()
            val mockAccountRepository = fakeAccountRepository()
            val mockJournalNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

            // Configure API client to return remote content changes
            val remoteNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    content = "Remote content from server",
                )

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
            assertTrue(result.downloadedItems > 0, "Should have downloaded items")
            assertTrue(mockApiClient.wasMethodCalled("getContentChanges"), "Should have called getContentChanges")
            val savedNote = mockJournalNotesRepository.allNotesObserved.first().single() as JournalNote.Text
            assertEquals(remoteNote.uid, savedNote.uid)
            assertEquals(remoteNote.content, savedNote.content)
        }

    @Test
    fun `download remote content updates`() =
        runTest {
            // Given: A sync manager with existing local content
            val mockApiClient = fakeCloudApiClient()
            val mockAccountRepository = fakeAccountRepository()
            val mockJournalNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

            // Add existing local note
            val existingNote = mockJournalNotesRepository.addTestNote("Original content")

            // Configure API client to return updated version of the same note with a clearly newer timestamp
            val updatedTime = Clock.System.now().plus(1.hours)
            mockApiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes =
                            listOf(
                                ContentChange(
                                    id = existingNote.uid.toString(),
                                    type = "TEXT",
                                    content = "Updated content from server",
                                    mediaUri = null,
                                    createdAt = existingNote.creationTimestamp.toEpochMilliseconds(),
                                    lastUpdated = updatedTime.toEpochMilliseconds(),
                                    serverVersion = 2,
                                ),
                            ),
                        deletions = emptyList(),
                        lastTimestamp = updatedTime.toEpochMilliseconds(),
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

            // Then: The download should succeed with conflict resolution
            assertTrue(result.success, "Download should succeed")
            assertTrue(result.conflictsResolved > 0 || result.downloadedItems > 0, "Should have resolved conflicts or downloaded items")
        }

    @Test
    fun `download remote content deletions`() =
        runTest {
            // Given: A sync manager with API client that returns content deletions
            val mockApiClient = fakeCloudApiClient()
            val mockAccountRepository = fakeAccountRepository()
            val mockJournalNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

            val deletedNoteId = Uuid.random()

            // Configure API client to return content deletion
            mockApiClient.getContentChangesResponse =
                Result.success(
                    ContentChangesResponse(
                        changes = emptyList(),
                        deletions =
                            listOf(
                                ContentDeletion(
                                    id = deletedNoteId.toString(),
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
            assertTrue(result.downloadedItems > 0, "Should have processed deletions")
            assertTrue(mockApiClient.wasMethodCalled("getContentChanges"), "Should have called getContentChanges")
        }

    @Test
    fun `download fails with unauthenticated user`() =
        runTest {
            // Given: A sync manager with unauthenticated user
            val mockApiClient = fakeCloudApiClient()
            val mockAccountRepository = fakeAccountRepository(authenticated = false)
            val mockJournalNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

            val syncManager =
                DefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(mockApiClient),
                    cloudJournalDataSource = DefaultCloudJournalDataSource(mockApiClient),
                    cloudAssociationDataSource = DefaultCloudAssociationDataSource(mockApiClient),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(mockApiClient),
                    cloudDraftDataSource = DefaultCloudDraftDataSource(mockApiClient),
                    cloudAccountRepository = mockAccountRepository,
                    sessionStorage = fakeSessionStorage(authenticated = false),
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

            // When: We attempt to download without authentication
            val result = syncManager.downloadRemoteChanges()

            // Then: The download should fail with authentication error
            assertFalse(result.success, "Download should fail without authentication")
            assertTrue(
                result.errors.any { it.type == SyncErrorType.AUTHENTICATION_ERROR },
                "Should have authentication error",
            )
            assertFalse(
                mockApiClient.wasMethodCalled("getContentChanges"),
                "Should not attempt API calls without authentication",
            )
        }

    @Test
    fun `download handles api errors`() =
        runTest {
            // Given: A sync manager with failing API client
            val mockApiClient = failingCloudApiClient()
            val mockAccountRepository = fakeAccountRepository()
            val mockJournalNotesRepository = FakeJournalNotesRepository()
            val mockJournalRepository = FakeJournalRepository()
            val mockJournalContentRepository = FakeJournalContentRepository()

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

            // When: We attempt to download with failing API
            val result = syncManager.downloadRemoteChanges()

            // Then: The download should fail gracefully
            assertFalse(result.success, "Download should fail with API errors")
            assertTrue(result.errors.isNotEmpty(), "Should have error information")
            assertTrue(mockApiClient.wasMethodCalled("getContentChanges"), "Should still attempt API calls")
        }
}
