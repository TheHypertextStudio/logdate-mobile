@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.localTestPostgresUrl
import app.logdate.server.logdate.LogDateCollectionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Runs against the local test PostgreSQL only; queues competing writes at account/state admission before checking persisted pagination. */
class CollectionVersionPostgresTest {
    @Test
    fun `concurrent writes have unique versions and cannot vanish between single record pages`() =
        runBlocking {
            val url = localTestPostgresUrl()
            val previous = TransactionManager.defaultDatabase
            val source = localTestPostgresDataSource(url)
            val owner = Uuid.random().toJavaUUID()
            try {
                TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                insertTestAccountRow(owner)
                val store = PostgreSQLLogDateCollectionsMetadataStore()
                val did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
                store.upsert(owner, did, LogDateCollectionKind.ENTRY, "seed")
                val floor = System.currentTimeMillis() + 86_400_000L
                transaction {
                    LogDateCollectionStatesTable.update({ LogDateCollectionStatesTable.userId eq owner }) {
                        it[lastVersion] = floor
                    }
                }
                source.connection.use { lock ->
                    lock.autoCommit = false
                    lock.prepareStatement("SELECT user_id FROM logdate_collection_states WHERE user_id = ? FOR UPDATE").use {
                        it.setObject(1, owner)
                        it.executeQuery().close()
                    }
                    val first = async(Dispatchers.IO) { store.upsert(owner, did, LogDateCollectionKind.ENTRY, "first") }
                    val second = async(Dispatchers.IO) { store.upsert(owner, did, LogDateCollectionKind.ENTRY, "second") }
                    try {
                        withTimeout(15_000) {
                            while (true) {
                                val blocked =
                                    source.connection.use { monitor ->
                                        monitor.createStatement().use { statement ->
                                            statement
                                                .executeQuery(
                                                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                                        "AND wait_event_type = 'Lock' AND (query LIKE '%logdate_collection_states%' " +
                                                        "OR query LIKE '%FROM accounts WHERE%')",
                                                ).use { rows ->
                                                    rows.next()
                                                    rows.getInt(1)
                                                }
                                        }
                                    }
                                if (blocked >= 2) break
                                delay(25)
                            }
                        }
                    } finally {
                        lock.rollback()
                    }
                    val versions = listOf(first.await().version, second.await().version)
                    assertEquals(2, versions.distinct().size, "Concurrent changes must not share a cursor version")
                    assertTrue(versions.all { it > floor })
                }
                val page1 = store.changes(owner, LogDateCollectionKind.ENTRY, floor, 1)
                val page2 = store.changes(owner, LogDateCollectionKind.ENTRY, page1.lastTimestamp, 1)
                assertEquals(setOf("first", "second"), (page1.changes + page2.changes).map { it.recordKey }.toSet())
                val mixed =
                    listOf(
                        async(Dispatchers.IO) { store.upsert(owner, did, LogDateCollectionKind.ENTRY, "third").version },
                        async(
                            Dispatchers.IO,
                        ) { requireNotNull(store.delete(owner, did, LogDateCollectionKind.ENTRY, "first", floor)).version },
                    ).awaitAll()
                assertEquals(2, mixed.distinct().size)
                val deleted = store.changes(owner, LogDateCollectionKind.ENTRY, page2.lastTimestamp, 10)
                assertEquals(listOf("first"), deleted.deletions.map { it.recordKey })
                transaction {
                    LogDateCollectionRecordsTable.deleteWhere { userId eq owner }
                    LogDateCollectionStatesTable.deleteWhere { userId eq owner }
                }
                val initial =
                    (1..8)
                        .map { index ->
                            async(Dispatchers.IO) { store.upsert(owner, did, LogDateCollectionKind.ENTRY, "initial-$index").version }
                        }.awaitAll()
                assertEquals(8, initial.distinct().size)
            } finally {
                transaction {
                    LogDateCollectionRecordsTable.deleteWhere { userId eq owner }
                    LogDateCollectionStatesTable.deleteWhere { userId eq owner }
                }
                deleteTestAccountRow(owner)
                source.close()
                TransactionManager.defaultDatabase = previous
            }
        }
}
