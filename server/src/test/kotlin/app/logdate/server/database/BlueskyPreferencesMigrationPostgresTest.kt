package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withOwnPostgresDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class BlueskyPreferencesMigrationPostgresTest {
    @Test
    fun `preferences survive reconnecting and are deleted with their account`() {
        withOwnPostgresDatabase("bsky_preferences_") { url ->
            val account = Uuid.random()
            val other = Uuid.random()
            val initial = Json.parseToJsonElement("""[{"${'$'}type":"app.bsky.actor.defs#feedViewPref","feed":"home"}]""").jsonArray
            val updated = Json.parseToJsonElement("""[{"${'$'}type":"app.bsky.actor.defs#feedViewPref","feed":"friends"}]""").jsonArray
            val previous = TransactionManager.defaultDatabase
            try {
                localTestPostgresDataSource(url).use { source ->
                    Flyway
                        .configure()
                        .dataSource(source)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate()
                    val database = Database.connect(source)
                    source.connection.use { connection ->
                        connection.autoCommit = true
                        connection.createStatement().use { statement ->
                            statement.execute(
                                "INSERT INTO accounts(id, username, display_name, created_at) " +
                                    "VALUES ('$account', 'first', 'First', now()), ('$other', 'second', 'Second', now())",
                            )
                        }
                    }
                    runBlocking {
                        val store = PostgreSQLBlueskyPreferencesStore(database)
                        store.replace(account, initial)
                        store.replace(account, updated)
                        assertTrue(store.read(other).isEmpty())
                    }
                }
                localTestPostgresDataSource(url).use { source ->
                    val database = Database.connect(source)
                    runBlocking {
                        val store = PostgreSQLBlueskyPreferencesStore(database)
                        assertEquals(updated, store.read(account))
                        source.connection.use { connection ->
                            connection.autoCommit = true
                            connection.createStatement().use { it.execute("DELETE FROM accounts WHERE id = '$account'") }
                        }
                        assertTrue(store.read(account).isEmpty())
                    }
                }
            } finally {
                TransactionManager.defaultDatabase = previous
            }
        }
    }
}
