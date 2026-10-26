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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class WearDataLayerSyncManagerAudioTest : WearDataLayerSyncManagerTestFixture() {
    // =======================================================================
    // uploadPendingChanges -- audio notes (the critical watch→phone path)
    // =======================================================================
    @Test
    fun `upload sends audio note metadata via data item`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            syncManager.uploadPendingChanges()

            coVerify { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId), any()) }
        }

    @Test
    fun `upload sends audio file via channel`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            syncManager.uploadPendingChanges()

            coVerify { dataLayerClient.sendFile(any(), audioNote.mediaRef) }
        }

    @Test
    fun `upload fails for audio note when file transfer fails`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { dataLayerClient.sendFile(any(), any()) } returns false

            val result = syncManager.uploadPendingChanges()

            assertFalse(result.success)
            assertEquals(0, result.uploadedItems)
            coVerify { syncMetadataService.incrementRetryCount(noteId.toString(), EntityType.NOTE) }
            coVerify(exactly = 0) { syncMetadataService.markAsSynced(any(), any(), any(), any()) }
        }

    @Test
    fun `upload stamps each audio send so the phone sees a changed data item`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            val sentData = mutableListOf<Map<String, String>>()
            coEvery { dataLayerClient.putDataItem(any(), capture(sentData)) } returns true

            fixedClockSyncManager().uploadPendingChanges()

            assertEquals(
                fixedTime.toEpochMilliseconds().toString(),
                sentData.single()[WearDataLayerSyncManager.KEY_SENT_AT],
            )
        }

    @Test
    fun `terminal audio transfer failure remains visibly pending`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(
                        noteId.toString(),
                        PendingOperation.CREATE,
                        retryCount =
                            WearDataLayerSyncManager.MAX_RETRY_ATTEMPTS - 1,
                    ),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { dataLayerClient.sendFile(any(), any()) } returns false

            val result = syncManager.uploadPendingChanges()

            assertFalse(result.success)
            coVerify { deadLetterStore.add(any()) }
            coVerify(exactly = 0) { syncMetadataService.markAsSynced(any(), any(), any(), any()) }
        }

    @Test
    fun `upload fails for audio note when metadata put fails`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { dataLayerClient.putDataItem(any(), any()) } returns false

            val result = syncManager.uploadPendingChanges()

            assertFalse(result.success)
            assertEquals(0, result.uploadedItems)
        }

    @Test
    fun `upload sends audio file at correct channel path`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            syncManager.uploadPendingChanges()

            val expectedPath = "${NoteDataMapper.notePath(noteId)}/audio"
            coVerify { dataLayerClient.sendFile(expectedPath, audioNote.mediaRef) }
        }

    @Test
    fun `upload sends audio note with location metadata`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNoteWithLocation

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            coVerify { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId), any()) }
            coVerify { dataLayerClient.sendFile(any(), audioNoteWithLocation.mediaRef) }
        }

    @Test
    fun `upload handles mix of text and audio notes`() =
        runTest {
            val textId = Uuid.parse("110e8400-e29b-41d4-a716-446655440000")
            val audioId = Uuid.parse("220e8400-e29b-41d4-a716-446655440000")
            val textNote =
                JournalNote.Text(
                    uid = textId,
                    creationTimestamp = fixedTime,
                    lastUpdated = fixedTime,
                    content = "text",
                )
            val audioNote =
                JournalNote.Audio(
                    uid = audioId,
                    creationTimestamp = fixedTime,
                    lastUpdated = fixedTime,
                    mediaRef = "/audio.aac",
                    durationMs = 3000,
                )

            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(textId.toString(), PendingOperation.CREATE),
                    PendingUpload(audioId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(textId) } returns textNote
            coEvery { notesRepository.getNoteById(audioId) } returns audioNote

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            // The text note is delivered; the audio note waits for the phone's acknowledgement
            assertEquals(1, result.uploadedItems)
            // File sent only for audio, not text
            coVerify(exactly = 1) { dataLayerClient.sendFile(any(), any()) }
            coVerify(exactly = 2) { dataLayerClient.putDataItem(any(), any()) }
        }

    // =======================================================================
    // Data integrity: serialized data map contains correct note data
    // =======================================================================
    @Test
    fun `uploaded data map can be deserialized back to original note`() =
        runTest {
            val capturedMaps = mutableListOf<Map<String, String>>()
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { dataLayerClient.putDataItem(any(), capture(capturedMaps)) } returns true

            syncManager.uploadPendingChanges()

            assertTrue(capturedMaps.isNotEmpty())
            val restoredNote = noteDataMapper.fromDataMap(capturedMaps.first())
            assertEquals(audioNote, restoredNote)
        }
}
