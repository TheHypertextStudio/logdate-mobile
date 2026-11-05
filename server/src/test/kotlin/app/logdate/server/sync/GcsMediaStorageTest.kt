package app.logdate.server.sync

import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GcsMediaStorage], validating the integration with Google Cloud Storage
 * for persisting media assets and backups.
 *
 * This suite verifies path sanitization, KMS-based encryption support, signed URL
 * generation, and the mapping of GCS-specific errors to internal exceptions.
 */
class GcsMediaStorageTest {
    @Test
    fun `private reads require a live matching owner and pin the object generation`() {
        val storage = mockk<Storage>()
        val blob = mockk<Blob>()
        val channel = mockk<com.google.cloud.ReadChannel>(relaxed = true)
        val path = "identity-adoption-private/11111111-1111-4111-8111-111111111111"
        val owner = "A".repeat(43)
        every { storage.get(BlobId.of("bucket-a", path)) } returns blob
        every { blob.metadata } returns mapOf("logdate-private-owner" to owner, "logdate-private-state" to "live")
        every { blob.generation } returns 7L
        every { blob.size } returns 3L
        every { storage.reader(BlobId.of("bucket-a", path, 7L), *anyVararg()) } returns channel
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)
        assertNotNull(sut.openPrivateBlob(path, owner)).use { assertEquals(3L, it.size) }
        assertNull(sut.openPrivateBlob(path, "B".repeat(43)))
        every { blob.metadata } returns mapOf("logdate-private-owner" to owner, "logdate-private-state" to "retired")
        assertNull(sut.openPrivateBlob(path, owner))
        verify(exactly = 1) { storage.reader(BlobId.of("bucket-a", path, 7L), *anyVararg()) }
        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun `cleanup confirms missing objects but propagates provider failure`() {
        val storage = mockk<Storage>()
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)
        every { storage.delete(any<BlobId>()) } returns false
        sut.deleteBlobIfExists("users/owner/media/missing")
        every { storage.delete(any<BlobId>()) } throws
            com.google.cloud.storage
                .StorageException(404, "missing")
        sut.deleteBlobIfExists("users/owner/media/missing")
        every { storage.delete(any<BlobId>()) } throws
            com.google.cloud.storage
                .StorageException(503, "private-path")
        val error = kotlin.test.assertFails { sut.deleteBlobIfExists("users/owner/media/failed") }
        assertEquals("Blob cleanup failed", error.message)
        assertNull(error.cause)
    }

    @Test
    fun `upload media returns storage path and sanitizes filename`() {
        val storage = mockk<Storage>()
        every { storage.create(any<BlobInfo>(), any<ByteArray>(), *anyVararg()) } returns mockk<Blob>()
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)

        val userId = UUID.randomUUID()
        val mediaId = UUID.randomUUID()
        val path = sut.uploadMedia(userId, mediaId, "../nested\\\\file.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        assertContains(path, "users/$userId/media/$mediaId")
        assertContains(path, "_nested__file.jpg")
        verify(exactly = 1) {
            storage.create(
                match { it.blobId == BlobId.of("bucket-a", path) && it.contentType == "image/jpeg" },
                any<ByteArray>(),
                *anyVararg(),
            )
        }
    }

    @Test
    fun `constructor can build storage client from StorageOptions and apply project id`() {
        val builder = mockk<com.google.cloud.storage.StorageOptions.Builder>()
        val options = mockk<com.google.cloud.storage.StorageOptions>()
        val storage = mockk<Storage>()
        val userId = UUID.randomUUID()
        val mediaId = UUID.randomUUID()

        mockkStatic(com.google.cloud.storage.StorageOptions::class)
        try {
            every {
                com.google.cloud.storage.StorageOptions
                    .newBuilder()
            } returns builder
            every { builder.setProjectId(any()) } returns builder
            every { builder.build() } returns options
            every { options.service } returns storage
            every { storage.create(any<BlobInfo>(), any<ByteArray>(), *anyVararg()) } returns mockk<Blob>()

            val sut = GcsMediaStorage(bucketName = "bucket-a", projectId = "project-1")
            val path = sut.uploadMedia(userId, mediaId, "f.jpg", "image/jpeg", byteArrayOf(1))
            assertContains(path, "users/$userId/media/$mediaId/f.jpg")
        } finally {
            unmockkStatic(com.google.cloud.storage.StorageOptions::class)
        }
    }

    @Test
    fun `upload media wraps storage failures`() {
        val storage = mockk<Storage>()
        every { storage.create(any<BlobInfo>(), any<ByteArray>(), *anyVararg()) } throws IllegalStateException("boom")
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)

        val error =
            kotlin
                .runCatching {
                    sut.uploadMedia(UUID.randomUUID(), UUID.randomUUID(), "file.jpg", "image/jpeg", byteArrayOf(1))
                }.exceptionOrNull()
        assertTrue(error is MediaStorageException)
        assertContains(error.message.orEmpty(), "Failed to upload media")
    }

    @Test
    fun `upload backup returns path and wraps failures`() {
        val storage = mockk<Storage>()
        every { storage.create(any<BlobInfo>(), any<ByteArray>(), *anyVararg()) } returns mockk<Blob>()
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)
        val userId = UUID.randomUUID()
        val backupId = UUID.randomUUID()

        val path = sut.uploadBackup(userId, backupId, byteArrayOf(9, 8, 7))
        assertEquals("users/$userId/backups/$backupId.enc", path)

        every { storage.create(any<BlobInfo>(), any<ByteArray>(), *anyVararg()) } throws IllegalArgumentException("nope")
        val error =
            kotlin
                .runCatching {
                    sut.uploadBackup(userId, backupId, byteArrayOf(1))
                }.exceptionOrNull()
        assertTrue(error is MediaStorageException)
        assertContains(error.message.orEmpty(), "Failed to upload backup")
    }

    @Test
    fun `kms option branches execute for media and backup uploads`() {
        val storage = mockk<Storage>()
        every { storage.create(any<BlobInfo>(), any<ByteArray>(), *anyVararg()) } returns mockk<Blob>()
        val sut = GcsMediaStorage(bucketName = "bucket-a", kmsKeyName = "kms-key", storage = storage)
        val userId = UUID.randomUUID()

        val mediaPath = sut.uploadMedia(userId, UUID.randomUUID(), "file.jpg", "image/jpeg", byteArrayOf(1, 2))
        val backupPath = sut.uploadBackup(userId, UUID.randomUUID(), byteArrayOf(3, 4))
        assertContains(mediaPath, "/media/")
        assertContains(backupPath, "/backups/")
    }

    @Test
    fun `signed url generation returns url and wraps failure`() {
        val storage = mockk<Storage>()
        every {
            storage.signUrl(
                any<BlobInfo>(),
                any<Long>(),
                any<TimeUnit>(),
                any<Storage.SignUrlOption>(),
            )
        } returns URI("https://example.test/signed").toURL()
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)

        assertEquals("https://example.test/signed", sut.getSignedDownloadUrl("users/u/media/m/f.jpg", 2))

        every {
            storage.signUrl(
                any<BlobInfo>(),
                any<Long>(),
                any<TimeUnit>(),
                any<Storage.SignUrlOption>(),
            )
        } throws IllegalStateException("sign failed")
        val error =
            kotlin
                .runCatching {
                    sut.getSignedDownloadUrl("users/u/media/m/f.jpg")
                }.exceptionOrNull()
        assertTrue(error is MediaStorageException)
        assertContains(error.message.orEmpty(), "Failed to generate download URL")
    }

    @Test
    fun `download delete and exists handle success and failure`() {
        val storage = mockk<Storage>()
        val path = "users/u/media/m/f.jpg"
        val blobId = BlobId.of("bucket-a", path)
        every { storage.readAllBytes(blobId) } returns byteArrayOf(1, 2)
        every { storage.delete(blobId) } returns true
        every { storage.get(blobId) } returns mockk()
        val sut = GcsMediaStorage(bucketName = "bucket-a", storage = storage)

        assertTrue(sut.downloadMedia(path)!!.contentEquals(byteArrayOf(1, 2)))
        assertTrue(sut.deleteMedia(path))
        assertTrue(sut.exists(path))

        every { storage.delete(blobId) } returns false
        assertFalse(sut.deleteMedia(path))

        every { storage.readAllBytes(blobId) } throws IllegalStateException("read failed")
        every { storage.delete(blobId) } throws IllegalStateException("delete failed")
        every { storage.get(blobId) } throws IllegalStateException("exists failed")
        assertNull(sut.downloadMedia(path))
        assertFalse(sut.deleteMedia(path))
        assertFalse(sut.exists(path))
    }

    @Test
    fun `companion from environment returns null when bucket is absent`() {
        val missing = GcsMediaStorage.fromEnvironment { null }
        assertNull(missing)

        val created =
            GcsMediaStorage.fromEnvironment {
                when (it) {
                    "GCS_BUCKET_NAME" -> "bucket-a"
                    "GCS_PROJECT_ID" -> "project-a"
                    "GCS_MEDIA_KMS_KEY" -> "kms/a"
                    else -> null
                }
            }
        assertNotNull(created)
    }

    @Test
    fun `media storage exception supports optional cause`() {
        val noCause = MediaStorageException("x")
        assertEquals("x", noCause.message)
        assertNull(noCause.cause)
    }
}
