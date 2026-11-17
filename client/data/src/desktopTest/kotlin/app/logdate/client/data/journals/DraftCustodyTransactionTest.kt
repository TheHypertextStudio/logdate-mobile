package app.logdate.client.data.journals

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.metadata.DatabaseSyncMetadataService
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.KeyValueFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.config.DefaultLogDateConfigRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DraftCustodyTransactionTest {
    @Test
    fun `rolled back handoff retains source and repairs sibling outbox even after first sync`() =
        runTest {
            val file = Files.createTempFile("draft-custody-transaction", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val storage = CustodyStorage()
                val config = DefaultLogDateConfigRepository(initialBackendUrl = ORIGIN)
                val metadata = DatabaseSyncMetadataService(database.syncMetadataDao(), config, CustodyOwner)
                val transactions = RoomSyncTransactionManager(database)
                val repository =
                    LocalFirstDraftRepository(
                        storage,
                        Json,
                        CustodySignedOut,
                        config,
                        CustodyOwner,
                        metadata,
                        transactions = transactions,
                    )
                val marker = KeyValueFirstSyncEnqueueStore(storage)
                marker.markEnqueuedDraftScope(OWNER, ORIGIN)
                val scoped = custodyDraft("Scoped accepted before handoff")
                repository.saveDraftFromSync(scoped)
                val source = scoped.copy(blocks = listOf(custodyBlock("Accepted conflicting source")))
                storage.putStringOrThrow("editor_drafts_anonymous", Json.encodeToString(listOf(source)))
                metadata.enqueuePendingForScope(scoped.id.toString(), EntityType.DRAFT, PendingOperation.UPDATE, UploadScope(OWNER, ORIGIN))
                val recoveredIds = mutableListOf<String>()
                assertFailsWith<IllegalStateException> {
                    transactions.withTransaction {
                        recoveredIds +=
                            repository
                                .getAllDraftsForSync()
                                .single { it.id != scoped.id }
                                .id
                                .toString()
                        error("Synthetic outer SQL rollback")
                    }
                }
                assertTrue(storage.getStringOrThrow("editor_drafts_anonymous").orEmpty().contains(source.id.toString()))
                assertEquals(1, metadata.getPendingUploads(EntityType.DRAFT).size)
                assertEquals(2, repository.getAllDraftsForSync().size)
                assertTrue(metadata.getPendingUploads(EntityType.DRAFT).any { it.entityId == recoveredIds.single() })
                assertTrue(marker.hasEnqueuedDraftScope(OWNER, ORIGIN))
                assertTrue(ArchiveDraftStorageState.decode(Json, storage.getStringOrThrow("editor_drafts_anonymous")).drafts.isEmpty())
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    private companion object {
        const val OWNER = "owner-a"
        const val ORIGIN = "https://server-a.example"
    }
}

private object CustodyOwner : CanonicalOwnerProvider {
    override suspend fun getCanonicalOwnerId() = "owner-a"

    override suspend fun hasBoundOwner() = true
}

private object CustodySignedOut : SessionStorage {
    override fun getSession(): UserSession? = null

    override fun getSessionFlow() = flowOf<UserSession?>(null)

    override suspend fun hasValidSession() = false

    override suspend fun saveSession(session: UserSession) = error("Signed-out fixture")

    override suspend fun clearSession() = Unit
}
