package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.ContentUpdateRequest
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.client.sync.cloud.JournalUpdateRequest
import app.logdate.client.sync.cloud.JournalUploadRequest
import app.logdate.integration.e2e.fixtures.assertCloudError
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DeletedRecordOfflineEditE2ETest {
    @Test
    fun `offline edits return typed deletion conflicts without resurrecting notes or journals`() =
        runTest {
            withServerClientHarness {
                val account = apiClient.createAccountWithSyntheticPasskey("deleted_edit_${Random.nextInt(1000, 9999)}")
                val token = account.data.tokens.accessToken
                val note =
                    ContentUploadRequest(
                        id = "deleted-offline-note",
                        type = "TEXT",
                        content = "original",
                        mediaUri = null,
                        createdAt = 1,
                        lastUpdated = 1,
                        deviceId = DeviceId("offline-device"),
                    )
                val journal =
                    JournalUploadRequest(
                        id = "deleted-offline-journal",
                        title = "Original",
                        description = "",
                        createdAt = 1,
                        lastUpdated = 1,
                        deviceId = DeviceId("offline-device"),
                    )
                apiClient.uploadContent(token, note).getOrThrow()
                apiClient.uploadJournal(token, journal).getOrThrow()

                // These edits were made without observing the other device's deletions. Their
                // timestamps deliberately outrun the server's clock and still cannot restore IDs.
                val offlineNote = note.copy(content = "retained offline edit", lastUpdated = Long.MAX_VALUE)
                val offlineJournal = journal.copy(title = "Retained offline title", lastUpdated = Long.MAX_VALUE)
                apiClient.deleteContent(token, note.id).getOrThrow()
                apiClient.deleteJournal(token, journal.id).getOrThrow()

                assertCloudError(apiClient.uploadContent(token, offlineNote), "DELETION_CONFLICT", 409)
                assertCloudError(apiClient.uploadJournal(token, offlineJournal), "DELETION_CONFLICT", 409)
                assertCloudError(
                    apiClient.updateContent(
                        token,
                        note.id,
                        ContentUpdateRequest(content = offlineNote.content, lastUpdated = Long.MAX_VALUE),
                    ),
                    "DELETION_CONFLICT",
                    409,
                )
                assertCloudError(
                    apiClient.updateJournal(
                        token,
                        journal.id,
                        JournalUpdateRequest(title = offlineJournal.title, lastUpdated = Long.MAX_VALUE),
                    ),
                    "DELETION_CONFLICT",
                    409,
                )
                val notes = apiClient.getContentChanges(token, 0).getOrThrow()
                val journals = apiClient.getJournalChanges(token, 0).getOrThrow()
                assertTrue(notes.changes.none { it.id == note.id })
                assertTrue(notes.deletions.any { it.id == note.id })
                assertTrue(journals.changes.none { it.id == journal.id })
                assertTrue(journals.deletions.any { it.id == journal.id })
            }
        }
}
