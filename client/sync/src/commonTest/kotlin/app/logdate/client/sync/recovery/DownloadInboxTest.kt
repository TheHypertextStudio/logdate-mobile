package app.logdate.client.sync.recovery

import app.logdate.client.database.dao.sync.DownloadInboxDao
import app.logdate.client.database.entities.sync.DownloadCheckpointEntity
import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.DraftChange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadInboxTest {
    @Test
    fun `a record encrypted by an older identity remains a record repair`() =
        runTest {
            val db = Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1L })
            inbox.stage("NOTE", 1, listOf(WireDownload("legacy", 1, false, "ciphertext")))

            inbox.failed("NOTE", "legacy", DiagnosticReason.KEY_RECOVERY_REQUIRED.name, 1)

            val row = db.rows.single()
            assertEquals(1, row.attempts)
            assertEquals(DiagnosticReason.KEY_RECOVERY_REQUIRED.name, row.reason)
        }

    @Test
    fun `a legacy wire record can be reconsidered after an earlier applied checkpoint`() =
        runTest {
            val db = Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1L })
            val records =
                listOf(
                    WireDownload("legacy", 1, false, "{\"content\":\"LDSE1:encrypted\"}"),
                    WireDownload("current", 1, false, "{\"content\":\"LDSE2:encrypted\"}"),
                )
            inbox.stage("NOTE", 100, records)
            inbox.applied("NOTE", "legacy", 1, enqueueMedia = false)
            inbox.applied("NOTE", "current", 1, enqueueMedia = false)

            inbox.stage("NOTE", 100, records)

            assertEquals(listOf("legacy"), inbox.pending("NOTE").map { it.entityId })
            assertTrue(inbox.isAppliedVersion("NOTE", "current", 1))
        }

    @Test
    fun `replaying applied rich draft restores a missing media recovery row`() =
        runTest {
            val db = Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1L })
            val wire =
                DraftChange(
                    id = "draft",
                    content = "encrypted text",
                    blockTypes = emptyList(),
                    journalIds = emptyList(),
                    createdAt = 1,
                    lastUpdated = 1,
                    deviceId = DeviceId("device"),
                    serverVersion = 4,
                    encryptedBlocksVersion = 1,
                    encryptedBlocks = "LDSE2:ciphertext",
                )
            inbox.stage("DRAFT", 4, listOf(WireDownload("draft", 4, false, Json.encodeToString(wire))))
            inbox.applied("DRAFT", "draft", 4)
            assertTrue(
                db.rows.first { it.entityType == "MEDIA_DRAFT" }.operationId !=
                    db.rows.first { it.entityType == "DRAFT" }.operationId,
            )
            db.rows.removeAll { it.entityType == "MEDIA_DRAFT" }

            inbox.applied("DRAFT", "draft", 4)

            assertEquals(listOf("draft"), inbox.pending("MEDIA_DRAFT").map { it.entityId })
            assertTrue(
                db.rows
                    .first { it.entityType == "DRAFT" }
                    .payload
                    .contains("LDSE2:ciphertext"),
            )
            assertTrue(
                !db.rows
                    .first { it.entityType == "DRAFT" }
                    .payload
                    .contains("encrypted text"),
            )
        }

    @Test
    fun `failed rows are not due until their retry time`() =
        runTest {
            val db = Database()
            var clock = 1_000L
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { clock })
            inbox.stage("NOTE", 2, listOf(WireDownload("bad", 1, false, "ciphertext")))
            inbox.failed("NOTE", "bad", DiagnosticReason.OFFLINE.name)

            assertEquals(0, inbox.dueCount())
            assertEquals(1, inbox.count())

            clock += DownloadInbox.MAX_ATTEMPTS * 60 * 60 * 1000L
            assertEquals(1, inbox.dueCount())
        }

    @Test
    fun `rows that exhaust their attempts park and a manual backup does not retry them`() =
        runTest {
            val db = Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("flaky", 1, false, "ciphertext")))
            repeat(DownloadInbox.MAX_ATTEMPTS - 1) {
                inbox.failed("NOTE", "flaky", DiagnosticReason.OFFLINE.name)
            }
            assertEquals(DownloadInboxState.FAILED.name, db.rows.single().state)

            inbox.failed("NOTE", "flaky", DiagnosticReason.OFFLINE.name)

            assertEquals(DownloadInboxState.PARKED.name, db.rows.single().state)
            inbox.release()
            assertEquals(0, inbox.dueCount())
            assertEquals(1, inbox.count())
        }

    @Test
    fun `manual backup does not make a corrupt payload row due`() =
        runTest {
            val db = Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("broken", 1, false, "ciphertext")))
            inbox.failed("NOTE", "broken", DiagnosticReason.CORRUPT_PAYLOAD.name)

            inbox.release()

            assertEquals(0, inbox.dueCount())
        }

    @Test
    fun `manual backup makes a transient failure due again`() =
        runTest {
            val db = Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("offline", 1, false, "ciphertext")))
            inbox.failed("NOTE", "offline", DiagnosticReason.OFFLINE.name)

            inbox.release()

            assertEquals(1, inbox.dueCount())
        }

    internal class Database :
        DownloadInboxDao,
        SyncTransactionManager {
        var rows = mutableListOf<DownloadInboxEntity>()
        var checkpoints = mutableListOf<DownloadCheckpointEntity>()
        var failCheckpoint = false

        override suspend fun get(
            owner: String,
            origin: String,
            type: String,
            id: String,
        ) = rows.firstOrNull {
            it.ownerId == owner &&
                it.serverOrigin == origin &&
                it.entityType == type &&
                it.entityId == id
        }

        override suspend fun pending(
            owner: String,
            origin: String,
            type: String,
            now: Long,
        ) = rows.filter {
            it.ownerId == owner &&
                it.serverOrigin == origin &&
                it.entityType == type &&
                (it.state == "WAITING" || it.state == "FAILED") &&
                it.nextAttemptAt <= now
        }

        override suspend fun count(
            owner: String,
            origin: String,
        ) = rows.count {
            it.ownerId == owner &&
                it.serverOrigin == origin &&
                it.state != "APPLIED"
        }

        override suspend fun put(row: DownloadInboxEntity) {
            rows.removeAll {
                it.ownerId == row.ownerId &&
                    it.serverOrigin == row.serverOrigin &&
                    it.entityType == row.entityType &&
                    it.entityId == row.entityId
            }
            rows.add(row)
        }

        override suspend fun checkpoint(
            owner: String,
            origin: String,
            type: String,
        ) = checkpoints.firstOrNull {
            it.ownerId == owner &&
                it.serverOrigin == origin &&
                it.entityType == type
        }

        override suspend fun checkpoint(row: DownloadCheckpointEntity) {
            check(!failCheckpoint)
            checkpoints.removeAll { it.ownerId == row.ownerId && it.serverOrigin == row.serverOrigin && it.entityType == row.entityType }
            checkpoints.add(row)
        }

        override suspend fun countNeedingAttention(
            owner: String,
            origin: String,
        ) = rows.count {
            it.ownerId == owner && it.serverOrigin == origin && (it.state == "FAILED" || it.state == "PARKED")
        }

        override suspend fun countDue(
            owner: String,
            origin: String,
            now: Long,
        ) = rows.count {
            it.ownerId == owner &&
                it.serverOrigin == origin &&
                (it.state == "WAITING" || it.state == "FAILED") &&
                it.nextAttemptAt <= now
        }

        override suspend fun release(
            owner: String,
            origin: String,
            reasons: List<String>,
        ) {
            rows =
                rows
                    .map {
                        val retryable = it.state == "WAITING" || it.state == "FAILED"
                        val releasable = it.reason in reasons
                        if (it.ownerId == owner && it.serverOrigin == origin && retryable && releasable) it.copy(nextAttemptAt = 0) else it
                    }.toMutableList()
        }

        override suspend fun <T> withTransaction(block: suspend () -> T): T {
            val before = rows.toMutableList()
            val cursors = checkpoints.toMutableList()
            try {
                return block()
            } catch (error: Throwable) {
                rows = before
                checkpoints = cursors
                throw error
            }
        }
    }

    @Test
    fun `failed records survive restart while later records settle and stale changes cannot resurrect deletion`() =
        runTest {
            val db = Database()
            val scope = { DownloadScope("owner", "origin") }
            val first = DownloadInbox(db, db, scope, { 1000L })
            first.stage("NOTE", 2, listOf(WireDownload("bad", 1, false, "ciphertext"), WireDownload("good", 2, false, "ciphertext")))
            first.failed("NOTE", "bad", "LOCAL_STORAGE")
            first.applied("NOTE", "good", 2)
            val restart = DownloadInbox(db, db, scope, { 100000L })
            assertEquals(2L, restart.cursor("NOTE"))
            assertEquals(listOf("bad"), restart.pending("NOTE").map { it.entityId })
            restart.stage("NOTE", 3, listOf(WireDownload("bad", 3, true, "tombstone")))
            restart.applied("NOTE", "bad", 1)
            assertEquals(1, restart.count())
            restart.applied("NOTE", "bad", 3)
            restart.stage("NOTE", 1, listOf(WireDownload("bad", 1, false, "old")))
            assertEquals(0, restart.count())
            assertEquals(3L, restart.cursor("NOTE"))
        }

    @Test
    fun `failed checkpoint transaction preserves old cursor and isolates account and server`() =
        runTest {
            val db = Database()
            var selected = DownloadScope("a", "first")
            val inbox = DownloadInbox(db, db, { selected }, { 0L })
            db.failCheckpoint = true
            assertFailsWith<IllegalStateException> { inbox.stage("NOTE", 2, listOf(WireDownload("one", 2, false, "ciphertext"))) }
            assertEquals(0L, inbox.cursor("NOTE"))
            assertEquals(0, inbox.count())
            db.failCheckpoint = false
            inbox.stage("NOTE", 2, listOf(WireDownload("one", 2, false, "ciphertext")))
            selected = DownloadScope("b", "first")
            assertTrue(inbox.pending("NOTE").isEmpty())
            selected = DownloadScope("a", "second")
            assertTrue(inbox.pending("NOTE").isEmpty())
        }
}
