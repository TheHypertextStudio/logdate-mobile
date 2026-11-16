package app.logdate.client.sync

import app.logdate.client.networking.DataUsageMode
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadInboxTest
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.WireDownload
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeSyncMetadataService
import app.logdate.client.sync.test.fakeDataUsagePolicy
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.shared.model.diagnostics.DiagnosticReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PeriodicSyncDueWorkTest {
    @Test
    fun `legacy media rows do not keep waking a scheduler with no upload handler`() =
        runTest {
            val metadata = FakeSyncMetadataService()
            repeat(827) { metadata.enqueuePending("old-media-$it", EntityType.MEDIA, PendingOperation.CREATE) }
            repeat(3) {
                val manager = testDefaultSyncManager(syncMetadataService = metadata, syncScope = backgroundScope)
                assertFalse(manager.hasDueWork(), "Unchanged retained rows must not trigger another full sync after restart")
            }
            kotlin.test.assertEquals(827, metadata.getPendingUploads(EntityType.MEDIA).size)
            val notes = FakeJournalNotesRepository()
            val note = notes.addTestNote("Written today")
            metadata.enqueuePending(note.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
            assertTrue(
                testDefaultSyncManager(
                    syncMetadataService = metadata,
                    journalNotesRepository = notes,
                    syncScope = backgroundScope,
                ).hasDueWork(),
            )
        }

    @Test
    fun `a pending note that can never upload is due work until a pass moves it to Sync Issues`() =
        runTest {
            val notes = FakeJournalNotesRepository()
            val malformed = FakeSyncMetadataService()
            malformed.enqueuePending("not-a-note-id", EntityType.NOTE, PendingOperation.CREATE)
            val missing = FakeSyncMetadataService()
            missing.enqueuePending(Uuid.random().toString(), EntityType.NOTE, PendingOperation.CREATE)

            for (metadata in listOf(malformed, missing)) {
                val manager =
                    testDefaultSyncManager(syncMetadataService = metadata, journalNotesRepository = notes, syncScope = backgroundScope)
                assertTrue(manager.hasDueWork(), "Only a pass can move the row out of the pending queue")
            }
        }

    @Test
    fun `a row still in backoff is not due work until its retry time`() =
        runTest {
            val db = DownloadInboxTest.Database()
            var clock = 1_000L
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { clock })
            inbox.stage("NOTE", 2, listOf(WireDownload("flaky", 1, false, "ciphertext")))
            inbox.failed("NOTE", "flaky", DiagnosticReason.OFFLINE.name)
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)

            assertFalse(manager.hasDueWork())

            clock = db.rows.single().nextAttemptAt - 1
            assertFalse(manager.hasDueWork())

            clock = db.rows.single().nextAttemptAt
            assertTrue(manager.hasDueWork())
        }

    @Test
    fun `a download held for Wi-Fi is due work only on a connection that allows it`() =
        runTest {
            val db = DownloadInboxTest.Database()
            var clock = 1_000L
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { clock })
            inbox.stage("MEDIA_NOTE", 2, listOf(WireDownload("held", 1, false, "original")))
            inbox.failed("MEDIA_NOTE", "held", DiagnosticReason.NETWORK_POLICY.name)

            fun managerOn(mode: DataUsageMode) =
                testDefaultSyncManager(
                    downloadInbox = inbox,
                    transactionManager = db,
                    dataUsagePolicy = fakeDataUsagePolicy(mode),
                    syncScope = backgroundScope,
                )

            assertFalse(managerOn(DataUsageMode.Unrestricted).hasDueWork(), "The deferral's own wait has not passed")

            clock = db.rows.single().nextAttemptAt
            assertFalse(managerOn(DataUsageMode.Conservative).hasDueWork())
            assertTrue(managerOn(DataUsageMode.Unrestricted).hasDueWork())
        }

    @Test
    fun `a download only a newer app version can read is never due work`() =
        runTest {
            val db = DownloadInboxTest.Database()
            var clock = 1_000L
            val inbox = DownloadInbox(db, db, { DownloadScope("owner", "origin") }, { clock })
            inbox.stage("NOTE", 2, listOf(WireDownload("newer", 1, false, "ciphertext")))
            inbox.failed("NOTE", "newer", DiagnosticReason.UNSUPPORTED_FORMAT.name)
            val manager = testDefaultSyncManager(downloadInbox = inbox, transactionManager = db, syncScope = backgroundScope)

            clock = db.rows.single().nextAttemptAt
            assertFalse(manager.hasDueWork(), "Retrying cannot finish it, so its backoff ending is not work")
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
