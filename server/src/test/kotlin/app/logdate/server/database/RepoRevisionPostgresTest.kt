package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.Assume.assumeTrue
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.CarCodec
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DAG_CBOR_CODEC
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A repository stored with counter revisions keeps working against a real PostgreSQL once commits
 * use TID revisions: the next commit's revision is a TID larger than every stored one, history
 * still lists oldest first, and the export's blocks all hash to their CIDs.
 *
 * Explicit disposable PostgreSQL only, like the other `*PostgresTest` suites.
 */
class RepoRevisionPostgresTest {
    @Test
    fun `a repository with counter revisions continues with larger TID revisions`() =
        runBlocking {
            val url = System.getenv("LOGDATE_DIAGNOSTIC_TEST_DATABASE_URL")
            assumeTrue(System.getenv("LOGDATE_DIAGNOSTIC_TEST_DISPOSABLE") == "true" && url != null)
            require(java.net.URI(requireNotNull(url)).host in setOf("localhost", "127.0.0.1"))
            val previous = TransactionManager.defaultDatabase
            val source = DatabaseConfig.createDataSource(databaseUrl = url, username = null, password = null) as HikariDataSource
            val repo = AtprotoDid.require("did:plc:" + Tid.fromInstant(Clock.System.now()).value + "aaaaaaaaaaa")
            val blockStore = PostgreSQLRepoBlockStore()
            val owner = UUID.randomUUID()
            try {
                TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                createTestAccount(owner, repo.toString())
                // A clock at the Unix epoch gives revisions 0, 1, 2: the counter values that
                // repositories carry from before revisions were TIDs.
                val legacy = DefaultRepoEngine(blockStore, FixedClock(Instant.fromEpochSeconds(0)))
                repeat(3) { index ->
                    blockStore.write(owner) { legacy.putRecord(record(repo, "legacy-$index"), value("legacy")).getOrThrow() }
                }
                val legacyRevisions = blockStore.listCommits(repo).getOrThrow().map { it.commit.revision }
                assertEquals(listOf(0L, 1L, 2L), legacyRevisions)

                val now = Clock.System.now()
                val engine = DefaultRepoEngine(blockStore, FixedClock(now))
                blockStore.write(owner) { engine.putRecord(record(repo, "current"), value("current")).getOrThrow() }

                val head = assertNotNull(blockStore.readHead(repo).getOrThrow())
                assertEquals(Tid.fromInstant(now), Tid.fromLong(head.revision))
                val revisions = blockStore.listCommits(repo).getOrThrow().map { it.commit.revision }
                assertEquals(legacyRevisions + head.revision, revisions)
                val archive = CarCodec.readCar(CarCodec.write(engine.export(repo).getOrThrow()))
                assertEquals(listOf(head.commitCid), archive.roots)
                archive.blocks.forEach { block ->
                    assertEquals(block.cid, Cid.sha256(DAG_CBOR_CODEC, block.bytes), "block ${block.cid} does not hash to its CID")
                }
                assertTrue(archive.blocks.any { it.cid == head.commitCid })
            } finally {
                blockStore.write(owner) { blockStore.clearRepo(repo).getOrThrow() }
                deleteTestAccountRow(owner)
                source.close()
                TransactionManager.defaultDatabase = previous
            }
        }

    private fun record(
        repo: AtprotoDid,
        key: String,
    ) = RepoRecordId(
        repo = repo,
        collection = COLLECTION,
        recordKey = RecordKey.require(key),
    )

    private fun value(text: String) =
        buildJsonObject {
            put("\$type", COLLECTION.toString())
            put("text", text)
        }

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        val COLLECTION: Nsid = Nsid.require("studio.hypertext.logdate.entry")
    }
}
