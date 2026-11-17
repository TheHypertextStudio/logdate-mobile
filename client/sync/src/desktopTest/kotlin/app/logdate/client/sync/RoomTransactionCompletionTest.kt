package app.logdate.client.sync

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.getRoomDatabase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomTransactionCompletionTest {
    @Test
    fun `cleanup sees a committed outbox from an independent database connection`() =
        runTest {
            withDatabases { database, observer ->
                val manager = RoomSyncTransactionManager(database)
                val operation = pending("committed")
                var cleaned = false
                manager.withTransaction {
                    database.syncMetadataDao().insertPending(operation)
                    manager.afterCommit {
                        assertEquals(operation, observer.syncMetadataDao().getPending("owner", "origin", "DRAFT", operation.entityId))
                        cleaned = true
                    }
                    assertFalse(cleaned)
                }
                assertTrue(cleaned)
            }
        }

    @Test
    fun `outer rollback preserves pending cleanup and rolls back its outbox`() =
        runTest {
            withDatabases { database, observer ->
                val manager = RoomSyncTransactionManager(database)
                var cleaned = false
                assertFailsWith<IllegalStateException> {
                    manager.withTransaction {
                        database.syncMetadataDao().insertPending(pending("rolled-back"))
                        manager.afterCommit { cleaned = true }
                        error("Rollback fixture")
                    }
                }
                assertFalse(cleaned)
                assertNull(observer.syncMetadataDao().getPending("owner", "origin", "DRAFT", "rolled-back"))
            }
        }

    @Test
    fun `caught nested rollback cannot clear its local intent after the outer commit`() =
        runTest {
            withDatabases { database, observer ->
                val manager = RoomSyncTransactionManager(database)
                val cleaned = mutableListOf<String>()
                manager.withTransaction {
                    database.syncMetadataDao().insertPending(pending("outer"))
                    manager.afterCommit { cleaned += "outer" }
                    assertFailsWith<IllegalStateException> {
                        manager.withTransaction {
                            database.syncMetadataDao().insertPending(pending("inner"))
                            manager.afterCommit { cleaned += "inner" }
                            error("Nested rollback fixture")
                        }
                    }
                }
                assertEquals(listOf("outer"), cleaned)
                assertEquals(
                    pending("outer").entityId,
                    observer.syncMetadataDao().getPending("owner", "origin", "DRAFT", "outer")?.entityId,
                )
                assertNull(observer.syncMetadataDao().getPending("owner", "origin", "DRAFT", "inner"))
            }
        }

    private fun pending(id: String) = PendingUploadEntity("owner", "origin", "DRAFT", id, "DELETE", 10)

    private suspend fun withDatabases(block: suspend (LogDateDatabase, LogDateDatabase) -> Unit) {
        val path = Files.createTempFile("logdate-commit-boundary-", ".db")
        Files.delete(path)
        val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(path.toString()))
        val observer = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(path.toString()))
        try {
            block(database, observer)
        } finally {
            observer.close()
            database.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.resolveSibling("${path.fileName}-wal"))
            Files.deleteIfExists(path.resolveSibling("${path.fileName}-shm"))
        }
    }
}
