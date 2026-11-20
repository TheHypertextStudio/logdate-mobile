package app.logdate.server.logdate

import java.io.Closeable
import java.io.InputStream
import java.nio.file.Path
import java.util.UUID

/**
 * LogDate-owned blob namespace for binary objects.
 *
 * `ATPROTO` is reserved for the future blob semantics work that still needs to be layered onto
 * the current first-party sync media and backup objects.
 */
enum class LogDateBlobNamespace {
    MEDIA,
    BACKUP,
    ATPROTO,

    /** Sealed blobs in a personal space; each upload gets its own object, even for repeated content. */
    SPACE,
}

/**
 * Generic blob write request.
 */
data class LogDateBlobWriteRequest(
    val ownerId: UUID,
    val namespace: LogDateBlobNamespace,
    val blobId: String,
    val fileName: String? = null,
    val contentType: String,
    val bytes: ByteArray,
)

/** An object's bytes as a stream, and how many there are. Closing it ends the read. */
class LogDateBlobStream(
    val size: Long,
    val input: InputStream,
) : Closeable {
    override fun close() = input.close()
}

/**
 * LogDate-owned binary object storage boundary for media, encrypted backups, and future ATProto
 * blob semantics.
 *
 * The current production implementation is still GCS-backed, but the interface is now generic so
 * route code is no longer coupled to media-specific upload methods.
 */
interface LogDateBlobStorage {
    fun putBlob(request: LogDateBlobWriteRequest): String

    fun getBlob(storagePath: String): ByteArray?

    fun putBlobFile(request: LogDateBlobFileWriteRequest): String

    /** Starts an object whose bytes are written as they arrive; see [LogDateBlobWriter]. */
    fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter

    fun getBlobFile(
        storagePath: String,
        destination: Path,
        checkActive: () -> Unit = {},
    ): Boolean

    /**
     * Opens the object at [storagePath] to read as a stream, or returns null when there's none.
     * Nothing is copied to memory or disk first; the caller closes the stream.
     */
    fun openBlob(storagePath: String): LogDateBlobStream?

    fun deleteBlob(storagePath: String): Boolean

    /** Delete or confirm absence; throw on a provider failure. Never infer absence from a read. */
    fun deleteBlobIfExists(storagePath: String) {
        check(deleteBlob(storagePath)) { "Blob deletion was not confirmed" }
    }

    /** Exact owner namespace only; enumeration failures must propagate to the cleanup queue. */
    fun listOwnerBlobPaths(ownerId: UUID): List<String> = error("Owner enumeration is unavailable")

    /**
     * The path an object written as [blobId] in [ownerId]'s [namespace] is stored at, so a later
     * request can find an object an earlier one wrote without the client naming a path.
     */
    fun storagePathFor(
        ownerId: UUID,
        namespace: LogDateBlobNamespace,
        blobId: String,
    ): String = error("Storage paths cannot be derived for this storage")

    fun getSignedDownloadUrl(
        storagePath: String,
        expirationHours: Long = 1,
    ): String

    fun uploadMedia(
        userId: UUID,
        mediaId: UUID,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): String =
        putBlob(
            LogDateBlobWriteRequest(
                ownerId = userId,
                namespace = LogDateBlobNamespace.MEDIA,
                blobId = mediaId.toString(),
                fileName = fileName,
                contentType = mimeType,
                bytes = data,
            ),
        )

    fun uploadBackup(
        userId: UUID,
        backupId: UUID,
        data: ByteArray,
    ): String =
        putBlob(
            LogDateBlobWriteRequest(
                ownerId = userId,
                namespace = LogDateBlobNamespace.BACKUP,
                blobId = backupId.toString(),
                fileName = null,
                contentType = "application/octet-stream",
                bytes = data,
            ),
        )

    fun downloadMedia(storagePath: String): ByteArray? = getBlob(storagePath)

    fun deleteMedia(storagePath: String): Boolean = deleteBlob(storagePath)
}

data class LogDateBlobFileWriteRequest(
    val ownerId: UUID,
    val namespace: LogDateBlobNamespace,
    val blobId: String,
    val fileName: String? = null,
    val contentType: String,
    val path: Path,
    val checkActive: () -> Unit = {},
)
