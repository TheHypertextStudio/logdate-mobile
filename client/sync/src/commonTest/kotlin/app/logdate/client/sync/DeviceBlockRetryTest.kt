package app.logdate.client.sync

import app.logdate.client.device.crypto.IdentityKeyNotFoundException
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncBackoff
import app.logdate.client.sync.metadata.retryKey
import app.logdate.client.sync.test.FakeSyncMetadataService
import app.logdate.client.sync.test.InMemorySyncDeadLetterStore
import app.logdate.client.sync.test.InMemorySyncRetryScheduleStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DeviceBlockRetryTest {
    @Test
    fun `missing identity stops the pass without charging a row attempt`() =
        runTest {
            val metadata = FakeSyncMetadataService(trackOperationIdentity = true)
            val dead = InMemorySyncDeadLetterStore()
            val retry =
                SyncRetryCoordinator(
                    InMemorySyncRetryScheduleStore(),
                    metadata,
                    dead,
                    SyncBackoff(),
                    recordConflict = { _, _, _, _, _, _, _ -> },
                )
            metadata.enqueuePending("note", EntityType.NOTE, PendingOperation.CREATE)
            val pending = metadata.getPendingUploads(EntityType.NOTE).single()
            assertFailsWith<IdentityKeyNotFoundException> {
                retry.handleRetryFailure(EntityType.NOTE, pending, IdentityKeyNotFoundException("Missing identity"))
            }
            assertEquals(0, metadata.getPendingUploads(EntityType.NOTE).single().retryCount)
            assertEquals(0, dead.list().size)
        }

    @Test
    fun `a permanent record stays parked after its timer expires`() =
        runTest {
            val metadata = FakeSyncMetadataService(trackOperationIdentity = true)
            val dead = InMemorySyncDeadLetterStore()
            val schedule = InMemorySyncRetryScheduleStore()
            val retry = SyncRetryCoordinator(schedule, metadata, dead, SyncBackoff(), recordConflict = { _, _, _, _, _, _, _ -> })
            metadata.enqueuePending("note", EntityType.NOTE, PendingOperation.CREATE)
            val pending = metadata.getPendingUploads(EntityType.NOTE).single()
            retry.handleRetryFailure(EntityType.NOTE, pending, IllegalArgumentException("Bad record"), permanent = true)
            schedule.setNextAttemptAt(EntityType.NOTE, pending.retryKey(), 0L)
            assertFalse(retry.shouldAttempt(EntityType.NOTE, pending))
        }

    @Test
    fun `bundled SQLite full storage is a device block but an individual missing file is not`() {
        assertEquals(
            SyncPausedReason.LOCAL_STORAGE_UNAVAILABLE,
            deviceBlockReason(SQLiteException("Error code: 13, message: database or disk is full")),
        )
        assertEquals(null, deviceBlockReason(IllegalStateException("No such file: one-photo.jpg")))
    }

    private class SQLiteException(
        message: String,
    ) : Exception(message)
}
