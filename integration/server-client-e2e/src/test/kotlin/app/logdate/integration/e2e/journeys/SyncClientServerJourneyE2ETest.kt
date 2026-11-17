package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.Association
import app.logdate.client.sync.cloud.AssociationDeleteItem
import app.logdate.client.sync.cloud.AssociationDeleteRequest
import app.logdate.client.sync.cloud.AssociationUploadRequest
import app.logdate.client.sync.cloud.ContentUpdateRequest
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.client.sync.cloud.JournalUpdateRequest
import app.logdate.client.sync.cloud.JournalUploadRequest
import app.logdate.client.sync.cloud.MediaUploadRequest
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.fixtures.uploadMedia
import app.logdate.integration.e2e.harness.withServerClientHarness
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration journey test validating the end-to-end synchronization lifecycle between
 * the client application and a live server instance.
 *
 * This suite exercises the full spectrum of the sync API, including the creation and
 * modification of multi-modal content (text and media), the management of journal
 * hierarchies, and the resolution of cross-entity associations, ensuring that the
 * distributed system maintains consistency across all standard user operations.
 */
class SyncClientServerJourneyE2ETest {
    @Test
    fun `client sync lifecycle succeeds against real server routes`() =
        kotlinx.coroutines.test.runTest {
            withServerClientHarness {
                val username = "journey_sync_${Random.nextInt(1000, 9999)}"
                val account = apiClient.createAccountWithSyntheticPasskey(username)
                val accessToken = account.data.tokens.accessToken
                val now = 1_700_000_000_000L

                val uploadContent =
                    apiClient.uploadContent(
                        accessToken = accessToken,
                        content =
                            ContentUploadRequest(
                                id = "content-1",
                                type = "TEXT",
                                content = "hello",
                                mediaUri = null,
                                createdAt = now,
                                lastUpdated = now,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                assertTrue(uploadContent.isSuccess)

                val contentChanges = apiClient.getContentChanges(accessToken = accessToken, since = 0)
                assertTrue(contentChanges.isSuccess)
                assertTrue(contentChanges.getOrThrow().changes.any { it.id == "content-1" })

                val updateContent =
                    apiClient.updateContent(
                        accessToken = accessToken,
                        contentId = "content-1",
                        content =
                            ContentUpdateRequest(
                                content = "hello-updated",
                                lastUpdated = now + 1_000,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                assertTrue(updateContent.isSuccess)

                val uploadJournal =
                    apiClient.uploadJournal(
                        accessToken = accessToken,
                        journal =
                            JournalUploadRequest(
                                id = "journal-1",
                                title = "Journal",
                                description = "Description",
                                createdAt = now,
                                lastUpdated = now,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                assertTrue(uploadJournal.isSuccess)

                val journalChanges = apiClient.getJournalChanges(accessToken = accessToken, since = 0)
                assertTrue(journalChanges.isSuccess)
                assertTrue(journalChanges.getOrThrow().changes.any { it.id == "journal-1" })

                val updateJournal =
                    apiClient.updateJournal(
                        accessToken = accessToken,
                        journalId = "journal-1",
                        journal =
                            JournalUpdateRequest(
                                title = "Journal Updated",
                                description = "Description Updated",
                                lastUpdated = now + 2_000,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                assertTrue(updateJournal.isSuccess)

                val associationUpload =
                    apiClient.uploadAssociations(
                        accessToken = accessToken,
                        associations =
                            AssociationUploadRequest(
                                associations =
                                    listOf(
                                        Association(
                                            journalId = "journal-1",
                                            contentId = "content-1",
                                            createdAt = now + 3_000,
                                            deviceId = DeviceId("device-a"),
                                        ),
                                    ),
                            ),
                    )
                assertTrue(associationUpload.isSuccess)
                assertEquals(1, associationUpload.getOrThrow().uploadedCount)

                val associationChanges = apiClient.getAssociationChanges(accessToken = accessToken, since = 0)
                assertTrue(associationChanges.isSuccess)
                assertTrue(
                    associationChanges
                        .getOrThrow()
                        .changes
                        .any { it.journalId == "journal-1" && it.contentId == "content-1" },
                )

                val deleteAssociations =
                    apiClient.deleteAssociations(
                        accessToken = accessToken,
                        associations =
                            AssociationDeleteRequest(
                                associations =
                                    listOf(
                                        AssociationDeleteItem(
                                            journalId = "journal-1",
                                            contentId = "content-1",
                                        ),
                                    ),
                            ),
                    )
                assertTrue(deleteAssociations.isSuccess)

                val payload = byteArrayOf(7, 8, 9, 10)
                val mediaUpload =
                    apiClient.uploadMedia(
                        accessToken = accessToken,
                        media =
                            MediaUploadRequest(
                                contentId = "content-1",
                                fileName = "photo.jpg",
                                mimeType = "image/jpeg",
                                sizeBytes = payload.size.toLong(),
                                data = payload,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                assertTrue(mediaUpload.isSuccess)

                val mediaDownload =
                    apiClient.downloadMedia(
                        accessToken = accessToken,
                        mediaId = mediaUpload.getOrThrow().mediaId,
                    )
                assertTrue(mediaDownload.isSuccess)
                assertContentEquals(payload, mediaDownload.getOrThrow().data)

                assertTrue(apiClient.deleteContent(accessToken, "content-1").isSuccess)
                assertTrue(apiClient.deleteJournal(accessToken, "journal-1").isSuccess)
            }
        }
}
