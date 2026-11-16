package app.logdate.client.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.migrations.MIGRATION_60_61
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Validates migration 61, which stores upload retry state on the queue and indexes the download inbox. */
class SyncRetryStateMigrationJvmTest {
    private val databaseFile = Files.createTempFile("logdate-migration", ".db")

    private val helper =
        MigrationTestHelper(
            Path.of("schemas"),
            databaseFile,
            BundledSQLiteDriver(),
            LogDateDatabase::class,
            { LogDateDatabaseConstructor.initialize() },
            emptyList(),
        )

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(databaseFile)
        Files.deleteIfExists(databaseFile.resolveSibling("${databaseFile.fileName}-wal"))
        Files.deleteIfExists(databaseFile.resolveSibling("${databaseFile.fileName}-shm"))
    }

    @Test
    fun `queued uploads keep their operation and start with no retry schedule`() {
        helper.createDatabase(60).use { connection ->
            connection.execSQL(
                "INSERT INTO pending_uploads (ownerId, serverOrigin, entityType, entityId, operation, createdAt, " +
                    "retryCount, expectedServerVersion, operationId) " +
                    "VALUES ('owner', 'https://sync', 'NOTE', 'note-1', 'UPDATE', 1710000000000, 3, 7, 'op-1')",
            )
        }

        helper.runMigrationsAndValidate(61, listOf(MIGRATION_60_61)).use { connection ->
            connection
                .prepare(
                    "SELECT operation, retryCount, expectedServerVersion, operationId, nextAttemptAt, unfinishedAttempts " +
                        "FROM pending_uploads WHERE entityId = 'note-1'",
                ).use { row ->
                    assertTrue(row.step(), "the queued upload is gone after the migration")
                    assertEquals("UPDATE", row.getText(0))
                    assertEquals(3L, row.getLong(1))
                    assertEquals(7L, row.getLong(2))
                    assertEquals("op-1", row.getText(3))
                    assertTrue(row.isNull(4), "a migrated upload is waiting for a retry it never scheduled")
                    assertEquals(0L, row.getLong(5))
                }
        }
    }

    @Test
    fun `receipts applied before the upgrade are dated at the upgrade, unsettled rows are not`() {
        helper.createDatabase(60).use { connection ->
            connection.insertInboxRow(id = "applied", state = "APPLIED")
            connection.insertInboxRow(id = "waiting", state = "WAITING")
        }
        val before = System.currentTimeMillis() - 1_000

        helper.runMigrationsAndValidate(61, listOf(MIGRATION_60_61)).use { connection ->
            connection.prepare("SELECT entityId, appliedAt FROM sync_download_inbox ORDER BY entityId").use { row ->
                assertTrue(row.step())
                assertEquals("applied", row.getText(0))
                assertTrue(row.getLong(1) >= before, "an applied receipt would be pruned the moment it is checked")
                assertTrue(row.step())
                assertEquals("waiting", row.getText(0))
                assertEquals(0L, row.getLong(1))
            }
        }
    }

    @Test
    fun `due and scoped inbox reads use the state index`() {
        helper.createDatabase(60).close()

        helper.runMigrationsAndValidate(61, listOf(MIGRATION_60_61)).use { connection ->
            val plan =
                connection.queryPlan(
                    "SELECT COUNT(*) FROM sync_download_inbox WHERE ownerId = 'o' AND serverOrigin = 's' " +
                        "AND state IN ('WAITING', 'FAILED') AND nextAttemptAt <= 10",
                )
            assertTrue("index_sync_download_inbox_ownerId_serverOrigin_state_nextAttemptAt" in plan, "due counts scan the inbox: $plan")
        }
    }

    @Test
    fun `retry state belongs to the queued operation`() =
        runBlocking {
            val database = openDatabase()
            try {
                val queue = database.syncMetadataDao()
                val retries = database.pendingUploadRetryDao()
                queue.insertPending(pending(operationId = "op-1"))

                assertEquals(0, retries.beginAttempt("owner", "https://sync", "NOTE", "note-1", "op-1"))
                assertEquals(1, retries.beginAttempt("owner", "https://sync", "NOTE", "note-1", "op-1"))
                assertEquals(1, retries.setNextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-1", 5_000))
                assertEquals(5_000, retries.nextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-1"))
                assertEquals(emptyList(), retries.duePending("owner", "https://sync", "NOTE", now = 4_999, limit = 10))
                val due = retries.duePending("owner", "https://sync", "NOTE", now = 5_000, limit = 10)
                assertEquals(listOf("note-1"), due.map { it.entityId })

                queue.insertPending(pending(operationId = "op-2"))

                assertEquals(0, retries.setNextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-1", 9_000))
                assertEquals(0, retries.beginAttempt("owner", "https://sync", "NOTE", "note-1", "op-1"))
                assertNull(retries.nextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-2"))
                assertEquals(0, retries.beginAttempt("owner", "https://sync", "NOTE", "note-1", "op-2"))
                retries.endAttempt("owner", "https://sync", "NOTE", "note-1", "op-2")
                assertEquals(0, retries.beginAttempt("owner", "https://sync", "NOTE", "note-1", "op-2"))

                retries.setNextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-2", 9_000)
                assertEquals(1, retries.releaseScheduledRetries("owner", "https://sync"))
                assertEquals(0, retries.nextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-2"))
                retries.clearRetry("owner", "https://sync", "NOTE", "note-1", "op-2")
                assertNull(retries.nextAttemptAt("owner", "https://sync", "NOTE", "note-1", "op-2"))
                assertEquals(0, retries.beginAttempt("owner", "https://sync", "NOTE", "note-1", "op-2"))
            } finally {
                database.close()
            }
        }

    @Test
    fun `pruning removes old applied receipts and keeps deletions and unsettled work`() =
        runBlocking {
            val database = openDatabase()
            try {
                val inbox = database.downloadInboxDao()
                inbox.put(inboxRow("old", state = "APPLIED", appliedAt = 1_000))
                inbox.put(inboxRow("old-deleted", state = "APPLIED", appliedAt = 1_000, deleted = true))
                inbox.put(inboxRow("recent", state = "APPLIED", appliedAt = 9_000))
                inbox.put(inboxRow("waiting", state = "WAITING", appliedAt = 0))
                inbox.put(inboxRow("other-owner", state = "APPLIED", appliedAt = 1_000, owner = "someone-else"))

                assertEquals(1, inbox.pruneApplied("owner", "https://sync", appliedBefore = 5_000))
                assertEquals(
                    listOf("old-deleted", "recent", "waiting"),
                    inbox.all("owner", "https://sync", "NOTE").map { it.entityId }.sorted(),
                )

                assertEquals(1, inbox.pruneApplied("owner", "https://sync", appliedBefore = 5_000, includeDeletions = true))
                assertEquals(listOf("recent", "waiting"), inbox.all("owner", "https://sync", "NOTE").map { it.entityId }.sorted())
                assertEquals(1, inbox.all("someone-else", "https://sync", "NOTE").size)
            } finally {
                database.close()
            }
        }

    private fun openDatabase(): LogDateDatabase = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(BundledSQLiteDriver()).build()

    private fun pending(operationId: String) =
        PendingUploadEntity(
            ownerId = "owner",
            serverOrigin = "https://sync",
            entityType = "NOTE",
            entityId = "note-1",
            operation = "UPDATE",
            createdAt = 1_000,
            operationId = operationId,
        )

    private fun inboxRow(
        id: String,
        state: String,
        appliedAt: Long,
        deleted: Boolean = false,
        owner: String = "owner",
    ) = DownloadInboxEntity(
        ownerId = owner,
        serverOrigin = "https://sync",
        entityType = "NOTE",
        entityId = id,
        version = 1,
        deleted = deleted,
        payload = "",
        operationId = "op-$id",
        state = state,
        attempts = 0,
        nextAttemptAt = 0,
        reason = "NONE",
        appliedAt = appliedAt,
    )

    private fun SQLiteConnection.insertInboxRow(
        id: String,
        state: String,
    ) = execSQL(
        "INSERT INTO sync_download_inbox (ownerId, serverOrigin, entityType, entityId, version, deleted, payload, " +
            "operationId, state, attempts, nextAttemptAt, reason) " +
            "VALUES ('owner', 'https://sync', 'NOTE', '$id', 1, 0, '', 'op-$id', '$state', 0, 0, 'NONE')",
    )

    private fun SQLiteConnection.queryPlan(sql: String): String =
        prepare("EXPLAIN QUERY PLAN $sql").use { row ->
            buildString {
                while (row.step()) appendLine(row.getText(3))
            }
        }
}
