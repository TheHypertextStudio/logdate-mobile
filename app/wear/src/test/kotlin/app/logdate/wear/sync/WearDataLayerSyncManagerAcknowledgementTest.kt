package app.logdate.wear.sync

import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.UploadScope
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WearDataLayerSyncManagerAcknowledgementTest : WearDataLayerSyncManagerTestFixture() {
    @Test
    fun `upload leaves an audio note pending until the phone acknowledges it`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            assertEquals(0, result.uploadedItems)
            coVerify(exactly = 0) { syncMetadataService.markAsSynced(any(), any(), any(), any()) }
        }

    @Test
    fun `upload holds off resending an unacknowledged audio note for the acknowledgement window`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            fixedClockSyncManager().uploadPendingChanges()

            coVerify {
                retryScheduleStore.setNextAttemptAt(
                    EntityType.NOTE,
                    noteId.toString(),
                    fixedTime.toEpochMilliseconds() + WearDataLayerSyncManager.ACK_TIMEOUT_MS,
                )
            }
        }

    @Test
    fun `upload skips an audio note still inside its acknowledgement window`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE, retryCount = 1),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { retryScheduleStore.nextAttemptAt(EntityType.NOTE, noteId.toString()) } returns
                fixedTime.toEpochMilliseconds() + 1

            fixedClockSyncManager().uploadPendingChanges()

            coVerify(exactly = 0) { dataLayerClient.putDataItem(any(), any()) }
            coVerify(exactly = 0) { dataLayerClient.sendFile(any(), any()) }
        }

    @Test
    fun `upload resends an unacknowledged audio note once the window has passed`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(noteId.toString(), PendingOperation.CREATE, retryCount = 1),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { retryScheduleStore.nextAttemptAt(EntityType.NOTE, noteId.toString()) } returns
                fixedTime.toEpochMilliseconds()

            fixedClockSyncManager().uploadPendingChanges()

            coVerify(exactly = 1) { dataLayerClient.sendFile(any(), audioNote.mediaRef) }
        }

    @Test
    fun `awaiting an audio acknowledgement never consumes the failure budget`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(
                    PendingUpload(
                        noteId.toString(),
                        PendingOperation.CREATE,
                        retryCount = WearDataLayerSyncManager.MAX_RETRY_ATTEMPTS - 1,
                    ),
                )
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            val result = syncManager.uploadPendingChanges()

            assertTrue(result.success)
            assertTrue(result.errors.isEmpty())
            coVerify(exactly = 0) { syncMetadataService.incrementRetryCount(any(), any()) }
            coVerify(exactly = 0) { deadLetterStore.add(any()) }
        }

    @Test
    fun `overlapping syncs run one after the other so the audio is not sent twice at once`() =
        runTest {
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(PendingUpload(noteId.toString(), PendingOperation.CREATE))
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { dataLayerClient.sendFile(any(), any()) } coAnswers {
                gate.await()
                true
            }

            val first = async { syncManager.fullSync() }
            runCurrent()
            val second = async { syncManager.fullSync() }
            runCurrent()

            coVerify(exactly = 1) { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId), any()) }
            gate.complete(Unit)
            first.await()
            second.await()
            coVerify(exactly = 2) { dataLayerClient.putDataItem(NoteDataMapper.notePath(noteId), any()) }
        }

    @Test
    fun `an acknowledgement without a recorded transmission cannot settle a note`() =
        runTest {
            syncManager.onNoteAcknowledged(noteId)

            coVerify(exactly = 0) { syncMetadataService.markAsSynced(any(), any(), any(), any()) }
            coVerify(exactly = 0) { syncMetadataService.settleIfCurrent(any(), any(), any(), any()) }
        }

    @Test
    fun `a legacy acknowledgement cannot settle an edit made after transmission`() =
        runTest {
            val sent = PendingUpload(noteId.toString(), PendingOperation.CREATE, operationId = "sent")
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns listOf(sent)
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            syncManager.uploadPendingChanges()
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns
                listOf(sent.copy(operationId = "newer"))
            coEvery { notesRepository.getNoteById(noteId) } returns
                audioNote.copy(lastUpdated = fixedTime + kotlin.time.Duration.parse("1s"))

            syncManager.onNoteAcknowledged(noteId)

            coVerify(exactly = 0) { syncMetadataService.markAsSynced(any(), any(), any(), any()) }
            coVerify(exactly = 0) { syncMetadataService.settleIfCurrent(any(), any(), any(), any()) }
        }

    @Test
    fun `an unchanged transmitted audio note accepts a legacy acknowledgement`() =
        runTest {
            val pending = PendingUpload(noteId.toString(), PendingOperation.CREATE)
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns listOf(pending)
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            coEvery { syncMetadataService.settleIfCurrent(EntityType.NOTE, pending, any(), any()) } returns true
            syncManager.uploadPendingChanges()

            syncManager.onNoteAcknowledged(noteId)

            coVerify { syncMetadataService.settleIfCurrent(EntityType.NOTE, pending, any(), any()) }
            coVerify { retryScheduleStore.clear(EntityType.NOTE, noteId.toString()) }
        }

    @Test
    fun `a payload-free acknowledgement cannot settle a scoped transmission`() =
        runTest {
            val pending =
                PendingUpload(
                    noteId.toString(),
                    PendingOperation.CREATE,
                    operationId = "scoped",
                    scope = UploadScope("owner", "https://server.test"),
                )
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns listOf(pending)
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            syncManager.uploadPendingChanges()

            syncManager.onNoteAcknowledged(noteId)

            coVerify(exactly = 0) { syncMetadataService.settleIfCurrent(any(), any(), any(), any()) }
        }

    @Test
    fun `a late versioned acknowledgement clears only its own retained failure`() =
        runTest {
            val sent =
                PendingUpload(
                    noteId.toString(),
                    PendingOperation.CREATE,
                    operationId = "sent",
                    scope = UploadScope("owner", "https://server.test"),
                )
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns listOf(sent)
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            val metadata = mutableListOf<Map<String, String>>()
            coEvery { dataLayerClient.putDataItem(any(), capture(metadata)) } returns true
            syncManager.uploadPendingChanges()
            val oldFailure =
                SyncDeadLetterRecord(
                    "sent",
                    EntityType.NOTE.name,
                    noteId.toString(),
                    "CREATE",
                    5,
                    "NETWORK_UNAVAILABLE",
                    0L,
                    scope = sent.scope,
                    operationId = sent.operationId,
                )
            val newFailure = oldFailure.copy(id = "newer", operationId = "newer")
            coEvery { deadLetterStore.list() } returns listOf(oldFailure, newFailure)
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns listOf(sent.copy(operationId = "newer"))

            syncManager.onNoteAcknowledged(noteId, metadata.single().getValue(WearAudioRequestPaths.KEY_TRANSFER_ID))

            coVerify { deadLetterStore.remove("sent") }
            coVerify(exactly = 0) { deadLetterStore.remove("newer") }
            coVerify(exactly = 0) { syncMetadataService.settleIfCurrent(any(), any(), any(), any()) }
        }

    @Test
    fun `a duplicate versioned acknowledgement does not settle twice`() =
        runTest {
            val pending = PendingUpload(noteId.toString(), PendingOperation.CREATE, operationId = "sent")
            coEvery { syncMetadataService.getPendingUploads(EntityType.NOTE) } returns listOf(pending)
            coEvery { notesRepository.getNoteById(noteId) } returns audioNote
            val metadata = mutableListOf<Map<String, String>>()
            coEvery { dataLayerClient.putDataItem(any(), capture(metadata)) } returns true
            syncManager.uploadPendingChanges()
            val token = metadata.single().getValue(WearAudioRequestPaths.KEY_TRANSFER_ID)

            syncManager.onNoteAcknowledged(noteId, token)
            syncManager.onNoteAcknowledged(noteId, token)

            coVerify(exactly = 1) { syncMetadataService.settleIfCurrent(EntityType.NOTE, pending, any(), any()) }
        }
}
