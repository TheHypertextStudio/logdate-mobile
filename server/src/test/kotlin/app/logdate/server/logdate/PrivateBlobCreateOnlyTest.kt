@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.logdate

import app.logdate.server.database.toJavaUUID
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PrivateBlobCreateOnlyTest {
    @Test fun privateReadPinsTheLiveProviderOwnerAndRefusesForeignOrRetiredBytes() {
        val root = Files.createTempDirectory("logdate-private-read-owner-")
        try {
            for (storage in listOf(InMemoryLogDateBlobStorage(), FilesystemLogDateBlobStorage(root))) {
                val blobId = Uuid.random().toString()
                val owner = "A".repeat(43)
                val writer =
                    storage.openBlobWriter(
                        LogDateBlobStreamRequest(
                            Uuid.random().toJavaUUID(),
                            LogDateBlobNamespace.SPACE,
                            blobId,
                            "application/octet-stream",
                            createOnly = true,
                            privateOwner = owner,
                        ),
                    )
                writer.output.write(byteArrayOf(1, 2, 3))
                writer.commit()
                writer.closeForCleanup()
                assertNull(storage.openPrivateBlob(writer.storagePath, "B".repeat(43)))
                assertNotNull(storage.openPrivateBlob(writer.storagePath, owner)).use {
                    assertEquals(listOf<Byte>(1, 2, 3), it.input.readBytes().toList())
                }
                assertTrue(storage.retirePrivateBlob(blobId, owner))
                assertNull(storage.openPrivateBlob(writer.storagePath, owner))
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun filesystemPublishRequiresPayloadPersistenceBeforeCreatingTheLiveLink() {
        val root = Files.createTempDirectory("logdate-private-force-failure-")
        try {
            val blobId = Uuid.random().toString()
            val owner = "A".repeat(43)
            var forceCalled = false
            val lifecycle =
                FilesystemPrivateBlobLifecycle(root, forcePayload = { pending ->
                    forceCalled = true
                    assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(pending).toList())
                    assertFalse(Files.exists(root.resolve(privateBlobPath(blobId))))
                    throw IOException("synthetic payload persistence failure")
                })
            val pending = lifecycle.pending(blobId, owner)
            Files.write(pending, byteArrayOf(1, 2, 3))
            assertFailsWith<IOException> { lifecycle.publish(blobId, owner, pending) }
            assertTrue(forceCalled)
            assertFalse(Files.exists(root.resolve(privateBlobPath(blobId))))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun memoryAndFilesystemNeverOverwriteAnUnknownObjectForPrivateIngress() {
        val root = Files.createTempDirectory("logdate-private-create-only-")
        try {
            for (storage in listOf(InMemoryLogDateBlobStorage(), FilesystemLogDateBlobStorage(root))) {
                val owner = Uuid.random().toJavaUUID()
                val request =
                    LogDateBlobStreamRequest(
                        owner,
                        LogDateBlobNamespace.SPACE,
                        "synthetic-owned-path",
                        "application/octet-stream",
                        createOnly = true,
                    )
                val first = storage.openBlobWriter(request)
                first.output.write(byteArrayOf(1, 2, 3))
                first.commit()
                first.closeForCleanup()
                val second = storage.openBlobWriter(request)
                second.output.write(byteArrayOf(4, 5, 6))
                assertFailsWith<LogDateBlobCollisionException> { second.commit() }
                second.closeForCleanup()
                assertEquals(listOf<Byte>(1, 2, 3), storage.getBlob(first.storagePath)?.toList())
                val ordinary = storage.openBlobWriter(request.copy(createOnly = false))
                ordinary.output.write(byteArrayOf(7, 8))
                ordinary.commit()
                ordinary.closeForCleanup()
                assertEquals(listOf<Byte>(7, 8), storage.getBlob(first.storagePath)?.toList())
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
