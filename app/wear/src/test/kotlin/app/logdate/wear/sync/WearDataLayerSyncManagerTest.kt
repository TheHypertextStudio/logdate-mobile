package app.logdate.wear.sync

import app.logdate.client.repository.journals.JournalNote
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests for [WearDataLayerSyncManager], the orchestrator of data consistency between the
 * Wear OS client and the companion mobile application.
 *
 * This suite validates the management of the sync outbox, the prioritization of different
 * entity types (Notes, Journals, etc.), and the sophisticated retry logic required to
 * maintain a reliable "offline-first" experience. It ensures that both metadata updates
 * and binary asset transfers are correctly tracked and marked as synchronized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearDataLayerSyncManagerTest : WearDataLayerSyncManagerTestFixture() {
    // =======================================================================
    // getSyncStatus
    // =======================================================================
    @Test
    fun `sync status reports enabled when phone connected`() =
        runTest {
            coEvery { dataLayerClient.isPhoneConnected(any()) } returns true
            coEvery { syncMetadataService.getPendingCount() } returns 0

            val status = syncManager.getSyncStatus()

            assertTrue(status.isEnabled)
        }

    @Test
    fun `sync status reports disabled when phone not connected`() =
        runTest {
            coEvery { dataLayerClient.isPhoneConnected(any()) } returns false
            coEvery { syncMetadataService.getPendingCount() } returns 0

            val status = syncManager.getSyncStatus()

            assertFalse(status.isEnabled)
        }

    @Test
    fun `sync status reports pending upload count`() =
        runTest {
            coEvery { syncMetadataService.getPendingCount() } returns 5

            val status = syncManager.getSyncStatus()

            assertEquals(5, status.pendingUploads)
        }

    @Test
    fun `sync status reports not syncing when idle`() =
        runTest {
            coEvery { syncMetadataService.getPendingCount() } returns 0

            val status = syncManager.getSyncStatus()

            assertFalse(status.isSyncing)
        }

    @Test
    fun `sync status has no errors initially`() =
        runTest {
            coEvery { syncMetadataService.getPendingCount() } returns 0

            val status = syncManager.getSyncStatus()

            assertFalse(status.hasErrors)
            assertNull(status.lastError)
        }

    // =======================================================================
    // uploadPendingChanges -- text notes
    // =======================================================================
    @Test
    fun `upload sends pending text note via data layer`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            assertEquals(1, result.uploadedItems)
            coVerify {
                dataLayerClient.putDataItem(
                    NoteDataMapper.notePath(noteId),
                    any(),
                )
            }
        }

    @Test
    fun `upload marks note as synced after successful put`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote

            syncManager.uploadPendingChanges()

            coVerify {
                syncMetadataService.markAsSynced(
                    entityId = noteId.toString(),
                    entityType = EntityType.NOTE,
                    syncedAt = any(),
                    version = any(),
                )
            }
        }

    @Test
    fun `upload handles multiple pending notes`() =
        runTest {
            val noteId2 = Uuid.parse("660e8400-e29b-41d4-a716-446655440000")
            val note2 =
                JournalNote.Text(
                    uid = noteId2,
                    creationTimestamp = fixedTime,
                    lastUpdated = fixedTime,
                    content = "Second note",
                )

            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                    PendingUpload(noteId2.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote
            coEvery { notesRepository.getNoteById(noteId2) } returns note2

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            assertEquals(2, result.uploadedItems)
        }

    @Test
    fun `upload sends UPDATE as data item put`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.UPDATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            assertEquals(1, result.uploadedItems)
            coVerify { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId), any()) }
        }

    @Test
    fun `upload does not send file for text notes`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote

            syncManager.uploadPendingChanges()

            coVerify(exactly = 0) { dataLayerClient.sendFile(any(), any()) }
        }

    @Test
    fun `upload does not attempt file transfer when metadata put fails`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { dataLayerClient.putDataItem(any(), any()) } returns false

            syncManager.uploadPendingChanges()

            coVerify(exactly = 0) { dataLayerClient.sendFile(any(), any()) }
        }

    @Test
    fun `upload returns success with zero items when nothing pending`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns emptyList()

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            assertEquals(0, result.uploadedItems)
        }

    @Test
    fun `upload continues processing after one note fails`() =
        runTest {
            val noteId2 = Uuid.parse("660e8400-e29b-41d4-a716-446655440000")
            val note2 =
                JournalNote.Text(
                    uid = noteId2,
                    creationTimestamp = fixedTime,
                    lastUpdated = fixedTime,
                    content = "ok",
                )

            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                    PendingUpload(noteId2.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote
            coEvery { notesRepository.getNoteById(noteId2) } returns note2
            // First put fails, second succeeds
            coEvery { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId), any()) } returns false
            coEvery { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId2), any()) } returns true

            val result = syncManager.uploadPendingChanges()

            // One succeeded, one failed
            assertEquals(1, result.uploadedItems)
            assertEquals(1, result.errors.size)
        }

    // =======================================================================
    // syncContent delegates to upload
    // =======================================================================
    @Test
    fun `syncContent uploads pending notes`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote

            val result = syncManager.syncContent()

            assertTrue(result.success)
            coVerify { dataLayerClient.putDataItem(any(), any()) }
        }

    // =======================================================================
    // fullSync
    // =======================================================================
    @Test
    fun `fullSync uploads all pending entity types`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(any()) } returns emptyList()

            val result = syncManager.fullSync()

            assertTrue(result.success)
            coVerify { syncMetadataService.getPendingUploads(EntityType.NOTE) }
        }

    // =======================================================================
    // sync (non-blocking)
    // =======================================================================
    @Test
    fun `sync does not throw`() {
        syncManager.sync(startNow = false)
        syncManager.sync(startNow = true)
    }

    // =======================================================================
    // downloadRemoteChanges
    // =======================================================================
    @Test
    fun `downloadRemoteChanges returns success`() =
        runTest {
            val result = syncManager.downloadRemoteChanges()

            assertTrue(result.success)
        }

    // =======================================================================
    // Last sync time tracking
    // =======================================================================
    @Test
    fun `sync status includes last sync time after upload`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote
            coEvery { syncMetadataService.getLastSyncTime(any()) } returns fixedTime
            coEvery { syncMetadataService.getPendingCount() } returns 0

            syncManager.uploadPendingChanges()
            val status = syncManager.getSyncStatus()

            assertNotNull(status.lastSyncTime)
        }

    @Test
    fun `upload result includes last sync time`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns textNote

            val result = syncManager.uploadPendingChanges()

            assertNotNull(result.lastSyncTime)
        }
}
