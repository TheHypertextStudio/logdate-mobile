package app.logdate.server.database

import app.logdate.server.database.support.withH2Database
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.logdate.LogDateCollectionsMetadataStore
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.LogDateMediaRepository
import app.logdate.server.sync.StaleWriteException
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.identity.AtprotoDid
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CollectionDeletionWinsTest {
    @Test
    fun `canonical metadata tombstones reject writes without partial batches`() =
        withH2Database(LogDateCollectionStatesTable, LogDateCollectionRecordsTable) {
            runBlocking {
                verifyMetadata(PostgreSQLLogDateCollectionsMetadataStore())
                verifyMetadata(InMemoryLogDateCollectionsMetadataStore())
            }
        }

    @Test
    fun `media tombstones reject writes even when deletion arrived first`() =
        withH2Database(LogDateMediaRecordsTable) {
            verifyMedia(PostgreSQLLogDateMediaRepository())
            verifyMedia(InMemoryLogDateMediaRepository())
        }

    private suspend fun verifyMetadata(store: LogDateCollectionsMetadataStore) {
        val user = UUID.randomUUID()
        val did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
        for (kind in listOf(LogDateCollectionKind.ENTRY, LogDateCollectionKind.JOURNAL, LogDateCollectionKind.ASSOCIATION)) {
            store.delete(user, did, kind, "deleted", 100, allowMissing = true)
            store.delete(user, did, kind, "deleted", 50, allowMissing = true)
            assertFailsWith<StaleWriteException> { store.upsert(user, did, kind, "deleted") }
            assertFailsWith<StaleWriteException> { store.upsertBatch(user, did, kind, listOf("fresh", "deleted")) }
            assertNull(store.metadata(user, kind, "fresh"))
            assertEquals(
                100L,
                store
                    .changes(user, kind, 0, 20)
                    .deletions
                    .single()
                    .deletedAt,
            )
            val deletion = store.changes(user, kind, 0, 20).deletions.single()
            val purge = store.purgeTombstones(user, Long.MAX_VALUE)
            assertEquals(0, purge.entryPurged + purge.journalPurged + purge.associationPurged)
            assertEquals(listOf(deletion), store.changes(user, kind, 0, 20).deletions)
            store.purgeAllTombstones(Long.MAX_VALUE)
            assertEquals(listOf(deletion), store.changes(user, kind, 0, 20).deletions)
            assertFailsWith<StaleWriteException> { store.upsert(user, did, kind, "deleted") }
        }
    }

    private fun verifyMedia(repository: LogDateMediaRepository) {
        val user = UUID.randomUUID()
        repository.deleteMedia(user, "deleted", 100)
        repository.deleteMedia(user, "deleted", 50)
        assertFailsWith<StaleWriteException> {
            repository.upsertMedia(
                user,
                LogDateMedia("deleted", "entry", user, "audio", "audio/mp4", 1, byteArrayOf(1), null, 1, 0, DeviceId("device")),
            )
        }
        assertNull(repository.getMedia(user, "deleted"))
    }
}
