package app.logdate.client.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.logdate.client.database.entities.HistoryRecordEntity
import app.logdate.client.database.migrations.MIGRATION_61_62
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validates migration 62, and that a page of history is read in one query and written in one statement per record. */
class HistoryRecordBatchJvmTest {
    private val databaseFile = Files.createTempFile("logdate-history-batch", ".db")

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
    fun `unsent history is found through the dirty index in upload order`() {
        helper.createDatabase(61).use { connection ->
            connection.execSQL(
                "INSERT INTO history_records (ownerId, origin, id, recordType, payload, deviceId, deviceVersion, " +
                    "serverVersion, deleted, dirty) VALUES ('owner', 'origin', 'a', 'place', 'p', 'phone', 1, 0, 0, 1)",
            )
        }

        helper.runMigrationsAndValidate(62, listOf(MIGRATION_61_62)).use { connection ->
            val plan =
                connection.queryPlan(
                    "SELECT * FROM history_records WHERE ownerId = 'owner' AND origin = 'origin' AND dirty = 1 ORDER BY id LIMIT 50",
                )
            assertTrue("index_history_records_ownerId_origin_dirty_id" in plan, "unsent history scans every record: $plan")
            assertTrue("TEMP B-TREE" !in plan, "unsent history is sorted after it is read: $plan")
            connection.prepare("SELECT id FROM history_records WHERE dirty = 1").use { row ->
                assertTrue(row.step())
                assertEquals("a", row.getText(0))
            }
        }
    }

    @Test
    fun `a downloaded page is read in one query and written once per record`() =
        runBlocking {
            val driver = CountingDriver(BundledSQLiteDriver())
            val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(driver).build()
            try {
                val dao = database.historyRecordDao()
                dao.putBatchPreservingTombstones(
                    (0 until 60).map { record("r$it", serverVersion = 1, dirty = it % 2 == 0, payload = "local") },
                )
                driver.runs.clear()

                dao.applyPage(
                    "owner",
                    "origin",
                    (0 until 120).map { record("r$it", serverVersion = 2, dirty = false, payload = "remote") },
                    7,
                )

                assertEquals(1, driver.runsOf("SELECT"), "${driver.runs}")
                assertEquals(120, driver.runsOf("INSERT OR REPLACE"), "${driver.runs}")
                assertEquals(0, driver.runsOf("UPDATE"), "${driver.runs}")
                assertEquals("local", dao.record("owner", "origin", "r0")?.payload, "a local edit was overwritten")
                assertEquals(2, dao.record("owner", "origin", "r0")?.serverVersion)
                assertEquals("remote", dao.record("owner", "origin", "r1")?.payload)
                assertEquals("remote", dao.record("owner", "origin", "r119")?.payload)
                assertEquals(7, dao.cursor("owner", "origin"))
            } finally {
                database.close()
            }
        }

    @Test
    fun `a batch of local records is read in one query and written once per record`() =
        runBlocking {
            val driver = CountingDriver(BundledSQLiteDriver())
            val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(driver).build()
            try {
                val dao = database.historyRecordDao()
                dao.putBatchPreservingTombstones(listOf(record("gone", serverVersion = 1, dirty = true, payload = null, deleted = true)))
                driver.runs.clear()

                dao.putBatchPreservingTombstones(
                    (0 until 80).map { record("r$it", serverVersion = 0, dirty = true, payload = "p$it") } +
                        record("gone", serverVersion = 0, dirty = true, payload = "revived"),
                )

                assertEquals(1, driver.runsOf("SELECT"), "${driver.runs}")
                assertEquals(80, driver.runsOf("INSERT OR REPLACE"), "${driver.runs}")
                assertEquals(0, driver.runsOf("UPDATE"), "${driver.runs}")
                assertEquals("p79", dao.record("owner", "origin", "r79")?.payload)
                assertEquals(true, dao.record("owner", "origin", "gone")?.deleted, "a tombstone was revived by a stale write")
            } finally {
                database.close()
            }
        }

    private fun record(
        id: String,
        serverVersion: Long,
        dirty: Boolean,
        payload: String?,
        deleted: Boolean = false,
    ) = HistoryRecordEntity("owner", "origin", id, "place", payload, "phone", 1, serverVersion, deleted, dirty)

    private fun SQLiteConnection.queryPlan(sql: String): String =
        prepare("EXPLAIN QUERY PLAN $sql").use { row ->
            buildString {
                while (row.step()) appendLine(row.getText(3))
            }
        }

    /** Records each execution of a statement, including statements Room prepares once and reuses. */
    private class CountingDriver(
        private val delegate: SQLiteDriver,
    ) : SQLiteDriver by delegate {
        val runs = CopyOnWriteArrayList<String>()

        fun runsOf(prefix: String): Int = runs.count { it.startsWith(prefix) && "history_records" in it }

        override fun open(fileName: String): SQLiteConnection = CountingConnection(delegate.open(fileName), runs)
    }

    private class CountingConnection(
        private val delegate: SQLiteConnection,
        private val runs: MutableList<String>,
    ) : SQLiteConnection by delegate {
        override fun prepare(sql: String): SQLiteStatement = CountingStatement(delegate.prepare(sql), sql.trimStart(), runs)
    }

    private class CountingStatement(
        private val delegate: SQLiteStatement,
        private val sql: String,
        private val runs: MutableList<String>,
    ) : SQLiteStatement by delegate {
        private var running = false

        override fun step(): Boolean {
            if (!running) runs += sql
            running = true
            return delegate.step()
        }

        override fun reset() {
            running = false
            delegate.reset()
        }
    }
}
