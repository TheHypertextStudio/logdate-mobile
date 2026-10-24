package app.logdate.server.logdate

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral blob storage for tests and non-database server runs. Backup files remain on disk so
 * the fallback does not force a large archive into heap memory.
 */
class InMemoryLogDateBlobStorage : LogDateBlobStorage {
    private val blobs = ConcurrentHashMap<String, StoredBlob>()
    private val fileBlobs = ConcurrentHashMap<String, Path>()

    override fun putBlobFile(request: LogDateBlobFileWriteRequest): String {
        val storagePath =
            storagePathFor(
                LogDateBlobWriteRequest(
                    request.ownerId,
                    request.namespace,
                    request.blobId,
                    request.fileName,
                    request.contentType,
                    ByteArray(0),
                ),
            )
        val privateCopy = Files.createTempFile("logdate-memory-blob-", ".bin")
        try {
            Files.newInputStream(request.path).use { source ->
                Files.newOutputStream(privateCopy).use { destination ->
                    copyBackupFile(source, destination, request.checkActive)
                }
            }
            fileBlobs.put(storagePath, privateCopy)?.let { Files.deleteIfExists(it) }
            blobs.remove(storagePath)
            return storagePath
        } catch (error: Exception) {
            Files.deleteIfExists(privateCopy)
            throw error
        }
    }

    override fun openBlob(storagePath: String): LogDateBlobStream? {
        fileBlobs[storagePath]?.let { file ->
            return try {
                LogDateBlobStream(Files.size(file), Files.newInputStream(file))
            } catch (_: java.nio.file.NoSuchFileException) {
                null
            }
        }
        val bytes = blobs[storagePath]?.bytes ?: return null
        return LogDateBlobStream(bytes.size.toLong(), bytes.inputStream())
    }

    override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
        val storagePath =
            storagePathFor(
                LogDateBlobWriteRequest(request.ownerId, request.namespace, request.blobId, null, request.contentType, ByteArray(0)),
            )
        val buffer = java.io.ByteArrayOutputStream()
        val output = ChecksummingOutputStream(buffer)
        return object : LogDateBlobWriter {
            override val storagePath = storagePath
            override val output = output

            override fun commit() {
                output.close()
                val bytes = buffer.toByteArray()
                if (bytes.size.toLong() != output.size) throw LogDateBlobIntegrityException("Stored size differs from bytes written")
                fileBlobs.remove(storagePath)?.let { Files.deleteIfExists(it) }
                blobs[storagePath] = StoredBlob(bytes = bytes, contentType = request.contentType)
            }

            override fun abort() {
                blobs.remove(storagePath)
            }
        }
    }

    override fun getBlobFile(
        storagePath: String,
        destination: Path,
        checkActive: () -> Unit,
    ): Boolean {
        val file = fileBlobs[storagePath]
        if (file != null) {
            try {
                Files.newInputStream(file).use { source ->
                    Files.newOutputStream(destination).use { output -> copyBackupFile(source, output, checkActive) }
                }
            } catch (error: Exception) {
                Files.deleteIfExists(destination)
                throw error
            }
            return true
        }
        val bytes = blobs[storagePath]?.bytes ?: return false
        checkActive()
        Files.write(destination, bytes)
        return true
    }

    override fun putBlob(request: LogDateBlobWriteRequest): String {
        val storagePath = storagePathFor(request)
        fileBlobs.remove(storagePath)?.let { Files.deleteIfExists(it) }
        blobs[storagePath] =
            StoredBlob(
                bytes = request.bytes,
                contentType = request.contentType,
            )
        return storagePath
    }

    override fun getBlob(storagePath: String): ByteArray? =
        blobs[storagePath]?.bytes ?: fileBlobs[storagePath]?.let { Files.readAllBytes(it) }

    override fun deleteBlob(storagePath: String): Boolean {
        val file = fileBlobs.remove(storagePath)
        file?.let { Files.deleteIfExists(it) }
        return blobs.remove(storagePath) != null || file != null
    }

    override fun deleteBlobIfExists(storagePath: String) {
        deleteBlob(storagePath)
    }

    override fun listOwnerBlobPaths(ownerId: java.util.UUID): List<String> =
        (blobs.keys + fileBlobs.keys).filter { it.startsWith("users/$ownerId/") }

    override fun getSignedDownloadUrl(
        storagePath: String,
        expirationHours: Long,
    ): String = "https://logdate.test/download/$storagePath?expiresInHours=$expirationHours"

    private fun storagePathFor(request: LogDateBlobWriteRequest): String =
        when (request.namespace) {
            LogDateBlobNamespace.MEDIA -> {
                val fileName = requireNotNull(request.fileName) { "Media blobs require a file name" }
                "users/${request.ownerId}/media/${request.blobId}/$fileName"
            }

            LogDateBlobNamespace.BACKUP -> {
                "users/${request.ownerId}/backups/${request.blobId}.enc"
            }

            LogDateBlobNamespace.ATPROTO -> {
                "users/${request.ownerId}/atproto/blobs/${request.blobId}"
            }

            LogDateBlobNamespace.SPACE -> {
                "users/${request.ownerId}/spaces/${request.blobId}"
            }
        }

    private data class StoredBlob(
        val bytes: ByteArray,
        val contentType: String,
    )
}
