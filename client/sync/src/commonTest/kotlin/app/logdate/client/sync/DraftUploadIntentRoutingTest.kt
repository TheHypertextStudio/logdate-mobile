package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableDraftRepository
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.space.FakeDataHome
import app.logdate.client.sync.space.InMemorySpaceSwapStore
import app.logdate.client.sync.space.SpaceDataHomes
import app.logdate.client.sync.space.SpaceRecordRegistry
import app.logdate.client.sync.space.SpaceSyncDependencies
import app.logdate.client.sync.space.SpaceTransportGate
import app.logdate.client.sync.space.TEST_DID
import app.logdate.client.sync.space.TEST_IDENTITY_KEY
import app.logdate.client.sync.space.mappers.draftSpaceKind
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.testSyncUploader
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.EditorDraft
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DraftUploadIntentRoutingTest {
    @Test
    fun `space upload-only run replays durable draft intent before the outbox snapshot`() =
        runTest {
            val id = Uuid.random()
            val metadata = fakeSyncMetadataService()
            val base = FakeJournalRepository()
            var replayed = false
            val repository =
                object : JournalRepository by base, SyncableDraftRepository by base {
                    override suspend fun getAllDraftsForSync(): List<EditorDraft> {
                        if (!replayed) {
                            metadata.enqueuePending(id.toString(), EntityType.DRAFT, PendingOperation.DELETE)
                            replayed = true
                        }
                        return emptyList()
                    }
                }
            val home = FakeDataHome()
            val uploader =
                testSyncUploader(
                    journalRepository = repository,
                    syncMetadataService = metadata,
                    spaceSync =
                        SpaceSyncDependencies(
                            registry = SpaceRecordRegistry(listOf(draftSpaceKind(repository))),
                            gate = SpaceTransportGate({ true }, { true }, { AccountStorageMode.PERSONAL_SPACE_V1 }),
                            homes = SpaceDataHomes { _, token -> home.signedIn(token) },
                            swapStore = InMemorySpaceSwapStore(),
                            accountDid = { TEST_DID },
                            identityKey = { TEST_IDENTITY_KEY },
                        ),
                )
            val result = uploader.uploadDrafts("synthetic-token")
            assertTrue(result.success)
            assertTrue(replayed, "Both transports must replay accepted local intent before reading SQL pending work")
            assertEquals(1, result.uploadedItems)
            assertTrue(metadata.getPendingUploads(EntityType.DRAFT).isEmpty())
            assertTrue(home.tokensSeen.isNotEmpty(), "The deletion must reach the actual space transport")
        }
}
