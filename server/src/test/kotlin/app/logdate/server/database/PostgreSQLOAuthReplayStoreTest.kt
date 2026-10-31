package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withH2Database
import app.logdate.server.oauth.MutableClock
import app.logdate.server.oauth.replayKeyHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The shared replay store refuses a second use of a DPoP proof or PKCE challenge even when the
 * second use reaches a different server instance, because every instance writes to one table.
 */
class PostgreSQLOAuthReplayStoreTest {
    @Test
    fun `a value used through one store is refused through another sharing the database`() =
        withH2Database(OAuthUsedValuesTable) {
            runBlocking {
                val firstInstance = PostgreSQLOAuthReplayStore()
                val secondInstance = PostgreSQLOAuthReplayStore()

                assertTrue(firstInstance.firstUse("dpop:key:proof-1", 10.minutes))
                assertFalse(secondInstance.firstUse("dpop:key:proof-1", 10.minutes))
                assertTrue(secondInstance.firstUse("dpop:key:proof-2", 10.minutes))
            }
        }

    @Test
    fun `only a hash of the value is stored`() =
        withH2Database(OAuthUsedValuesTable) {
            runBlocking {
                PostgreSQLOAuthReplayStore().firstUse("pkce:example-challenge", 10.minutes)

                val stored = transaction { OAuthUsedValuesTable.selectAll().single()[OAuthUsedValuesTable.valueHash] }

                assertEquals(64, stored.length)
                assertFalse("example-challenge" in stored)
            }
        }

    @Test
    fun `an expired value can be used again and expired rows are cleaned up`() =
        withH2Database(OAuthUsedValuesTable) {
            runBlocking {
                val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
                val store = PostgreSQLOAuthReplayStore(clock)
                store.firstUse("pkce:old", 1.minutes)
                store.firstUse("pkce:other-old", 1.minutes)

                clock.nowValue = clock.nowValue + 2.minutes

                assertTrue(store.firstUse("pkce:old", 1.minutes))
                assertEquals(1, transaction { OAuthUsedValuesTable.selectAll().count() }, "only the new record is left")
            }
        }

    /**
     * Runs against the safe local PostgreSQL test fixture, after Flyway applies every migration,
     * so the real table and the real insert-if-absent are what is checked.
     */
    @Test
    fun `instances racing on PostgreSQL accept a value exactly once`() {
        val previous = TransactionManager.defaultDatabase
        val source = localTestPostgresDataSource()
        val key = "dpop:race:${UUID.randomUUID()}"
        try {
            TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
            val accepted =
                runBlocking {
                    (1..16)
                        .map { async(Dispatchers.IO) { PostgreSQLOAuthReplayStore(Clock.System).firstUse(key, 10.minutes) } }
                        .awaitAll()
                }

            assertEquals(1, accepted.count { it }, "exactly one instance accepts the value")
            assertFalse(runBlocking { PostgreSQLOAuthReplayStore().firstUse(key, 10.minutes) })
        } finally {
            transaction { OAuthUsedValuesTable.deleteWhere { valueHash eq replayKeyHash(key) } }
            TransactionManager.defaultDatabase = previous
            source.close()
        }
    }
}
