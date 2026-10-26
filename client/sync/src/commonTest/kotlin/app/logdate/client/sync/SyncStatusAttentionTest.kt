package app.logdate.client.sync

import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadInboxTest
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.WireDownload
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.shared.model.diagnostics.DiagnosticReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncStatusAttentionTest {
    @Test
    fun `a row waiting for its first attempt is not reported as an error`() =
        runTest {
            val db = DownloadInboxTest.Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("waiting", 1, false, "ciphertext")))
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)

            assertFalse(manager.getSyncStatus().hasErrors)
        }

    @Test
    fun `a parked row is reported as needing attention`() =
        runTest {
            val db = DownloadInboxTest.Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("broken", 1, false, "ciphertext")))
            repeat(DownloadInbox.MAX_ATTEMPTS) { inbox.failed("NOTE", "broken", DiagnosticReason.CORRUPT_PAYLOAD.name) }
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)

            assertTrue(manager.getSyncStatus().hasErrors)
        }

    @Test
    fun `waiting inbox work alone does not fail a worker pass`() =
        runTest {
            val db = DownloadInboxTest.Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("waiting", 1, false, "ciphertext")))
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)
            assertTrue(manager.fullSync().success)
            assertFalse(manager.getSyncStatus().hasErrors)
            assertTrue(inbox.count() > 0)
        }
}
