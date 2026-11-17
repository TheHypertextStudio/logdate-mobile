package app.logdate.client.sync

import app.logdate.client.device.crypto.ContentEncryptionService
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.InMemorySecureStorage
import app.logdate.client.sync.cloud.TestCryptoManager
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.fakeCloudApiClient
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.client.sync.test.testSyncUploader
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableTextBlock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class UnclaimedDraftUploadTest {
    @Test
    fun `unclaimed local content retains its pending upload while eligible drafts sync`() =
        runTest {
            val unknown = Uuid.random()
            val selected = FakeJournalRepository()
            val draft = draft()
            selected.saveDraft(draft)
            val repository =
                object : JournalRepository by selected {
                    override suspend fun hasUnclaimedDraft(id: Uuid): Boolean = id == unknown
                }
            val metadata = fakeSyncMetadataService()
            metadata.enqueuePending(unknown.toString(), EntityType.DRAFT, PendingOperation.UPDATE)
            metadata.enqueuePending(draft.id.toString(), EntityType.DRAFT, PendingOperation.CREATE)
            val api = fakeCloudApiClient()
            val manager =
                testDefaultSyncManager(
                    journalRepository = repository,
                    syncMetadataService = metadata,
                    cloudDraftDataSource = DefaultCloudDraftDataSource(api, cipher(), supportsRichDrafts = { true }),
                    supportsRichDrafts = { true },
                )

            val result = manager.syncDrafts()

            assertTrue(result.success)
            assertEquals(1, result.uploadedItems)
            assertEquals(listOf(draft.id.toString()), api.uploadDraftCalls.map { it.second.id })
            val remaining = metadata.getPendingUploads(EntityType.DRAFT)
            assertEquals(listOf(unknown.toString()), remaining.map { it.entityId }, "Unclaimed local data must retain its queued mutation")
            val retained = remaining.single()
            assertEquals(unknown.toString(), retained.entityId)
            assertEquals(PendingOperation.UPDATE, retained.operation)
            assertEquals(0, retained.retryCount, "Unknown provenance must not exhaust a transport retry budget")
        }

    @Test
    fun `a genuinely absent draft still settles its obsolete upload`() =
        runTest {
            val missing = Uuid.random()
            val metadata = fakeSyncMetadataService()
            metadata.enqueuePending(missing.toString(), EntityType.DRAFT, PendingOperation.UPDATE)
            val api = fakeCloudApiClient()
            val manager =
                testDefaultSyncManager(
                    journalRepository = FakeJournalRepository(),
                    syncMetadataService = metadata,
                    cloudDraftDataSource = DefaultCloudDraftDataSource(api),
                )

            assertTrue(manager.syncDrafts().success)

            assertTrue(metadata.getPendingUploads(EntityType.DRAFT).isEmpty())
            assertTrue(api.uploadDraftCalls.isEmpty())
        }

    @Test
    fun `an explicit deletion is not held behind an unclaimed local copy`() =
        runTest {
            val removed = Uuid.random()
            val repository =
                object : JournalRepository by FakeJournalRepository() {
                    override suspend fun hasUnclaimedDraft(id: Uuid): Boolean = id == removed
                }
            val metadata = fakeSyncMetadataService()
            metadata.enqueuePending(removed.toString(), EntityType.DRAFT, PendingOperation.DELETE)
            val api = fakeCloudApiClient()
            val manager =
                testDefaultSyncManager(
                    journalRepository = repository,
                    syncMetadataService = metadata,
                    cloudDraftDataSource = DefaultCloudDraftDataSource(api),
                )

            assertTrue(manager.syncDrafts().success)

            assertEquals(listOf(removed.toString()), api.deleteDraftCalls.map { it.second })
            assertTrue(metadata.getPendingUploads(EntityType.DRAFT).isEmpty())
            assertTrue(api.uploadDraftCalls.isEmpty())
        }

    @Test
    fun `upload-only sync replays durable local intent before reading an empty SQL outbox`() =
        runTest {
            val removed = Uuid.random()
            val metadata = fakeSyncMetadataService()
            var replayed = false
            val repository =
                object : JournalRepository by FakeJournalRepository() {
                    override suspend fun getAllDraftsForSync(): List<EditorDraft> {
                        if (!replayed) {
                            metadata.enqueuePending(removed.toString(), EntityType.DRAFT, PendingOperation.DELETE)
                            replayed = true
                        }
                        return emptyList()
                    }
                }
            val uploader = testSyncUploader(journalRepository = repository, syncMetadataService = metadata)
            val result = uploader.uploadDrafts("synthetic-token")
            assertTrue(result.success)
            assertTrue(replayed, "An empty SQL outbox must not bypass durable local intent replay")
            assertEquals(1, result.uploadedItems)
            assertTrue(metadata.getPendingUploads(EntityType.DRAFT).isEmpty())
        }

    private fun draft(): EditorDraft {
        val created = Instant.fromEpochMilliseconds(1)
        return EditorDraft(
            blocks = listOf(SerializableTextBlock(Uuid.random(), created, content = "Eligible account draft")),
            createdAt = created,
            lastModifiedAt = created,
        )
    }

    private suspend fun cipher(): SyncPayloadCipher {
        val crypto = TestCryptoManager()
        val keys = IdentityKeyManager(InMemorySecureStorage(), crypto)
        keys.setupNewIdentity()
        return SyncPayloadCipher(ContentEncryptionService(keys, KeyDerivation(crypto), crypto), keys, crypto)
    }
}
