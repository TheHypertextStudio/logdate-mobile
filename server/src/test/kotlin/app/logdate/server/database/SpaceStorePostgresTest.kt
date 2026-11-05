package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.localTestPostgresUrl
import app.logdate.server.entitlements.DatabaseUsageCalculator
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceCursorTooOldException
import app.logdate.server.space.SpacePurgeResult
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceQuotaExceededException
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.recordCid
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Runs against the local test PostgreSQL only; forces competing transactions to wait on each other's locks. */
class SpaceStorePostgresTest {
    private class Fixture(
        val source: HikariDataSource,
        val owner: UUID,
    ) {
        val author = "did:plc:${owner.toString().replace("-", "").take(24)}"
        val space = "at://$author/space/app.logdate.space.personal/self"
        val store = PostgreSQLSpaceStore()

        fun write(
            rkey: String,
            blobs: List<String> = emptyList(),
        ) = SpaceRecordWrite(owner, space, author, rkey, sealed(), blobs, swapCid = null, createOnly = false)
    }

    @Test
    fun `concurrent writes get distinct sequence numbers and account deletion removes every space row`() =
        withFixture { fixture ->
            with(fixture) {
                val initial = (1..8).map { async(Dispatchers.IO) { store.put(write("initial-$it")).seq } }.awaitAll()
                assertEquals((1L..8L).toList(), initial.sorted(), "Concurrent first writes must share one sequence")

                source.connection.use { lock ->
                    lock.autoCommit = false
                    lock.prepareStatement("SELECT last_seq FROM space_states WHERE space_uri = ? AND author_did = ? FOR UPDATE").use {
                        it.setString(1, space)
                        it.setString(2, author)
                        it.executeQuery().close()
                    }
                    val first = async(Dispatchers.IO) { store.put(write("first")) }
                    val second = async(Dispatchers.IO) { store.put(write("second")) }
                    try {
                        withTimeout(15_000) {
                            while (waiting(
                                    source,
                                    "wait_event_type = 'Lock' AND (query LIKE '%space_states%' OR query LIKE '%identity_epoch%')",
                                ) < 2
                            ) {
                                delay(25)
                            }
                        }
                    } finally {
                        lock.rollback()
                    }
                    val seqs = listOf(first.await().seq, second.await().seq)
                    assertEquals(listOf(9L, 10L), seqs.sorted(), "Concurrent writes must not share a sequence number")
                }

                val blobPath = "users/$owner/spaces/blob"
                store.putBlob(owner, space, SpaceBlob("bafyblob", "application/octet-stream", 1, blobPath))
                store.put(write("with-blob", blobs = listOf("bafyblob")))
                store.delete(owner, space, author, "first", swapCid = null)
                assertEquals(1, count(source, "SELECT count(*) FROM space_record_blobs WHERE space_uri = ?", space))

                transaction { exec("DELETE FROM accounts WHERE id = '$owner'") }

                assertEquals(0, count(source, "SELECT count(*) FROM space_states WHERE account_id = ?", owner))
                assertEquals(0, count(source, "SELECT count(*) FROM space_records WHERE account_id = ?", owner))
                assertEquals(0, count(source, "SELECT count(*) FROM space_record_blobs WHERE space_uri = ?", space))
                assertEquals(0, count(source, "SELECT count(*) FROM space_blobs WHERE account_id = ?", owner))
                assertEquals(
                    1,
                    count(
                        source,
                        "SELECT count(*) FROM account_blob_cleanup_paths WHERE owner_id = ? AND storage_path = ?",
                        owner,
                        blobPath,
                    ),
                    "Deleting the account must queue its space blobs for deletion from object storage",
                )
            }
        }

    @Test
    fun `purge keeps a blob that an open write is committing a reference to`() =
        withFixture { fixture ->
            with(fixture) {
                val blobPath = "users/$owner/spaces/racing"
                store.putBlob(owner, space, SpaceBlob("bafyracing", "application/octet-stream", 1, blobPath))
                val gate = owner.mostSignificantBits
                val pause = "pause_space_reference_${owner.toString().replace("-", "").take(12)}"
                source.connection.use { control ->
                    control.autoCommit = true
                    // Holds the write open inside its transaction, after it has locked the blob and inserted
                    // the reference, until the test releases the advisory lock.
                    control.createStatement().use { sql ->
                        sql.execute(
                            "CREATE FUNCTION $pause() RETURNS trigger LANGUAGE plpgsql AS " +
                                "\$\$ BEGIN PERFORM pg_advisory_xact_lock($gate); RETURN NEW; END; \$\$",
                        )
                        sql.execute(
                            "CREATE TRIGGER $pause AFTER INSERT ON space_record_blobs FOR EACH ROW " +
                                "WHEN (NEW.space_uri = '$space') EXECUTE FUNCTION $pause()",
                        )
                        sql.execute("SELECT pg_advisory_lock($gate)")
                    }
                    try {
                        val put = async(Dispatchers.IO) { store.put(write("racing", blobs = listOf("bafyracing"))) }
                        withTimeout(15_000) {
                            while (waiting(source, "wait_event = 'advisory' AND query LIKE '%space_record_blobs%'") < 1) delay(25)
                        }
                        val purged = ConcurrentLinkedQueue<String>()
                        val purge =
                            async(Dispatchers.IO) {
                                store.purge(
                                    tombstonesBefore = Clock.System.now() - 1.hours,
                                    unreferencedBlobsBefore = Clock.System.now() + 1.hours,
                                ) { purged += it }
                            }
                        withTimeout(15_000) {
                            while (!purge.isCompleted &&
                                waiting(
                                    source,
                                    "wait_event_type = 'Lock' AND wait_event <> 'advisory' " +
                                        "AND (query LIKE '%space_blobs%' OR query LIKE '%identity_epoch%')",
                                ) < 1
                            ) {
                                delay(25)
                            }
                        }
                        control.createStatement().use { it.execute("SELECT pg_advisory_unlock($gate)") }

                        val stored = put.await()
                        purge.await()
                        assertFalse(blobPath in purged, "Purge must not delete a blob a committing write refers to")
                        assertNotNull(store.getBlob(space, "bafyracing"))
                        assertEquals(stored, store.get(space, author, "racing"))
                        assertEquals(
                            1,
                            count(source, "SELECT count(*) FROM space_record_blobs WHERE space_uri = ? AND cid = ?", space, "bafyracing"),
                        )
                    } finally {
                        control.createStatement().use { sql ->
                            sql.execute("SELECT pg_advisory_unlock_all()")
                            sql.execute("DROP TRIGGER $pause ON space_record_blobs")
                            sql.execute("DROP FUNCTION $pause()")
                        }
                    }
                }
            }
        }

    @Test
    fun `purge remembers purged tombstones and keeps a blob whose object delete fails`() =
        withFixture { fixture ->
            with(fixture) {
                store.put(write("a"))
                store.put(write("b"))
                val deleted = store.delete(owner, space, author, "a", swapCid = null)
                val blobPath = "users/$owner/spaces/stubborn"
                store.putBlob(owner, space, SpaceBlob("bafystubborn", "application/octet-stream", 1, blobPath))
                val later = Clock.System.now() + 1.hours

                val failing = store.purge(tombstonesBefore = later, unreferencedBlobsBefore = later) { error("The object store is down") }

                assertEquals(SpacePurgeResult(blobsDeleted = 0, blobsFailed = 1), failing)
                assertNull(store.getBlob(space, "bafystubborn"))
                assertEquals(
                    1,
                    count(source, "SELECT COUNT(*) FROM space_blobs WHERE space_uri = ? AND purge_operation_id IS NOT NULL", space),
                )
                assertEquals(
                    deleted.toInt(),
                    count(source, "SELECT purged_through_seq FROM space_states WHERE space_uri = ? AND author_did = ?", space, author),
                )
                assertFailsWith<SpaceCursorTooOldException> { store.changes(space, author, 1, 10) }
                assertEquals(listOf("b"), store.changes(space, author, 0, 10).changes.map { it.rkey })

                val purged = ConcurrentLinkedQueue<String>()
                assertEquals(SpacePurgeResult(blobsDeleted = 1, blobsFailed = 0), store.purge(later, later) { purged += it })
                assertEquals(listOf(blobPath), purged.toList())
                assertNull(store.getBlob(space, "bafystubborn"))
            }
        }

    @Test
    fun `storage usage counts live space records and blobs but not tombstones`() =
        withFixture { fixture ->
            with(fixture) {
                val usage = DatabaseUsageCalculator(requireNotNull(TransactionManager.defaultDatabase))
                assertEquals(0L, usage.storageBytes(owner))
                val kept = store.put(write("kept"))
                store.put(write("deleted"))
                store.delete(owner, space, author, "deleted", swapCid = null)
                store.putBlob(owner, space, SpaceBlob("bafycounted", "application/octet-stream", 3_000_000_000L, "users/$owner/spaces/big"))
                val recordBytes = recordCid(kept.record).second.size.toLong()
                assertEquals(recordBytes + 3_000_000_000L, usage.storageBytes(owner))
                assertEquals(0L, usage.storageBytesOutsideSpaces(owner))
                assertEquals(
                    number(source, "SELECT space_bytes - 3000000000 FROM account_storage_usage WHERE account_id = ?", owner),
                    number(
                        source,
                        "SELECT COALESCE(SUM(size_bytes), 0) FROM space_records WHERE account_id = ? AND NOT deleted",
                        owner,
                    ),
                    "The running total matches the rows it counts",
                )
            }
        }

    @Test
    fun `requests that race for the last room wait on the account's usage lock and only those that fit are stored`() =
        withFixture { fixture ->
            with(fixture) {
                store.put(write("first"))
                val size = store.storedBytes(owner)
                val quota = SpaceQuota(limitBytes = size * 3, otherBytes = 0)
                source.connection.use { lock ->
                    lock.autoCommit = false
                    lock.prepareStatement("SELECT space_bytes FROM account_storage_usage WHERE account_id = ? FOR UPDATE").use {
                        it.setObject(1, owner)
                        it.executeQuery().close()
                    }
                    val racers =
                        listOf(
                            async(Dispatchers.IO) { runCatching { store.put(write("second").copy(quota = quota)) } },
                            async(Dispatchers.IO) { runCatching { store.put(write("third").copy(quota = quota)) } },
                            async(Dispatchers.IO) { runCatching { store.reserveUpload(owner, size, quota) } },
                            async(Dispatchers.IO) { runCatching { store.reserveUpload(owner, size, quota) } },
                        )
                    try {
                        withTimeout(15_000) {
                            // Each mutation takes the account fence before reaching state and usage locks.
                            while (waiting(
                                    source,
                                    "wait_event_type = 'Lock' AND (query LIKE '%account_storage_usage%' " +
                                        "OR query LIKE '%space_states%' OR query LIKE '%identity_epoch%')",
                                ) <
                                4
                            ) {
                                delay(25)
                            }
                        }
                    } finally {
                        lock.rollback()
                    }
                    val outcomes = racers.awaitAll()
                    assertEquals(2, outcomes.count { it.isSuccess }, "Only two of four requests fit: $outcomes")
                    assertTrue(outcomes.mapNotNull { it.exceptionOrNull() }.all { it is SpaceQuotaExceededException }, "$outcomes")
                }
                val held = number(source, "SELECT COALESCE(SUM(bytes), 0) FROM space_upload_reservations WHERE account_id = ?", owner)
                assertEquals(size * 3, store.storedBytes(owner) + held, "Stored and held bytes fill the quota exactly")
            }
        }

    private fun withFixture(block: suspend CoroutineScope.(Fixture) -> Unit) =
        runBlocking {
            val url = localTestPostgresUrl()
            val previous = TransactionManager.defaultDatabase
            val source = localTestPostgresDataSource(url)
            val owner = UUID.randomUUID()
            try {
                TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                insertTestAccountRow(owner)
                block(Fixture(source, owner))
            } finally {
                deleteTestAccountRow(owner)
                source.close()
                TransactionManager.defaultDatabase = previous
            }
        }

    private fun waiting(
        source: HikariDataSource,
        condition: String,
    ): Int = count(source, "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND $condition")

    private fun count(
        source: HikariDataSource,
        sql: String,
        vararg parameters: Any,
    ): Int = number(source, sql, *parameters).toInt()

    private fun number(
        source: HikariDataSource,
        sql: String,
        vararg parameters: Any,
    ): Long =
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
}

private fun sealed() =
    buildJsonObject {
        put("\$type", "app.logdate.crypto.sealed")
        put("version", 1)
        put("keyId", "abcdefghijklm")
        putJsonObject("nonce") { put("\$bytes", Base64.encode(ByteArray(12))) }
        putJsonObject("ciphertext") { put("\$bytes", Base64.encode(ByteArray(528))) }
    }
