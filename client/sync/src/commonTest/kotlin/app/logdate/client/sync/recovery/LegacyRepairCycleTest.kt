package app.logdate.client.sync.recovery

import app.logdate.client.device.crypto.ContentEncryptionService
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.InMemorySecureStorage
import app.logdate.client.sync.cloud.TestCryptoManager
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.InMemoryFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.fakeCloudApiClient
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.shared.model.sync.ContentChange
import app.logdate.shared.model.sync.ContentChangesResponse
import app.logdate.shared.model.sync.ContentUpdateRequest
import app.logdate.shared.model.sync.ContentUpdateResponse
import app.logdate.shared.model.sync.ContentUploadRequest
import app.logdate.shared.model.sync.ContentUploadResponse
import app.logdate.shared.model.sync.VersionConstraint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LegacyRepairCycleTest {
    @Test
    fun `legacy repair is downloaded and verified on the next full sync without losing local content`() = repairCycle(false)

    @Test
    fun `a previously queued create cannot block recovery of its existing legacy cloud record`() = repairCycle(true)

    private fun repairCycle(queuedCreate: Boolean) =
        runTest {
            suspend fun cipher(seed: String): SyncPayloadCipher {
                val crypto = TestCryptoManager()
                val identity = IdentityKeyManager(InMemorySecureStorage(), crypto)
                identity.recoverIdentity((1..12).map { "$seed-$it" })
                return SyncPayloadCipher(ContentEncryptionService(identity, KeyDerivation(crypto), crypto), identity, crypto)
            }

            val id = Uuid.random()
            val field = "sync:note:$id:text"
            val old = cipher("old").encryptString(field, "Surviving entry")
            val current = cipher("current")
            var remote =
                ContentChange(
                    id = id.toString(),
                    type = "TEXT",
                    content = "LDSE1:" + Json.parseToJsonElement(old.removePrefix("LDSE2:")).jsonObject["env"],
                    createdAt = 1,
                    lastUpdated = 1,
                    serverVersion = 1,
                )
            val api =
                object : CloudApiClient by fakeCloudApiClient() {
                    override suspend fun getContentChanges(
                        accessToken: String,
                        since: Long,
                        limit: Int?,
                    ): Result<ContentChangesResponse> =
                        Result.success(
                            ContentChangesResponse(
                                listOfNotNull(remote.takeIf { it.serverVersion > since }),
                                emptyList(),
                                remote.serverVersion,
                            ),
                        )

                    override suspend fun uploadContent(
                        accessToken: String,
                        content: ContentUploadRequest,
                    ): Result<ContentUploadResponse> =
                        Result.failure(CloudApiException("CONTENT_EXISTS", "Already exists", statusCode = 412))

                    override suspend fun updateContent(
                        accessToken: String,
                        contentId: String,
                        content: ContentUpdateRequest,
                    ): Result<ContentUpdateResponse> {
                        assertEquals(VersionConstraint.Known(remote.serverVersion), content.versionConstraint)
                        remote =
                            remote.copy(
                                content = content.content,
                                serverVersion = remote.serverVersion + 1,
                                lastUpdated =
                                    remote.lastUpdated + 1,
                            )
                        return Result.success(ContentUpdateResponse(contentId, remote.serverVersion, remote.lastUpdated))
                    }
                }
            val db = DownloadInboxTest.Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1L })
            val local =
                JournalNote.Text(
                    uid = id,
                    creationTimestamp = Instant.fromEpochMilliseconds(1),
                    lastUpdated = Instant.fromEpochMilliseconds(1),
                    content = "Surviving entry",
                    syncVersion = 1,
                )
            val notes = FakeJournalNotesRepository().apply { create(local) }
            val metadata = fakeSyncMetadataService()
            if (queuedCreate) metadata.enqueuePending(id.toString(), EntityType.NOTE, PendingOperation.CREATE)
            val firstSync =
                InMemoryFirstSyncEnqueueStore().apply {
                    markEnqueued(EntityType.NOTE)
                    markEnqueued(EntityType.JOURNAL)
                }
            val manager =
                testDefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(DurableCloudApiClient(api, inbox), current),
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                    firstSyncEnqueueStore = firstSync,
                    downloadInbox = inbox,
                    transactionManager = db,
                    syncScope = backgroundScope,
                )

            val repair = manager.fullSync()
            assertEquals(1, repair.uploadedItems)
            assertTrue(repair.success, "A waiting retained record does not make a completed transfer fail")
            assertEquals(1, inbox.count())
            assertTrue(metadata.getPendingUploads(EntityType.NOTE).isEmpty())
            assertEquals(local.content, current.decryptString(field, requireNotNull(remote.content)))

            assertTrue(manager.fullSync().success)
            assertEquals(0, inbox.count())
            val recovered = notes.getNoteById(id) as JournalNote.Text
            assertEquals(local.content, recovered.content)
            assertEquals(local.creationTimestamp, recovered.creationTimestamp)
            assertEquals(2L, recovered.syncVersion)
        }
}
