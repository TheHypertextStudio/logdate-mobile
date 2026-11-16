package app.logdate.client.sync.recovery

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.dao.sync.DownloadRecoveryCounts
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.sync.RoomSyncTransactionManager
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomDownloadRecoveryCountsTest {
    @Test
    fun `retained pauses ready work and real failures remain distinct after restart`() =
        runTest {
            val path = Files.createTempFile("logdate-recovery-counts", ".db")
            Files.delete(path)

            fun open() = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(path.toString()))

            var database = open()
            try {
                val selected = DownloadScope("owner", "server")
                val inbox = DownloadInbox(database.downloadInboxDao(), RoomSyncTransactionManager(database), { selected }, { 1L })
                assertEquals(DownloadRecoveryCounts(), inbox.recoveryCounts())
                inbox.stage(
                    "MEDIA_NATIVE_NOTE",
                    1,
                    listOf("ready", "policy", "unsupported", "storage", "settled").map { WireDownload(it, 1, false, "original") },
                )
                inbox.failed("MEDIA_NATIVE_NOTE", "policy", "NETWORK_POLICY")
                inbox.failed("MEDIA_NATIVE_NOTE", "unsupported", "UNSUPPORTED_FORMAT")
                inbox.failed("MEDIA_NATIVE_NOTE", "storage", "LOCAL_STORAGE")
                inbox.applied("MEDIA_NATIVE_NOTE", "settled", 1)
                val foreign = DownloadScope("another-owner", "server")
                DownloadInbox(database.downloadInboxDao(), RoomSyncTransactionManager(database), { foreign }, { 1L })
                    .stage("MEDIA_NATIVE_NOTE", 1, listOf(WireDownload("foreign", 1, false, "original")))

                assertEquals(DownloadRecoveryCounts(retained = 4, retryable = 2, failed = 1), inbox.recoveryCounts())
                assertEquals(
                    DownloadRecoveryCounts(retained = 4, retryable = 2, failed = 1),
                    inbox.recoveryCounts(includePolicyDeferred = true),
                )
                assertEquals(
                    DownloadRecoveryCounts(retained = 4, retryable = 2, failed = 1),
                    database.downloadInboxDao().recoveryCounts(selected.owner, selected.origin, Long.MAX_VALUE),
                    "A due failed record remains a failure until it is retried or explicitly released",
                )
                assertEquals(1, inbox.needingAttentionCount(), "Unsupported formats alone do not fail backup status")
                inbox.release()
                assertEquals(
                    "WAITING",
                    database.downloadInboxDao().get(selected.owner, selected.origin, "MEDIA_NATIVE_NOTE", "storage")?.state,
                )
                assertEquals(1, database.downloadInboxDao().get(selected.owner, selected.origin, "MEDIA_NATIVE_NOTE", "storage")?.attempts)
                assertEquals(0, inbox.needingAttentionCount())
                assertEquals(
                    DownloadRecoveryCounts(retained = 4, retryable = 3, failed = 0),
                    inbox.recoveryCounts(includePolicyDeferred = true),
                )
                val dao = database.downloadInboxDao()
                assertEquals(2, dao.countDue(selected.owner, selected.origin, 1L), "Policy-held work waits for its connection")
                assertEquals(3, dao.countDue(selected.owner, selected.origin, 1L, includePolicyDeferred = true))
                assertEquals(
                    2,
                    dao.countDue(selected.owner, selected.origin, Long.MAX_VALUE),
                    "Neither the policy hold nor the wait for an app update ends with the backoff",
                )
                assertEquals(3, dao.countDue(selected.owner, selected.origin, Long.MAX_VALUE, includePolicyDeferred = true))
                database.close()
                database = open()
                assertEquals(
                    DownloadRecoveryCounts(retained = 4, retryable = 2, failed = 0),
                    database.downloadInboxDao().recoveryCounts(selected.owner, selected.origin, 1L),
                )
            } finally {
                database.close()
                Files.deleteIfExists(path)
                Files.deleteIfExists(path.resolveSibling("${path.fileName}-wal"))
                Files.deleteIfExists(path.resolveSibling("${path.fileName}-shm"))
            }
        }
}
