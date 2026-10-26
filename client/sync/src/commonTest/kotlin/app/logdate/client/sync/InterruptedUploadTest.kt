package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.ContentUploadResponse
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.test.FakeCloudApiClient
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeSyncMetadataService
import app.logdate.client.sync.test.InMemorySyncDeadLetterStore
import app.logdate.client.sync.test.InMemorySyncRetryScheduleStore
import app.logdate.client.sync.test.fakeJournalNotesRepository
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.testDefaultSyncManager
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * An upload that takes the whole app down with it never reports a failure: nothing runs after it
 * to count the attempt. Without a record kept before the attempt starts, the same entry is first
 * in the queue every time the app comes back, the backup restarts, and the app goes down again --
 * for ever, with every entry queued behind it stuck too.
 *
 * Each sync run below builds a fresh [DefaultSyncManager] over the same stores, the way a restarted
 * process would find them. The process dying is an [Error] thrown from the upload call, which
 * escapes every `catch (e: Exception)` just as a real crash leaves nothing to catch it.
 */
class InterruptedUploadTest {
    private class ProcessDied : Error("The process died during the upload")

    /** Interrupts the first [times] uploads of [entryId] with [interruption], then behaves normally. */
    private class InterruptingCloudApiClient(
        private val entryId: Uuid,
        private var times: Int,
        private val interruption: () -> Throwable,
    ) : FakeCloudApiClient() {
        override suspend fun uploadContent(
            accessToken: String,
            content: ContentUploadRequest,
        ): Result<ContentUploadResponse> {
            if (content.id == entryId.toString() && times > 0) {
                times--
                throw interruption()
            }
            return super.uploadContent(accessToken, content)
        }
    }

    private val notes: FakeJournalNotesRepository = fakeJournalNotesRepository()
    private val metadata: FakeSyncMetadataService = fakeSyncMetadataService()
    private val deadLetters = InMemorySyncDeadLetterStore()
    private val retrySchedule = InMemorySyncRetryScheduleStore()

    private suspend fun queueNote(text: String): Uuid {
        val note =
            JournalNote.Text(
                uid = Uuid.random(),
                creationTimestamp = Clock.System.now(),
                lastUpdated = Clock.System.now(),
                content = text,
            )
        notes.create(note)
        metadata.addPending(note.uid, EntityType.NOTE, PendingOperation.CREATE)
        return note.uid
    }

    private fun restartedApp(apiClient: FakeCloudApiClient): DefaultSyncManager =
        testDefaultSyncManager(
            cloudContentDataSource = DefaultCloudContentDataSource(apiClient),
            journalNotesRepository = notes,
            deadLetterStore = deadLetters,
            syncMetadataService = metadata,
            retryScheduleStore = retrySchedule,
        )

    private suspend fun isQueued(id: Uuid): Boolean = metadata.getPendingUploads(EntityType.NOTE).any { it.entityId == id.toString() }

    @Test
    fun `an entry that takes the app down twice is set aside and the rest of the queue uploads`() =
        runTest {
            val poison = queueNote("the entry whose upload never finishes")
            val behind = queueNote("an ordinary entry queued behind it")
            val apiClient = InterruptingCloudApiClient(poison, times = Int.MAX_VALUE) { ProcessDied() }

            assertFailsWith<ProcessDied> { restartedApp(apiClient).fullSync() }
            assertFailsWith<ProcessDied> { restartedApp(apiClient).fullSync() }
            val result = restartedApp(apiClient).fullSync()

            val record = deadLetters.list().singleOrNull()
            assertEquals(poison.toString(), record?.entityId, "the entry should be waiting in Sync Issues")
            assertTrue(
                record!!.lastError == "APP_CLOSED",
                "the record should say what happened: ${record.lastError}",
            )
            assertTrue(isQueued(poison), "an entry that never reached the server must still read as unsynced")
            assertTrue(!isQueued(behind), "the entry queued behind it should have uploaded")
            assertTrue(!result.success, "the run should report the entry it set aside")
        }

    @Test
    fun `an entry interrupted once uploads on the next run without being set aside`() =
        runTest {
            val entry = queueNote("interrupted once, fine after")
            val apiClient = InterruptingCloudApiClient(entry, times = 1) { ProcessDied() }

            assertFailsWith<ProcessDied> { restartedApp(apiClient).fullSync() }
            restartedApp(apiClient).fullSync()

            assertTrue(deadLetters.list().isEmpty(), "one interruption can be the system or the user, not the entry")
            assertTrue(!isQueued(entry), "the entry should have uploaded")
            assertEquals(
                0,
                retrySchedule.beginAttempt(EntityType.NOTE, entry.toString()),
                "a finished upload should leave no unfinished attempt behind",
            )
        }

    @Test
    fun `an upload that fails with an error is not mistaken for the app closing`() =
        runTest {
            val entry = queueNote("fails three times, fine after")
            val apiClient =
                InterruptingCloudApiClient(entry, times = 3) { IllegalStateException("Could not record the upload") }

            repeat(4) { restartedApp(apiClient).fullSync() }

            assertTrue(deadLetters.list().isEmpty(), "an ordinary failure is not a crash: ${deadLetters.list()}")
            assertTrue(!isQueued(entry), "the entry should have uploaded once the failure cleared")
        }

    @Test
    fun `a backup that is stopped part way does not count against the entry`() =
        runTest {
            val entry = queueNote("stopped twice, fine after")
            val apiClient =
                InterruptingCloudApiClient(entry, times = 2) { CancellationException("The backup was stopped") }

            repeat(2) {
                kotlin.test.assertFailsWith<CancellationException> { restartedApp(apiClient).fullSync() }
            }
            assertTrue(restartedApp(apiClient).fullSync().success)

            assertTrue(deadLetters.list().isEmpty(), "being stopped says nothing about the entry: ${deadLetters.list()}")
            assertTrue(!isQueued(entry), "the entry should have uploaded once the backup ran to the end")
        }
}
