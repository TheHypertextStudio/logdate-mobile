package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.localTestPostgresUrl
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.migration.StoredPrecisionClock
import app.logdate.server.migration.randomPlcDid
import app.logdate.server.migration.sealed
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.sync.DbLocationHistoryRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.LocationHistoryUpload
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.space.SpaceRef
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DAG_CBOR_CODEC
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoHead
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Runs against the local test PostgreSQL only: the move's compare-and-set, and deleting a legacy copy. */
class SpaceMigrationPostgresTest {
    @Test
    fun `a switch moves the storage mode and the move together or not at all`() =
        withAccount { account, _ ->
            val store = PostgreSQLSpaceMigrationStore()
            // The server's clock on Linux reads nanoseconds, which PostgreSQL would round away.
            val linuxClock = FixedClock(Instant.fromEpochSeconds(Clock.System.now().epochSeconds, 123_456_789))
            val now = StoredPrecisionClock(linuxClock).now()
            val begun = SpaceMigration(account, "move-1", SpaceMigrationPhase.UPLOADING, now, now)
            assertTrue(store.replace(null, begun))
            assertFalse(store.replace(null, begun), "A second first write is refused")
            val held = begun.next(now) { copy(holdStartedAt = now) }
            assertTrue(store.replace(begun, held))
            assertFalse(store.replace(begun, held), "A write from a stale copy is refused")

            val switched =
                held.next(
                    now,
                ) { copy(phase = SpaceMigrationPhase.SWITCHED, holdStartedAt = null, switchedAt = now, switchedAtSeq = 7) }
            assertFalse(store.replaceAndSwitch(held, switched, AccountStorageMode.PERSONAL_SPACE_V1, AccountStorageMode.LEGACY))
            assertEquals(held, store.get(account), "A refused switch leaves the move as it was")
            assertEquals("legacy", storageMode(account))

            assertTrue(store.replaceAndSwitch(held, switched, AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1))
            assertEquals("personalSpaceV1", storageMode(account))
            assertEquals(SpaceMigrationPhase.SWITCHED, store.get(account)?.phase)
            assertEquals(7L, store.get(account)?.switchedAtSeq)

            assertEquals(emptyList(), store.cleanupDue(now - 1.days, 10).filter { it.accountId == account })
            assertEquals(listOf(account), store.cleanupDue(now + 1.days, 1000).map { it.accountId }.filter { it == account })
        }

    @Test
    fun `deleting a legacy copy removes every legacy row and file and leaves the personal space`() =
        withAccount { account, did ->
            val storage = InMemoryLogDateBlobStorage()
            PostgreSQLLogDateCollectionsMetadataStore().upsert(account, AtprotoDid.require(did), LogDateCollectionKind.ENTRY, "entry-one")
            DbLocationHistoryRepository().upload(
                account,
                listOf(LocationHistoryUpload("place-one", "place", "LDSE2:opaque", deviceId = "d", deviceVersion = 1)),
            )
            val path =
                storage.putBlob(
                    LogDateBlobWriteRequest(account, LogDateBlobNamespace.MEDIA, "m1", "a.jpg", "image/jpeg", byteArrayOf(1, 2)),
                )
            PostgreSQLLogDateMediaRepository().upsertMedia(account, media(account, path))
            val blocks = PostgreSQLRepoBlockStore()
            val block = byteArrayOf(0xA0.toByte())
            val cid = Cid.sha256(DAG_CBOR_CODEC, block)
            blocks.write(account) {
                blocks.writeBlock(AtprotoDid.require(did), RepoBlock(cid, block)).getOrThrow()
                blocks.writeHead(RepoHead(AtprotoDid.require(did), cid, cid, 1)).getOrThrow()
            }
            val space = SpaceRef.personal(did).uri
            PostgreSQLSpaceStore().put(SpaceRecordWrite(account, space, did, "kept", sealed(), emptyList(), null, false))

            val cleanupAt = Clock.System.now()
            val claim = SpaceMigration(account, "cleanup", SpaceMigrationPhase.CLEANED, cleanupAt, cleanupAt)
            assertTrue(PostgreSQLSpaceMigrationStore().replace(null, claim))
            transaction { exec("UPDATE accounts SET storage_mode = 'personalSpaceV1' WHERE id = '$account'") }
            assertTrue(PostgreSQLLegacyJournalEraser(storage).erase(claim))
            assertTrue(PostgreSQLLegacyJournalEraser(storage).erase(claim))

            listOf(
                "SELECT count(*) FROM logdate_collection_records WHERE user_id = '$account'",
                "SELECT count(*) FROM logdate_collection_states WHERE user_id = '$account'",
                "SELECT count(*) FROM location_history WHERE user_id = '$account'",
                "SELECT count(*) FROM logdate_media_records WHERE user_id = '$account'",
                "SELECT count(*) FROM atproto_repo_heads WHERE repo_did = '$did'",
                "SELECT count(*) FROM atproto_repo_block_links WHERE repo_did = '$did'",
            ).forEach { assertEquals(0L, count(it), it) }
            assertNull(storage.getBlob(path))
            assertEquals(1L, count("SELECT count(*) FROM space_records WHERE account_id = '$account' AND NOT deleted"))
        }

    private class FixedClock(
        private val at: Instant,
    ) : Clock {
        override fun now(): Instant = at
    }

    private fun media(
        account: UUID,
        path: String,
    ) = LogDateMedia(
        mediaId = "m1",
        contentId = "c1",
        userId = account,
        fileName = "a.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2,
        data = ByteArray(0),
        storagePath = path,
        createdAt = 1,
        version = 0,
        deviceId = DeviceId("d"),
    )

    private fun storageMode(account: UUID): String =
        transaction {
            var mode = ""
            exec("SELECT storage_mode FROM accounts WHERE id = '$account'") { rows -> if (rows.next()) mode = rows.getString(1) }
            mode
        }

    private fun count(sql: String): Long =
        transaction {
            var found = -1L
            exec(sql) { rows -> if (rows.next()) found = rows.getLong(1) }
            found
        }

    private fun withAccount(block: suspend (UUID, String) -> Unit) =
        runBlocking {
            val url = localTestPostgresUrl()
            val previous = TransactionManager.defaultDatabase
            val source = localTestPostgresDataSource(url)
            val account = UUID.randomUUID()
            val did = randomPlcDid()
            try {
                TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                insertTestAccountRow(account)
                transaction { exec("UPDATE accounts SET did = '$did' WHERE id = '$account'") }
                block(account, did)
            } finally {
                transaction {
                    exec("DELETE FROM atproto_repo_heads WHERE repo_did = '$did'")
                    exec("DELETE FROM atproto_repo_block_links WHERE repo_did = '$did'")
                    exec("DELETE FROM location_history WHERE user_id = '$account'")
                    exec("DELETE FROM location_history_versions WHERE user_id = '$account'")
                    exec("DELETE FROM logdate_media_records WHERE user_id = '$account'")
                }
                deleteTestAccountRow(account)
                source.close()
                TransactionManager.defaultDatabase = previous
            }
        }
}
