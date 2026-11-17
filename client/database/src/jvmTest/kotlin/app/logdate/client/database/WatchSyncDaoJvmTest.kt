package app.logdate.client.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.logdate.client.database.entities.moments.MarkedMomentEntity
import app.logdate.client.database.entities.moments.MarkedMomentState
import app.logdate.client.database.entities.sync.PeerDeliveryEntity
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.entities.sync.SyncCursorEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Tests the marked moment and watch delivery DAOs and moving the outbox to a new owner. */
class WatchSyncDaoJvmTest {
    private val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(BundledSQLiteDriver()).build()

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `every delivery of a peer and direction is listed and observed`() =
        runTest {
            val dao = database.peerDeliveryDao()
            listOf(
                delivery("a", nextAttemptAt = 300),
                delivery("b", nextAttemptAt = 100),
                delivery("c", nextAttemptAt = null),
            ).forEach { dao.upsert(it) }

            assertEquals(setOf("a", "b", "c"), dao.all("phone", "TO_PHONE").map { it.entityId }.toSet())
            assertEquals(3, dao.observeAll("phone", "TO_PHONE").first().size)
        }

    @Test
    fun `a marked moment reads back by id and by state`() =
        runTest {
            val dao = database.markedMomentDao()
            val open = moment(MarkedMomentState.OPEN, capturedAt = 2)
            val filled = moment(MarkedMomentState.FILLED, capturedAt = 3)
            dao.upsert(open)
            dao.upsert(filled)

            assertEquals(open, dao.getById(open.id))
            assertEquals(listOf(open), dao.observeByState(MarkedMomentState.OPEN).first())
        }

    @Test
    fun `a deleted marked moment is not listed`() =
        runTest {
            val dao = database.markedMomentDao()
            dao.upsert(moment(MarkedMomentState.OPEN, capturedAt = 2).copy(deletedAt = Instant.fromEpochMilliseconds(9)))

            assertEquals(emptyList(), dao.observeByState(MarkedMomentState.OPEN).first())
        }

    @Test
    fun `a delivery entry reads back and is due only once its time comes`() =
        runTest {
            val dao = database.peerDeliveryDao()
            val due = delivery("a", nextAttemptAt = 100)
            val later = delivery("b", nextAttemptAt = 300)
            val settled = delivery("c", nextAttemptAt = null)
            listOf(due, later, settled).forEach { dao.upsert(it) }

            assertEquals(due, dao.get("phone", "TO_PHONE", "NOTE", "a"))
            assertEquals(listOf(due), dao.due("phone", "TO_PHONE", now = 200, limit = 10))

            dao.delete("phone", "TO_PHONE", "NOTE", "a")

            assertNull(dao.get("phone", "TO_PHONE", "NOTE", "a"))
        }

    @Test
    fun `queued uploads move to the new owner with their operation`() =
        runTest {
            val dao = database.syncMetadataDao()
            dao.insertPending(pending("local", "", "note-1", "CREATE", operationId = "op-1", retryCount = 1))
            dao.insertPending(pending("local", "", "note-2", "DELETE", operationId = "op-2"))
            dao.insertPending(pending("account", CLOUD, "note-2", "UPDATE", operationId = "op-3"))
            dao.insertPending(pending("other", "", "note-9", "CREATE", operationId = "op-9"))

            val leftBehind = dao.reassignPending("local", "", "account", CLOUD)

            assertEquals(1, leftBehind)
            assertEquals(
                pending("account", CLOUD, "note-1", "CREATE", operationId = "op-1", retryCount = 1),
                dao.getPending("account", CLOUD, "NOTE", "note-1"),
            )
            assertNull(dao.getPending("local", "", "NOTE", "note-1"))
            assertEquals("op-3", dao.getPending("account", CLOUD, "NOTE", "note-2")?.operationId)
            assertEquals("op-2", dao.getPending("local", "", "NOTE", "note-2")?.operationId)
            assertEquals("op-9", dao.getPending("other", "", "NOTE", "note-9")?.operationId)
        }

    @Test
    fun `cursors move to the new owner keeping the earlier of two`() =
        runTest {
            val dao = database.syncMetadataDao()
            dao.upsertCursor(SyncCursorEntity("local", "", "NOTE", 500))
            dao.upsertCursor(SyncCursorEntity("local", "", "JOURNAL", 700))
            dao.upsertCursor(SyncCursorEntity("account", CLOUD, "JOURNAL", 600))

            dao.reassignCursors("local", "", "account", CLOUD)

            assertEquals(500L, dao.getCursor("account", CLOUD, "NOTE")?.lastSyncTimestamp)
            assertEquals(600L, dao.getCursor("account", CLOUD, "JOURNAL")?.lastSyncTimestamp)
            assertNull(dao.getCursor("local", "", "NOTE"))
            assertNull(dao.getCursor("local", "", "JOURNAL"))
        }

    @Test
    fun `reassigning a scope to itself changes nothing`() =
        runTest {
            val dao = database.syncMetadataDao()
            dao.insertPending(pending("local", "", "note-1", "CREATE", operationId = "op-1"))
            dao.upsertCursor(SyncCursorEntity("local", "", "NOTE", 500))

            assertEquals(0, dao.reassignPending("local", "", "local", ""))
            dao.reassignCursors("local", "", "local", "")

            assertEquals("op-1", dao.getPending("local", "", "NOTE", "note-1")?.operationId)
            assertEquals(500L, dao.getCursor("local", "", "NOTE")?.lastSyncTimestamp)
        }

    private fun moment(
        state: String,
        capturedAt: Long,
    ) = MarkedMomentEntity(
        id = Uuid.random(),
        capturedAt = Instant.fromEpochMilliseconds(capturedAt),
        timeZoneId = "Europe/Paris",
        state = state,
        originDeviceId = "watch",
        lastUpdated = Instant.fromEpochMilliseconds(capturedAt),
    )

    private fun delivery(
        entityId: String,
        nextAttemptAt: Long?,
    ) = PeerDeliveryEntity(
        peerScope = "phone",
        direction = "TO_PHONE",
        entityType = "NOTE",
        entityId = entityId,
        contentVersion = 5,
        state = "PENDING",
        nextAttemptAt = nextAttemptAt,
        stateChangedAt = 1,
    )

    private fun pending(
        ownerId: String,
        serverOrigin: String,
        entityId: String,
        operation: String,
        operationId: String,
        retryCount: Int = 0,
    ) = PendingUploadEntity(
        ownerId = ownerId,
        serverOrigin = serverOrigin,
        entityType = "NOTE",
        entityId = entityId,
        operation = operation,
        createdAt = 10,
        retryCount = retryCount,
        operationId = operationId,
    )

    private companion object {
        const val CLOUD = "https://cloud.example"
    }
}
