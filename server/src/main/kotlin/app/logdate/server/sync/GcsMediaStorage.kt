package app.logdate.server.sync

import app.logdate.server.logdate.ChecksummingOutputStream
import app.logdate.server.logdate.LogDateBlobIntegrityException
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.server.logdate.PRIVATE_BLOB_OWNER
import app.logdate.server.logdate.PRIVATE_BLOB_PREFIX
import app.logdate.server.logdate.PRIVATE_BLOB_STATE
import app.logdate.server.logdate.copyBackupFile
import app.logdate.server.logdate.requirePrivateBlobOwner
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.github.aakira.napier.Napier
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Google Cloud Storage implementation for media file storage.
 * Handles upload, download, and signed URL generation for media files.
 */
class GcsMediaStorage(
    private val bucketName: String,
    projectId: String? = null,
    private val kmsKeyName: String? = null,
    private val storage: Storage =
        StorageOptions
            .newBuilder()
            .apply { projectId?.let { setProjectId(it) } }
            .build()
            .service,
) : LogDateBlobStorage {
    private val privateBlobs = GcsPrivateBlobLifecycle(storage, bucketName, kmsKeyName)

    override fun retirePrivateBlob(
        blobId: String,
        owner: String,
    ): Boolean = privateBlobs.retire(blobId, owner)

    override fun putBlobFile(request: app.logdate.server.logdate.LogDateBlobFileWriteRequest): String {
        val storagePath =
            plannedPath(
                LogDateBlobWriteRequest(
                    request.ownerId,
                    request.namespace,
                    request.blobId,
                    request.fileName,
                    request.contentType,
                    ByteArray(0),
                ),
            )
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).setContentType(request.contentType).build()
        val options = if (kmsKeyName != null) arrayOf(Storage.BlobWriteOption.kmsKeyName(kmsKeyName)) else emptyArray()
        try {
            request.checkActive()
            storage.writer(blobInfo, *options).use { channel ->
                Channels.newOutputStream(channel).use { destination ->
                    Files.newInputStream(request.path).buffered().use { source ->
                        copyBackupFile(source, destination, request.checkActive)
                    }
                }
            }
            return storagePath
        } catch (cancellation: CancellationException) {
            runCatching { storage.delete(BlobId.of(bucketName, storagePath)) }
            throw cancellation
        } catch (error: Exception) {
            runCatching { storage.delete(BlobId.of(bucketName, storagePath)) }
            throw MediaStorageException("Failed to upload backup")
        }
    }

    override fun openPrivateBlob(
        storagePath: String,
        owner: String,
    ): LogDateBlobStream? = privateBlobs.open(storagePath, owner)

    override fun openBlob(storagePath: String): LogDateBlobStream? {
        val blob = storage.get(BlobId.of(bucketName, storagePath)) ?: return null
        if (storagePath.startsWith(PRIVATE_BLOB_PREFIX) && privateBlobs.retired(blob)) return null
        return LogDateBlobStream(blob.size, Channels.newInputStream(blob.reader()))
    }

    override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
        val owner = request.privateOwner
        require(owner == null || request.createOnly)
        owner?.let(::requirePrivateBlobOwner)
        val storagePath =
            if (owner == null) {
                plannedPath(
                    LogDateBlobWriteRequest(request.ownerId, request.namespace, request.blobId, null, request.contentType, ByteArray(0)),
                )
            } else {
                plannedPrivateBlobPath(request.blobId)
            }
        val blobId = BlobId.of(bucketName, storagePath)
        val blobInfo =
            BlobInfo
                .newBuilder(blobId)
                .setContentType(request.contentType)
                .apply {
                    if (owner != null) setMetadata(mapOf(PRIVATE_BLOB_OWNER to owner, PRIVATE_BLOB_STATE to "live"))
                }.build()
        val options =
            buildList {
                if (kmsKeyName != null) add(Storage.BlobWriteOption.kmsKeyName(kmsKeyName))
                if (request.createOnly) add(Storage.BlobWriteOption.doesNotExist())
            }.toTypedArray()
        val channel = storage.writer(blobInfo, *options)
        channel.setChunkSize(STREAMED_WRITE_CHUNK_BYTES)
        val output = ChecksummingOutputStream(Channels.newOutputStream(channel))
        return object : LogDateBlobWriter {
            override val storagePath = storagePath
            override val output = output
            private var closeConfirmed = false
            private var closeUncertain = false

            override fun closeForCleanup() {
                if (closeUncertain) throw MediaStorageException("Blob writer completion is uncertain")
                if (closeConfirmed) return
                try {
                    output.close()
                    closeConfirmed = true
                } catch (_: Exception) {
                    closeUncertain = true
                    throw MediaStorageException("Blob writer completion is uncertain")
                }
            }

            override fun commit() {
                closeForCleanup()
                val stored = storage.get(blobId)
                if (stored == null || stored.size != output.size || stored.crc32cToHexString != output.crc32cHex) {
                    throw LogDateBlobIntegrityException("Stored object differs from the bytes written")
                }
            }

            override fun abort() {
                closeForCleanup()
                try {
                    if (owner == null) storage.delete(blobId) else privateBlobs.deletePayload(storagePath)
                } catch (error: com.google.cloud.storage.StorageException) {
                    if (error.code != 404) throw MediaStorageException("Blob cleanup failed")
                }
            }
        }
    }

    override fun getBlobFile(
        storagePath: String,
        destination: Path,
        checkActive: () -> Unit,
    ): Boolean {
        val blobId = BlobId.of(bucketName, storagePath)
        checkActive()
        val blob = storage.get(blobId) ?: return false
        if (storagePath.startsWith(PRIVATE_BLOB_PREFIX) && privateBlobs.retired(blob)) return false
        try {
            storage.reader(blobId).use { channel ->
                Channels.newInputStream(channel).use { source ->
                    Files.newOutputStream(destination).buffered().use { output ->
                        copyBackupFile(source, output, checkActive)
                    }
                }
            }
        } catch (error: Exception) {
            Files.deleteIfExists(destination)
            throw error
        }
        return true
    }

    /**
     * Upload a LogDate blob into the configured GCS bucket.
     */
    override fun putBlob(request: LogDateBlobWriteRequest): String {
        val storagePath = plannedPath(request)
        val label = request.namespace.errorLabel()
        val blobId = BlobId.of(bucketName, storagePath)
        val blobInfo =
            BlobInfo
                .newBuilder(blobId)
                .setContentType(request.contentType)
                .build()

        try {
            val options =
                if (kmsKeyName != null) {
                    arrayOf(Storage.BlobTargetOption.kmsKeyName(kmsKeyName))
                } else {
                    emptyArray()
                }
            storage.create(blobInfo, request.bytes, *options)
            Napier.d("Uploaded $label to GCS")
            return storagePath
        } catch (e: Exception) {
            Napier.e("Failed to upload $label to GCS")
            throw MediaStorageException("Failed to upload $label", e)
        }
    }

    /**
     * Generate a signed URL for media download.
     * @param storagePath The GCS storage path
     * @param expirationHours How long the URL should be valid (default: 1 hour)
     * @return Signed URL for downloading the media file
     */
    override fun getSignedDownloadUrl(
        storagePath: String,
        expirationHours: Long,
    ): String {
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).build()
        return try {
            val url: URL =
                storage.signUrl(
                    blobInfo,
                    expirationHours,
                    TimeUnit.HOURS,
                    Storage.SignUrlOption.withV4Signature(),
                )
            url.toString()
        } catch (e: Exception) {
            throw MediaStorageException("Failed to generate download URL", e)
        }
    }

    /**
     * Download media file bytes from GCS.
     * @param storagePath The GCS storage path
     * @return File contents as ByteArray, or null if not found
     */
    override fun getBlob(storagePath: String): ByteArray? {
        if (storagePath.startsWith(PRIVATE_BLOB_PREFIX) && privateBlobs.retired(storagePath)) return null
        val blobId = BlobId.of(bucketName, storagePath)
        return try {
            storage.readAllBytes(blobId)
        } catch (e: Exception) {
            Napier.e("Failed to download blob from GCS")
            null
        }
    }

    /**
     * Delete media from GCS.
     * @param storagePath The GCS storage path
     * @return true if deleted successfully, false otherwise
     */
    override fun deleteBlob(storagePath: String): Boolean {
        if (storagePath.startsWith(PRIVATE_BLOB_PREFIX)) return privateBlobs.deletePayload(storagePath)
        val blobId = BlobId.of(bucketName, storagePath)
        return try {
            val deleted = storage.delete(blobId)
            if (deleted) {
                Napier.d("Deleted blob from GCS")
            } else {
                Napier.w("Blob not found in GCS")
            }
            deleted
        } catch (e: Exception) {
            Napier.e("Failed to delete blob from GCS")
            false
        }
    }

    override fun deleteBlobIfExists(storagePath: String) {
        if (storagePath.startsWith(PRIVATE_BLOB_PREFIX)) {
            if (privateBlobs.retired(storagePath)) return
            if (privateBlobs.deletePayload(storagePath) || storage.get(BlobId.of(bucketName, storagePath)) == null) return
            error("Private payload cleanup was not confirmed")
        }
        try {
            storage.delete(BlobId.of(bucketName, storagePath))
        } catch (error: com.google.cloud.storage.StorageException) {
            if (error.code != 404) throw MediaStorageException("Blob cleanup failed")
        }
    }

    override fun listOwnerBlobPaths(ownerId: java.util.UUID): List<String> =
        storage
            .list(bucketName, Storage.BlobListOption.prefix("users/$ownerId/"))
            .iterateAll()
            .map { it.name }
            .toList()

    /**
     * Check if media exists in GCS.
     * @param storagePath The GCS storage path
     * @return true if the file exists
     */
    fun exists(storagePath: String): Boolean {
        val blobId = BlobId.of(bucketName, storagePath)
        return try {
            storage.get(blobId) != null
        } catch (e: Exception) {
            false
        }
    }

    override fun plannedPath(request: LogDateBlobWriteRequest): String =
        when (request.namespace) {
            LogDateBlobNamespace.MEDIA -> {
                val fileName = requireNotNull(request.fileName) { "Media blobs require a file name" }
                val sanitizedFileName =
                    fileName
                        .replace("..", "")
                        .replace("/", "_")
                        .replace("\\", "_")
                "users/${request.ownerId}/media/${request.blobId}/$sanitizedFileName"
            }

            LogDateBlobNamespace.BACKUP -> "users/${request.ownerId}/backups/${request.blobId}.enc"

            LogDateBlobNamespace.ATPROTO -> "users/${request.ownerId}/atproto/blobs/${request.blobId}"

            LogDateBlobNamespace.SPACE -> "users/${request.ownerId}/spaces/${request.blobId}"
        }

    companion object {
        /**
         * Create GcsMediaStorage from environment variables.
         * @return GcsMediaStorage instance, or null if not configured
         */
        fun fromEnvironment(env: (String) -> String? = System::getenv): GcsMediaStorage? {
            val bucketName = env("GCS_BUCKET_NAME") ?: return null
            val projectId = env("GCS_PROJECT_ID")
            val kmsKeyName = env("GCS_MEDIA_KMS_KEY")
            return GcsMediaStorage(bucketName, projectId, kmsKeyName)
        }
    }
}

/**
 * How many bytes a streamed write gathers in memory before sending them to Cloud Storage. The client
 * library's default is several times larger; this bounds each upload in progress to about 2 MiB of
 * heap. It must be a multiple of 256 KiB.
 */
internal const val STREAMED_WRITE_CHUNK_BYTES = 2 * 1024 * 1024

private fun LogDateBlobNamespace.errorLabel(): String =
    when (this) {
        LogDateBlobNamespace.MEDIA -> "media"
        LogDateBlobNamespace.BACKUP -> "backup"
        LogDateBlobNamespace.ATPROTO -> "blob"
        LogDateBlobNamespace.SPACE -> "space-blob"
    }

/**
 * Exception thrown when media storage operations fail.
 */
class MediaStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
