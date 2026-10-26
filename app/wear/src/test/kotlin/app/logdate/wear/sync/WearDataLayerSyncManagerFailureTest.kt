package app.logdate.wear.sync

import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WearDataLayerSyncManagerFailureTest : WearDataLayerSyncManagerTestFixture() {
    @Test
    fun `a removed note is announced to the phone at its delete path`() =
        runTest {
            val sent = mutableListOf<Pair<String, Map<String, String>>>()
            coEvery { dataLayerClient.putDataItem(any(), any()) } answers {
                sent += firstArg<String>() to secondArg<Map<String, String>>()
                true
            }

            syncManager.notifyRemoved(noteId)

            assertEquals(NoteDataMapper.noteDeletePath(noteId), sent.single().first)
            assertEquals(noteId.toString(), sent.single().second[NoteDataMapper.KEY_UID])
        }

    @Test
    fun `a removed note is announced even when the phone is not connected`() =
        runTest {
            coEvery { dataLayerClient.isPhoneConnected(any()) } returns false

            syncManager.notifyRemoved(noteId)

            coVerify(exactly = 1) { dataLayerClient.putDataItem(NoteDataMapper.noteDeletePath(noteId), any()) }
        }

    @Test
    fun `a removal that cannot be put does not throw`() =
        runTest {
            coEvery { dataLayerClient.putDataItem(any(), any()) } returns false

            syncManager.notifyRemoved(noteId)
        }

    // =======================================================================
    // uploadPendingChanges -- delete operations
    // =======================================================================
    @Test
    fun `upload sends delete signal for deleted notes`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.DELETE),
                )

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            coVerify { dataLayerClient.putDataItem(NoteDataMapper.noteDeletePath(noteId), any()) }
        }

    @Test
    fun `delete upload does not look up note from repository`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.DELETE),
                )

            syncManager.uploadPendingChanges()

            coVerify(exactly = 0) { notesRepository.getNoteById(any()) }
        }

    @Test
    fun `delete upload does not send file`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.DELETE),
                )

            syncManager.uploadPendingChanges()

            coVerify(exactly = 0) { dataLayerClient.sendFile(any(), any()) }
        }

    @Test
    fun `delete upload marks as synced on success`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.DELETE),
                )

            syncManager.uploadPendingChanges()

            coVerify {
                syncMetadataService.markAsSynced(noteId.toString(), EntityType.NOTE, any(), any())
            }
        }

    // =======================================================================
    // uploadPendingChanges -- error handling
    // =======================================================================
    @Test
    fun `upload returns error when phone not connected`() =
        runTest {
            coEvery { dataLayerClient.isPhoneConnected(any()) } returns false
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )

            val result = syncManager.uploadPendingChanges()

            assertFalse(result.success)
            assertTrue(result.errors.isNotEmpty())
            assertEquals(SyncErrorType.NETWORK_ERROR, result.errors.first().type)
        }

    @Test
    fun `upload does not attempt any puts when phone not connected`() =
        runTest {
            coEvery { dataLayerClient.isPhoneConnected(any()) } returns false

            syncManager.uploadPendingChanges()

            coVerify(exactly = 0) { dataLayerClient.putDataItem(any(), any()) }
            coVerify(exactly = 0) { dataLayerClient.sendFile(any(), any()) }
        }

    @Test
    fun `upload increments retry count on individual note failure`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote
            coEvery { dataLayerClient.putDataItem(any(), any()) } returns false

            syncManager.uploadPendingChanges()

            coVerify {
                syncMetadataService.incrementRetryCount(noteId.toString(), EntityType.NOTE)
            }
        }

    @Test
    fun `upload reports unavailable note without pretending it was delivered`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns null

            val result = syncManager.uploadPendingChanges()

            assertFalse(result.success)
            assertEquals(0, result.uploadedItems)
            coVerify(exactly = 0) { dataLayerClient.putDataItem(any(), any()) }
        }

    @Test
    fun `upload retains outbox for missing notes`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns null

            syncManager.uploadPendingChanges()

            coVerify(exactly = 0) {
                syncMetadataService.markAsSynced(noteId.toString(), EntityType.NOTE, any(), any())
            }
        }

    @Test
    fun `upload handles invalid uuid in pending upload gracefully`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload("not-a-valid-uuid", PendingOperation.CREATE),
                )

            val result = syncManager.uploadPendingChanges()

            assertFalse(result.success)
            assertEquals(0, result.uploadedItems)
            coVerify(exactly = 0) { syncMetadataService.markAsSynced(any(), any(), any(), any()) }
        }
}
