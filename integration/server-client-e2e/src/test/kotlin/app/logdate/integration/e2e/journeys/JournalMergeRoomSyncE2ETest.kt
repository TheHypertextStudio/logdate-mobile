@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.integration.e2e.journeys

import androidx.room.Room
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.repository.journals.JournalMergeResult
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.InMemorySyncRouteStore
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.cloud.Association
import app.logdate.client.sync.cloud.AssociationUploadRequest
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudRequestLocation
import app.logdate.client.sync.cloud.CloudRequestLocationProvider
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.client.sync.cloud.JournalUploadRequest
import app.logdate.client.sync.metadata.DatabaseSyncMetadataService
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.InMemoryFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.InMemoryIdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.InMemoryLastSyncErrorStore
import app.logdate.client.sync.metadata.InMemoryUnreadableCloudRecordStore
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.DurableCloudApiClient
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.Journal
import app.logdate.shared.model.sync.JournalMergeRequest
import app.logdate.shared.model.sync.JournalMergeResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class JournalMergeRoomSyncE2ETest {
    @Test
    fun `Room merge uploads destination before request and settles stable retry after a lost HTTP response`() =
        runTest {
            withServerClientHarness {
                val account = apiClient.createAccountWithSyntheticPasskey("roommerge_${Uuid.random().toString().take(8)}").data
                val token = account.tokens.accessToken
                val owner = account.account.id.toString()
                val origin = baseUrl.removeSuffix("/api/v1")
                val config = DefaultLogDateConfigRepository(initialBackendUrl = origin)
                val source = Journal(title = "Source created offline")
                val destination = Journal(title = "Destination created offline")
                val localOnly = Uuid.random()
                val remoteOnly = Uuid.random()
                apiClient
                    .uploadJournal(
                        token,
                        JournalUploadRequest(
                            id = source.id.toString(),
                            title = source.title,
                            description = "",
                            createdAt = 1L,
                            lastUpdated = 1L,
                            deviceId = DeviceId("other-device"),
                        ),
                    ).getOrThrow()
                apiClient
                    .uploadAssociations(
                        token,
                        AssociationUploadRequest(
                            listOf(
                                Association(
                                    journalId = source.id.toString(),
                                    contentId = remoteOnly.toString(),
                                    createdAt = 1L,
                                    deviceId = DeviceId("other-device"),
                                ),
                            ),
                        ),
                    ).getOrThrow()
                val file = Files.createTempFile("journal-room-http-merge", ".db")
                val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
                try {
                    val scope = JournalMergeScope(owner, origin)
                    val ownerProvider = mockk<CanonicalOwnerProvider> { coEvery { getCanonicalOwnerId() } returns owner }
                    val metadata = DatabaseSyncMetadataService(database.syncMetadataDao(), config, ownerProvider)
                    val transactions = RoomSyncTransactionManager(database)
                    val inbox =
                        DownloadInbox(
                            database.downloadInboxDao(),
                            transactions,
                            { DownloadScope(owner, origin) },
                            { System.currentTimeMillis() },
                        )
                    val journals =
                        OfflineFirstJournalRepository(
                            journalDao = database.journalDao(),
                            remoteDataSource = mockk(relaxed = true),
                            draftRepository = mockk(relaxed = true),
                            syncMetadataService = metadata,
                            database = database,
                            currentScope = { scope },
                            mergeTransactionManager = transactions,
                            externalScope = backgroundScope,
                        )
                    journals.create(source)
                    journals.create(destination)
                    database.journalContentDao().addContentToJournal(JournalContentEntityLink(source.id, localOnly))
                    val preview = assertNotNull(journals.previewMerge(source.id, destination.id))
                    val operation = assertIs<JournalMergeResult.Merged>(journals.merge(preview, Uuid.random())).operation
                    assertNull(database.journalDao().getJournalById(source.id))
                    assertEquals(listOf(destination.id.toString()), metadata.getPendingUploads(EntityType.JOURNAL).map { it.entityId })
                    val requests = mutableListOf<JournalMergeRequest>()
                    val transport =
                        object : CloudApiClient by apiClient {
                            override suspend fun mergeJournals(
                                accessToken: String,
                                sourceId: String,
                                request: JournalMergeRequest,
                            ): Result<JournalMergeResponse> {
                                requests += request
                                assertTrue(
                                    apiClient.getJournalChanges(accessToken, 0L).getOrThrow().changes.any {
                                        it.id ==
                                            destination.id.toString()
                                    },
                                    "Destination prerequisite must reach HTTP before merge",
                                )
                                val result = apiClient.mergeJournals(accessToken, sourceId, request)
                                return if (requests.size == 1 &&
                                    result.isSuccess
                                ) {
                                    Result.failure(IOException("Response lost after server committed"))
                                } else {
                                    result
                                }
                            }
                        }
                    val session = UserSession(token, account.tokens.refreshToken, owner)
                    val sessions =
                        mockk<SessionStorage> {
                            every { getSession() } returns session
                            every { getSessionFlow() } returns flowOf(session)
                            every { getOriginBoundSession() } returns OriginBoundSession(origin, session)
                        }
                    val accounts =
                        object : CloudAccountRepository by mockk(relaxed = true), CloudRequestLocationProvider {
                            override fun captureLocation() = CloudRequestLocation(origin, baseUrl)

                            override fun isCurrentOrigin(origin: String) = origin == scope.serverOrigin
                        }
                    // Advance transport retries immediately; operation/request/outbox persistence remains real.
                    val retrySchedule =
                        mockk<SyncRetryScheduleStore>(relaxed = true) {
                            coEvery { nextAttemptAt(any(), any()) } returns null
                        }
                    val manager =
                        DefaultSyncManager(
                            lastErrorStore = InMemoryLastSyncErrorStore(),
                            firstSyncEnqueueStore = InMemoryFirstSyncEnqueueStore(),
                            identityRecoveryNeededStore = InMemoryIdentityRecoveryNeededStore(),
                            unreadableCloudRecordStore = InMemoryUnreadableCloudRecordStore(),
                            syncRouteStore = InMemorySyncRouteStore(),
                            cloudContentDataSource = mockk(relaxed = true),
                            cloudJournalDataSource = DefaultCloudJournalDataSource(DurableCloudApiClient(transport, inbox)),
                            cloudAssociationDataSource = mockk(relaxed = true),
                            cloudMediaDataSource = mockk(relaxed = true),
                            cloudDraftDataSource = mockk(relaxed = true),
                            cloudAccountRepository = accounts,
                            sessionStorage = sessions,
                            mediaManager = mockk(relaxed = true),
                            mediaSyncRefStore = mockk(relaxed = true),
                            journalRepository = journals,
                            journalNotesRepository = mockk(relaxed = true),
                            journalContentRepository = mockk(relaxed = true),
                            journalConflictResolver = mockk(relaxed = true),
                            noteConflictResolver = mockk(relaxed = true),
                            conflictStore = mockk(relaxed = true),
                            deadLetterStore = mockk(relaxed = true),
                            retryScheduleStore = retrySchedule,
                            syncMetadataService = metadata,
                            transactionManager = transactions,
                            dataUsagePolicy = mockk(relaxed = true),
                            cloudApiClient = transport,
                            downloadInbox = inbox,
                            syncScope = backgroundScope,
                        )
                    val interrupted = manager.uploadPendingChanges()
                    assertFalse(interrupted.success)
                    assertEquals(operation.operationId, journals.pendingJournalMerges().single().operationId)
                    assertEquals(1, metadata.getPendingUploads(EntityType.JOURNAL_MERGE).size)
                    val completed = manager.uploadPendingChanges()
                    assertTrue(completed.success, completed.errors.toString())
                    assertEquals(2, requests.size)
                    assertEquals(requests.first(), requests.last())
                    assertTrue(journals.pendingJournalMerges().isEmpty())
                    assertTrue(metadata.getPendingUploads(EntityType.JOURNAL_MERGE).isEmpty())
                    assertEquals(destination.id, journals.resolveJournalId(source.id))
                    assertEquals(
                        setOf(localOnly),
                        database
                            .journalContentDao()
                            .getContentForJournal(destination.id)
                            .first()
                            .toSet(),
                    )
                    val remoteLinks = apiClient.getAssociationChanges(token, 0L).getOrThrow().changes
                    assertEquals(
                        setOf(localOnly.toString(), remoteOnly.toString()),
                        remoteLinks
                            .filter {
                                it.journalId ==
                                    destination.id.toString()
                            }.map { it.contentId }
                            .toSet(),
                    )
                    val remoteJournals = apiClient.getJournalChanges(token, 0L).getOrThrow()
                    assertEquals(destination.title, remoteJournals.changes.single { it.id == destination.id.toString() }.title)
                    assertEquals(
                        destination.id.toString(),
                        remoteJournals.deletions.single { it.id == source.id.toString() }.mergedIntoJournalId,
                    )
                    val downloaded = manager.syncJournals()
                    assertTrue(downloaded.success, downloaded.errors.toString())
                    assertEquals(0, inbox.count(), "Merge tombstone must settle the durable download inbox")
                } finally {
                    database.close()
                    Files.deleteIfExists(file)
                    Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
                    Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
                }
            }
        }
}
