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

class PeriodicSyncDueWorkTest {
    @Test
    fun `a row still in backoff is not due work`() =
        runTest {
            val db = DownloadInboxTest.Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("flaky", 1, false, "ciphertext")))
            inbox.failed("NOTE", "flaky", DiagnosticReason.OFFLINE.name)
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)

            assertFalse(manager.hasDueWork())
        }

    @Test
    fun `a row whose retry time has arrived is due work`() =
        runTest {
            val db = DownloadInboxTest.Database()
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { 1_000L })
            inbox.stage("NOTE", 2, listOf(WireDownload("waiting", 1, false, "ciphertext")))
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)

            assertTrue(manager.hasDueWork())
        }
}
