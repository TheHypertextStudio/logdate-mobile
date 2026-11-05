@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.logdate

import app.logdate.server.database.toJavaUUID
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PrivateBlobRetirementTest {
    @Test fun aPermanentMarkerFencesALateWriterAndSurvivesGenericCleanup() =
        providers { storage ->
            val id = Uuid.random().toString()
            val owner = "a".repeat(43)
            val request =
                LogDateBlobStreamRequest(
                    Uuid.random().toJavaUUID(),
                    LogDateBlobNamespace.SPACE,
                    id,
                    "application/octet-stream",
                    createOnly = true,
                    privateOwner = owner,
                )
            val writer = storage.openBlobWriter(request)
            writer.output.write(byteArrayOf(1, 2))
            assertTrue(storage.retirePrivateBlob(id, owner))
            writer.output.write(byteArrayOf(3))
            assertFails { writer.commit() }
            writer.closeForCleanup()
            assertTrue(storage.retirePrivateBlob(id, owner))
            assertNull(storage.openBlob(storage.plannedPrivateBlobPath(id)))
            storage.deleteBlobIfExists(storage.plannedPrivateBlobPath(id))
            assertTrue(storage.retirePrivateBlob(id, owner))
            assertFails {
                val later = storage.openBlobWriter(request)
                later.output.write(byteArrayOf(4))
                try {
                    later.commit()
                } finally {
                    later.closeForCleanup()
                }
            }
        }

    @Test fun retirementReplacesOnlyTheExactOwnedObject() =
        providers { storage ->
            val id = Uuid.random().toString()
            val owner = "a".repeat(43)
            val request =
                LogDateBlobStreamRequest(
                    Uuid.random().toJavaUUID(),
                    LogDateBlobNamespace.SPACE,
                    id,
                    "application/octet-stream",
                    createOnly = true,
                    privateOwner = owner,
                )
            val writer = storage.openBlobWriter(request)
            writer.output.write(byteArrayOf(1, 2, 3))
            writer.commit()
            writer.closeForCleanup()
            assertEquals(storage.plannedPrivateBlobPath(id), writer.storagePath)
            assertFalse(storage.retirePrivateBlob(id, "b".repeat(43)))
            assertEquals(listOf<Byte>(1, 2, 3), storage.getBlob(writer.storagePath)?.toList())
            assertTrue(storage.retirePrivateBlob(id, owner))
            assertNull(storage.getBlob(writer.storagePath))
            assertTrue(storage.retirePrivateBlob(id, owner))
        }

    @Test fun filesystemDoesNotReplaceAnUnownedObject() {
        val root = Files.createTempDirectory("logdate-private-unknown-")
        try {
            val storage = FilesystemLogDateBlobStorage(root)
            val id = Uuid.random().toString()
            val file = root.resolve(storage.plannedPrivateBlobPath(id))
            Files.createDirectories(file.parent)
            Files.write(file, byteArrayOf(9, 8, 7))
            assertFalse(storage.retirePrivateBlob(id, "a".repeat(43)))
            assertEquals(listOf<Byte>(9, 8, 7), Files.readAllBytes(file).toList())
        } finally {
            remove(root)
        }
    }

    private fun providers(block: (LogDateBlobStorage) -> Unit) {
        val root = Files.createTempDirectory("logdate-private-retirement-")
        try {
            block(InMemoryLogDateBlobStorage())
            block(FilesystemLogDateBlobStorage(root))
        } finally {
            remove(root)
        }
    }

    private fun remove(root: java.nio.file.Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
