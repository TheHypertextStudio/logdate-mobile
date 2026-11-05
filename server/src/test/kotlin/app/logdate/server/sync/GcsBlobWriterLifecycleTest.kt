package app.logdate.server.sync

import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStreamRequest
import com.google.cloud.WriteChannel
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class GcsBlobWriterLifecycleTest {
    @Test fun abortClosesTheWriterBeforeDeletingTheCandidate() {
        val fixture = Fixture()
        val events = mutableListOf<String>()
        every { fixture.channel.close() } answers {
            events.add("close")
            Unit
        }
        every { fixture.storage.delete(any<BlobId>()) } answers {
            events.add("delete")
            true
        }
        fixture.writer.abort()
        assertEquals(listOf("close", "delete"), events)
    }

    @Test fun aFailedCloseIsNotAConfirmedAbort() {
        val fixture = Fixture()
        every { fixture.channel.close() } throws IOException("Synthetic private provider details")
        val error = assertFails { fixture.writer.abort() }
        assertEquals("Blob writer completion is uncertain", error.message)
        assertNull(error.cause)
        verify(exactly = 0) { fixture.storage.delete(any<BlobId>()) }
    }

    @Test fun aFailedCommitCloseCannotBeReclassifiedByAnIdempotentSecondClose() {
        val fixture = Fixture()
        var closes = 0
        every { fixture.channel.close() } answers {
            closes++
            if (closes == 1) throw IOException("Synthetic close acknowledgement loss")
        }
        assertFails { fixture.writer.commit() }
        assertFails { fixture.writer.closeForCleanup() }
        assertEquals(1, closes)
    }

    @Test fun closingForCleanupLeavesDeletionToTheReferenceCheckedCoordinator() {
        val fixture = Fixture()
        fixture.writer.closeForCleanup()
        verify(exactly = 1) { fixture.channel.close() }
        verify(exactly = 0) { fixture.storage.delete(any<BlobId>()) }
    }

    @Test fun integrityFailureDoesNotDeleteAnObjectWithoutACleanupClaim() {
        val fixture = Fixture()
        val blob = mockk<Blob>()
        every { blob.size } returns 1L
        every { blob.crc32cToHexString } returns "ffffffff"
        every { fixture.storage.get(any<BlobId>()) } returns blob
        assertFails { fixture.writer.commit() }
        verify(exactly = 0) { fixture.storage.delete(any<BlobId>()) }
    }

    private class Fixture {
        val channel = mockk<WriteChannel>(relaxed = true)
        val storage = mockk<Storage>(relaxed = true)

        init {
            every { storage.writer(any<BlobInfo>(), *anyVararg()) } returns channel
        }

        val writer =
            GcsMediaStorage("synthetic-bucket", storage = storage).openBlobWriter(
                LogDateBlobStreamRequest(
                    UUID.fromString("00000000-0000-4000-8000-000000000001"),
                    LogDateBlobNamespace.SPACE,
                    "synthetic-upload",
                    "application/octet-stream",
                ),
            )
    }
}
