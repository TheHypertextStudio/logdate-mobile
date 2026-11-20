package app.logdate.server.logdate

import io.github.aakira.napier.Napier
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes

/**
 * On-disk [LogDateBlobStorage] for deployments that don't have GCS — the self-host path.
 *
 * Files live under `<rootPath>/<namespace>/<ownerId>/<blobId>`. The layout mirrors the GCS object
 * naming the first-party cloud uses so that signed-URL or path-shape assumptions hold everywhere.
 *
 * This is deliberately simple: no retention or deduplication. A small operator
 * running this against a local disk gets correct behavior for media and backups without needing
 * to stand up Google Cloud Storage. Larger deployments should still use GCS.
 *
 * Signed download URLs aren't meaningful here — there's no external object store to sign against —
 * so `getSignedDownloadUrl` returns the same scheme the media handler uses when no signer is
 * configured: a relative path the client can fetch through the authenticated `/media/.../binary`
 * endpoint.
 */
class FilesystemLogDateBlobStorage(
    private val rootPath: Path,
) : LogDateBlobStorage {
    override fun putBlobFile(request: LogDateBlobFileWriteRequest): String {
        val relative = relativePath(request.namespace, request.ownerId.toString(), request.blobId)
        val absolute = rootPath.resolve(relative)
        absolute.parent.createDirectories()
        val pending = Files.createTempFile(absolute.parent, ".logdate-blob-", ".pending")
        try {
            Files.newInputStream(request.path).use { source ->
                Files.newOutputStream(pending).use { destination ->
                    copyBackupFile(source, destination, request.checkActive)
                }
            }
            try {
                Files.move(pending, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
        return relative
    }

    override fun openBlob(storagePath: String): LogDateBlobStream? {
        val file = rootPath.resolve(storagePath)
        val input =
            try {
                Files.newInputStream(file)
            } catch (_: NoSuchFileException) {
                return null
            }
        return try {
            LogDateBlobStream(Files.size(file), input)
        } catch (error: Exception) {
            input.close()
            throw error
        }
    }

    override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
        val relative = relativePath(request.namespace, request.ownerId.toString(), request.blobId)
        val absolute = rootPath.resolve(relative)
        absolute.parent.createDirectories()
        val pending = Files.createTempFile(absolute.parent, ".logdate-blob-", ".pending")
        val output = ChecksummingOutputStream(Files.newOutputStream(pending).buffered())
        return object : LogDateBlobWriter {
            override val storagePath = relative
            override val output = output

            override fun commit() {
                output.close()
                if (Files.size(pending) != output.size) {
                    Files.deleteIfExists(pending)
                    throw LogDateBlobIntegrityException("Stored size differs from bytes written")
                }
                try {
                    Files.move(pending, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(pending, absolute, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            override fun abort() {
                runCatching { output.close() }
                Files.deleteIfExists(pending)
                Files.deleteIfExists(absolute)
            }
        }
    }

    override fun getBlobFile(
        storagePath: String,
        destination: Path,
        checkActive: () -> Unit,
    ): Boolean =
        try {
            Files.newInputStream(rootPath.resolve(storagePath)).use { source ->
                Files.newOutputStream(destination).use { output -> copyBackupFile(source, output, checkActive) }
            }
            true
        } catch (_: NoSuchFileException) {
            false
        } catch (error: Exception) {
            Files.deleteIfExists(destination)
            throw error
        }

    init {
        rootPath.createDirectories()
    }

    override fun putBlob(request: LogDateBlobWriteRequest): String {
        val relative = relativePath(request.namespace, request.ownerId.toString(), request.blobId)
        val absolute = rootPath.resolve(relative)
        absolute.parent.createDirectories()
        Files.write(absolute, request.bytes)
        return relative
    }

    override fun getBlob(storagePath: String): ByteArray? =
        try {
            rootPath.resolve(storagePath).readBytes()
        } catch (_: NoSuchFileException) {
            null
        } catch (e: Exception) {
            Napier.w("Filesystem blob read failed")
            null
        }

    override fun deleteBlob(storagePath: String): Boolean =
        try {
            rootPath.resolve(storagePath).deleteIfExists()
        } catch (e: Exception) {
            Napier.w("Filesystem blob delete failed")
            false
        }

    override fun deleteBlobIfExists(storagePath: String) {
        rootPath.resolve(storagePath).deleteIfExists()
    }

    override fun listOwnerBlobPaths(ownerId: java.util.UUID): List<String> =
        LogDateBlobNamespace.entries.flatMap { namespace ->
            val directory = rootPath.resolve("${namespace.name.lowercase()}/$ownerId")
            try {
                Files.walk(directory).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.map { rootPath.relativize(it).toString() }.toList()
                }
            } catch (_: NoSuchFileException) {
                emptyList()
            }
        }

    override fun storagePathFor(
        ownerId: java.util.UUID,
        namespace: LogDateBlobNamespace,
        blobId: String,
    ): String = relativePath(namespace, ownerId.toString(), blobId)

    override fun getSignedDownloadUrl(
        storagePath: String,
        expirationHours: Long,
    ): String = storagePath

    companion object {
        const val ENV_VAR: String = "LOGDATE_BLOB_STORAGE_DIR"

        /**
         * Builds a storage rooted at the path in [ENV_VAR], or returns `null` if the env var is
         * unset so the caller can decide whether to substitute another implementation or fail.
         */
        fun fromEnvironment(readEnv: (String) -> String? = System::getenv): FilesystemLogDateBlobStorage? {
            val raw = readEnv(ENV_VAR)?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val rebuiltPath =
                try {
                    Path.of(raw)
                } catch (e: Exception) {
                    Napier.w("Invalid $ENV_VAR path")
                    return null
                }
            return FilesystemLogDateBlobStorage(rebuiltPath)
        }
    }
}

/** Canonical layout: `<namespace-lower>/<ownerId>/<blobId>`, matching the GCS object naming. */
private fun relativePath(
    namespace: LogDateBlobNamespace,
    ownerId: String,
    blobId: String,
): String = "${namespace.name.lowercase()}/$ownerId/$blobId"
