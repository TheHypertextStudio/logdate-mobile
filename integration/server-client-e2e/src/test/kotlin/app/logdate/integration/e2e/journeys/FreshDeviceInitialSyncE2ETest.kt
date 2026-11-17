package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.AssociationUploadRequest
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.client.sync.cloud.JournalUploadRequest
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simulates a user installing the app on a second device and signing in to an account that
 * already has data. The "second device" uses the same access token (same account) but starts its
 * download cursor at 0, the way a fresh install would. We upload a modest batch on "device A",
 * then verify the "device B" sees every entity.
 *
 * Locks in the invariant behind the launch bar: reinstall on a new device doesn't lose data.
 */
class FreshDeviceInitialSyncE2ETest {
    @Test
    fun `device B sees every journal content and association device A uploaded`() =
        kotlinx.coroutines.test.runTest {
            withServerClientHarness {
                val username = "fresh_device_${Random.nextInt(1000, 9999)}"
                val account = apiClient.createAccountWithSyntheticPasskey(username)
                val accessToken = account.data.tokens.accessToken
                val now = 1_700_000_000_000L

                // --- Device A seeds content, journals, and associations ---
                val entryCount = 30
                val journalIds = listOf("journal-a", "journal-b", "journal-c")
                val contentIds = (1..entryCount).map { "content-$it" }

                journalIds.forEachIndexed { i, journalId ->
                    apiClient
                        .uploadJournal(
                            accessToken = accessToken,
                            journal =
                                JournalUploadRequest(
                                    id = journalId,
                                    title = "Journal $i",
                                    description = "Seed journal $i",
                                    createdAt = now + i,
                                    lastUpdated = now + i,
                                    deviceId = DeviceId("device-a"),
                                ),
                        ).getOrThrow()
                }
                contentIds.forEachIndexed { i, contentId ->
                    apiClient
                        .uploadContent(
                            accessToken = accessToken,
                            content =
                                ContentUploadRequest(
                                    id = contentId,
                                    type = "TEXT",
                                    content = "entry body $i",
                                    mediaUri = null,
                                    createdAt = now + i,
                                    lastUpdated = now + i,
                                    deviceId = DeviceId("device-a"),
                                ),
                        ).getOrThrow()
                }
                contentIds.forEach { contentId ->
                    val journalId = journalIds.first()
                    apiClient
                        .uploadAssociations(
                            accessToken = accessToken,
                            associations =
                                AssociationUploadRequest(
                                    associations =
                                        listOf(
                                            app.logdate.client.sync.cloud.Association(
                                                contentId = contentId,
                                                journalId = journalId,
                                                createdAt = now,
                                                deviceId = DeviceId("device-a"),
                                            ),
                                        ),
                                ),
                        ).getOrThrow()
                }

                // --- Device B: fresh install, page `since=0` to drain everything ---
                val downloadedContent = drainContentChanges(apiClient, accessToken)
                val downloadedJournals = drainJournalChanges(apiClient, accessToken)
                val downloadedAssociations = drainAssociationChanges(apiClient, accessToken)

                assertEquals(
                    contentIds.toSet(),
                    downloadedContent.map { it.id }.toSet(),
                    "device B should see every piece of content device A uploaded",
                )
                assertEquals(
                    journalIds.toSet(),
                    downloadedJournals.map { it.id }.toSet(),
                    "device B should see every journal device A uploaded",
                )
                assertEquals(
                    contentIds.size,
                    downloadedAssociations.size,
                    "device B should see one association per content row",
                )
                assertTrue(
                    downloadedAssociations.all { it.journalId == journalIds.first() },
                    "every association points to the first journal",
                )
            }
        }

    private suspend fun drainContentChanges(
        apiClient: app.logdate.client.sync.cloud.LogDateCloudApiClient,
        accessToken: String,
    ): List<app.logdate.client.sync.cloud.ContentChange> {
        val all = mutableListOf<app.logdate.client.sync.cloud.ContentChange>()
        var cursor = 0L
        while (true) {
            val page = apiClient.getContentChanges(accessToken = accessToken, since = cursor).getOrThrow()
            if (page.changes.isEmpty()) break
            all += page.changes
            val maxSeen = page.changes.maxOf { it.lastUpdated }
            if (maxSeen <= cursor) break
            cursor = maxSeen
        }
        return all.distinctBy { it.id }
    }

    private suspend fun drainJournalChanges(
        apiClient: app.logdate.client.sync.cloud.LogDateCloudApiClient,
        accessToken: String,
    ): List<app.logdate.client.sync.cloud.JournalChange> {
        val all = mutableListOf<app.logdate.client.sync.cloud.JournalChange>()
        var cursor = 0L
        while (true) {
            val page = apiClient.getJournalChanges(accessToken = accessToken, since = cursor).getOrThrow()
            if (page.changes.isEmpty()) break
            all += page.changes
            val maxSeen = page.changes.maxOf { it.lastUpdated }
            if (maxSeen <= cursor) break
            cursor = maxSeen
        }
        return all.distinctBy { it.id }
    }

    private suspend fun drainAssociationChanges(
        apiClient: app.logdate.client.sync.cloud.LogDateCloudApiClient,
        accessToken: String,
    ): List<app.logdate.shared.model.sync.AssociationChange> {
        val all = mutableListOf<app.logdate.shared.model.sync.AssociationChange>()
        var cursor = 0L
        while (true) {
            val page = apiClient.getAssociationChanges(accessToken = accessToken, since = cursor).getOrThrow()
            if (page.changes.isEmpty()) break
            all += page.changes
            val maxSeen = page.changes.maxOf { it.serverVersion }
            if (maxSeen <= cursor) break
            cursor = maxSeen
        }
        return all
    }
}
