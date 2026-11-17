@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.Association
import app.logdate.client.sync.cloud.AssociationDeleteItem
import app.logdate.client.sync.cloud.AssociationDeleteRequest
import app.logdate.client.sync.cloud.AssociationUploadRequest
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.client.sync.cloud.DraftUploadRequest
import app.logdate.client.sync.cloud.JournalUploadRequest
import app.logdate.integration.e2e.fixtures.assertCloudError
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import app.logdate.shared.model.sync.JournalMergeRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class JournalMergeClientServerE2ETest {
    @Test
    fun `merge carries remote-only and offline memberships and retry preserves destination and entries`() =
        runTest {
            withServerClientHarness {
                val token =
                    apiClient
                        .createAccountWithSyntheticPasskey("merge_${id().take(8)}")
                        .data.tokens.accessToken
                val source = id()
                val destination = id()
                val remoteOnly = id()
                val submitted = id()
                val late = id()
                apiClient.seedJournal(token, source, "Source")
                apiClient.seedJournal(token, destination, "Destination stays")
                for (note in listOf(remoteOnly, submitted, late)) {
                    apiClient
                        .uploadContent(
                            token,
                            ContentUploadRequest(
                                id = note,
                                type = "TEXT",
                                content = "opaque encrypted note $note",
                                mediaUri = null,
                                createdAt = 1L,
                                lastUpdated = 1L,
                                deviceId = DeviceId("device-a"),
                            ),
                        ).getOrThrow()
                }
                apiClient.link(token, source, remoteOnly)
                val draft =
                    DraftUploadRequest(
                        id = id(),
                        content = "opaque encrypted draft",
                        blockTypes = listOf("TEXT"),
                        journalIds = listOf(source, destination),
                        createdAt = 1L,
                        lastUpdated = 1L,
                        deviceId = DeviceId("device-a"),
                        encryptedBlocksVersion = 1,
                        encryptedBlocks = "LDSE2:opaque-blocks",
                    )
                apiClient.uploadDraft(token, draft).getOrThrow()
                val request = JournalMergeRequest(id(), destination, listOf(submitted))
                // Discard the first acknowledgement, as if the network lost the response.
                apiClient.mergeJournals(token, source, request).getOrThrow()
                val retried = apiClient.mergeJournals(token, source, request).getOrThrow()
                assertEquals(destination, retried.destinationId)
                assertCloudError(apiClient.mergeJournals(token, source, request.copy(contentIds = listOf(late))), "MERGE_CONFLICT", 409)
                apiClient.link(token, source, late)
                apiClient
                    .deleteAssociations(
                        token,
                        AssociationDeleteRequest(listOf(AssociationDeleteItem(journalId = source, contentId = remoteOnly))),
                    ).getOrThrow()
                val associations = apiClient.getAssociationChanges(token, 0L).getOrThrow()
                assertEquals(
                    setOf(remoteOnly, submitted, late),
                    associations.changes
                        .filter { it.journalId == destination }
                        .map { it.contentId }
                        .toSet(),
                )
                assertTrue(associations.changes.none { it.journalId == source })
                val journals = apiClient.getJournalChanges(token, 0L).getOrThrow()
                assertEquals("Destination stays", journals.changes.single { it.id == destination }.title)
                assertEquals(destination, journals.deletions.single { it.id == source }.mergedIntoJournalId)
                val entries = apiClient.getContentChanges(token, 0L).getOrThrow()
                assertEquals(setOf(remoteOnly, submitted, late), entries.changes.map { it.id }.toSet())
                assertTrue(entries.changes.all { it.content == "opaque encrypted note ${it.id}" })
                val restored =
                    apiClient
                        .getDraftChanges(token, 0L)
                        .getOrThrow()
                        .drafts
                        .single { it.id == draft.id }
                assertEquals(listOf(destination), restored.journalIds)
                assertEquals(draft.encryptedBlocks, restored.encryptedBlocks)
                val staleDraft = draft.copy(id = id())
                apiClient.uploadDraft(token, staleDraft).getOrThrow()
                assertEquals(
                    listOf(destination),
                    apiClient
                        .getDraftChanges(token, 0L)
                        .getOrThrow()
                        .drafts
                        .single { it.id == staleDraft.id }
                        .journalIds,
                )
                assertCloudError(apiClient.uploadJournal(token, journal(source, "Stale source")), "JOURNAL_MERGED", 409)
            }
        }

    @Test
    fun `offline source tombstone and chained late writes remain isolated to their account`() =
        runTest {
            withServerClientHarness {
                val owner =
                    apiClient
                        .createAccountWithSyntheticPasskey("owner_${id().take(8)}")
                        .data.tokens.accessToken
                val other =
                    apiClient
                        .createAccountWithSyntheticPasskey("other_${id().take(8)}")
                        .data.tokens.accessToken
                val offlineSource = id()
                val middle = id()
                val survivor = id()
                val offlineNote = id()
                val lateNote = id()
                apiClient.seedJournal(owner, middle, "Middle")
                apiClient.seedJournal(owner, survivor, "Survivor")
                val first = JournalMergeRequest(id(), middle, listOf(offlineNote))
                apiClient.mergeJournals(owner, offlineSource, first).getOrThrow()
                assertEquals(
                    middle,
                    apiClient
                        .getJournalChanges(owner, 0L)
                        .getOrThrow()
                        .deletions
                        .single { it.id == offlineSource }
                        .mergedIntoJournalId,
                )
                apiClient.mergeJournals(owner, middle, JournalMergeRequest(id(), survivor, emptyList())).getOrThrow()
                apiClient.link(owner, offlineSource, lateNote)
                assertEquals(survivor, apiClient.mergeJournals(owner, offlineSource, first).getOrThrow().destinationId)
                val associations = apiClient.getAssociationChanges(owner, 0L).getOrThrow().changes
                assertEquals(setOf(offlineNote, lateNote), associations.filter { it.journalId == survivor }.map { it.contentId }.toSet())
                assertTrue(associations.none { it.journalId == offlineSource || it.journalId == middle })
                apiClient.seedJournal(other, offlineSource, "Independent source")
                assertTrue(
                    apiClient
                        .getJournalChanges(other, 0L)
                        .getOrThrow()
                        .changes
                        .any { it.id == offlineSource },
                )
                assertTrue(
                    apiClient
                        .getAssociationChanges(other, 0L)
                        .getOrThrow()
                        .changes
                        .isEmpty(),
                )
                assertCloudError(
                    apiClient.mergeJournals(other, offlineSource, JournalMergeRequest(id(), survivor, emptyList())),
                    "MERGE_DESTINATION_MISSING",
                    404,
                )
            }
        }

    @Test
    fun `deleted destination leaves source intact and a new operation can select another destination`() =
        runTest {
            withServerClientHarness {
                val token =
                    apiClient
                        .createAccountWithSyntheticPasskey("retarget_${id().take(8)}")
                        .data.tokens.accessToken
                val source = id()
                val deleted = id()
                val replacement = id()
                val note = id()
                apiClient.seedJournal(token, source, "Source")
                apiClient.seedJournal(token, deleted, "Deleted target")
                apiClient.seedJournal(token, replacement, "New target")
                apiClient.link(token, source, note)
                apiClient.deleteJournal(token, deleted).getOrThrow()
                assertCloudError(
                    apiClient.mergeJournals(token, source, JournalMergeRequest(id(), deleted, listOf(note))),
                    "MERGE_DESTINATION_MISSING",
                    404,
                )
                assertTrue(
                    apiClient
                        .getJournalChanges(token, 0L)
                        .getOrThrow()
                        .changes
                        .any { it.id == source },
                )
                assertEquals(
                    replacement,
                    apiClient.mergeJournals(token, source, JournalMergeRequest(id(), replacement, listOf(note))).getOrThrow().destinationId,
                )
                assertEquals(
                    replacement,
                    apiClient
                        .getAssociationChanges(token, 0L)
                        .getOrThrow()
                        .changes
                        .single()
                        .journalId,
                )
            }
        }

    @Test
    fun `concurrent competing merges choose one survivor and keep winning retry stable`() =
        runTest {
            withServerClientHarness {
                val token =
                    apiClient
                        .createAccountWithSyntheticPasskey("compete_${id().take(8)}")
                        .data.tokens.accessToken
                val source = id()
                val destinationB = id()
                val destinationC = id()
                val remoteOnly = id()
                apiClient.seedJournal(token, source, "Source")
                apiClient.seedJournal(token, destinationB, "Keep B")
                apiClient.seedJournal(token, destinationC, "Keep C")
                apiClient.link(token, source, remoteOnly)
                val requests =
                    listOf(
                        JournalMergeRequest(id(), destinationB, emptyList()),
                        JournalMergeRequest(id(), destinationC, emptyList()),
                    )
                val results =
                    requests
                        .map { request ->
                            async { request to apiClient.mergeJournals(token, source, request) }
                        }.awaitAll()
                assertEquals(1, results.count { it.second.isSuccess })
                assertEquals(1, results.count { it.second.isFailure })
                val (winningRequest, winningResult) = results.single { it.second.isSuccess }
                val winningResponse = winningResult.getOrThrow()
                assertEquals(winningRequest.operationId, winningResponse.operationId)
                assertEquals(winningRequest.destinationId, winningResponse.destinationId)
                assertCloudError(results.single { it.second.isFailure }.second, "MERGE_CONFLICT", 409)
                assertEquals(winningResponse, apiClient.mergeJournals(token, source, winningRequest).getOrThrow())
                val journals = apiClient.getJournalChanges(token, 0L).getOrThrow()
                assertTrue(journals.changes.none { it.id == source })
                assertEquals(winningRequest.destinationId, journals.deletions.single { it.id == source }.mergedIntoJournalId)
                assertEquals("Keep B", journals.changes.single { it.id == destinationB }.title)
                assertEquals("Keep C", journals.changes.single { it.id == destinationC }.title)
                assertTrue(journals.changes.all { it.description == "Keep destination metadata" })
                val links = apiClient.getAssociationChanges(token, 0L).getOrThrow().changes
                assertEquals(setOf(remoteOnly), links.filter { it.journalId == winningRequest.destinationId }.map { it.contentId }.toSet())
                assertTrue(links.none { it.journalId == source || it.journalId != winningRequest.destinationId })
            }
        }

    private suspend fun CloudApiClient.seedJournal(
        token: String,
        id: String,
        title: String,
    ) {
        uploadJournal(token, journal(id, title)).getOrThrow()
    }

    private suspend fun CloudApiClient.link(
        token: String,
        journalId: String,
        contentId: String,
    ) {
        uploadAssociations(
            token,
            AssociationUploadRequest(
                listOf(Association(journalId = journalId, contentId = contentId, createdAt = 1L, deviceId = DeviceId("device-a"))),
            ),
        ).getOrThrow()
    }

    private fun journal(
        id: String,
        title: String,
    ) = JournalUploadRequest(
        id = id,
        title = title,
        description = "Keep destination metadata",
        createdAt = 1L,
        lastUpdated = 1L,
        deviceId = DeviceId("device-a"),
    )

    private fun id(): String = Uuid.random().toString()
}
