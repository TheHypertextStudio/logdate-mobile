package app.logdate.server.sync

import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.PRIVATE_BLOB_OWNER
import app.logdate.server.logdate.PRIVATE_BLOB_RETIRED
import app.logdate.server.logdate.PRIVATE_BLOB_STATE
import app.logdate.server.logdate.privateBlobId
import app.logdate.server.logdate.privateBlobPath
import app.logdate.server.logdate.requirePrivateBlobOwner
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import java.nio.channels.Channels

/** Generation preconditions make a permanent marker win safely against an unfinished create-only upload. */
internal class GcsPrivateBlobLifecycle(
    private val storage: Storage,
    private val bucket: String,
    private val kmsKey: String?,
) {
    fun retire(
        blobId: String,
        owner: String,
    ): Boolean {
        requirePrivateBlobOwner(owner)
        val id = BlobId.of(bucket, privateBlobPath(blobId))
        repeat(3) {
            val existing = storage.get(id)
            if (existing != null) {
                if (existing.metadata?.get(PRIVATE_BLOB_OWNER) != owner) return false
                if (retired(existing)) return true
            }
            val marker =
                BlobInfo
                    .newBuilder(id)
                    .setContentType("application/octet-stream")
                    .setMetadata(mapOf(PRIVATE_BLOB_OWNER to owner, PRIVATE_BLOB_STATE to PRIVATE_BLOB_RETIRED))
                    .build()
            val options =
                buildList {
                    if (kmsKey != null) add(Storage.BlobTargetOption.kmsKeyName(kmsKey))
                    add(
                        if (existing ==
                            null
                        ) {
                            Storage.BlobTargetOption.doesNotExist()
                        } else {
                            Storage.BlobTargetOption.generationMatch(existing.generation)
                        },
                    )
                }
            try {
                storage.create(marker, byteArrayOf(), *options.toTypedArray())
                return true
            } catch (error: StorageException) {
                if (error.code != 412) throw error
            }
        }
        return false
    }

    fun open(
        path: String,
        owner: String,
    ): LogDateBlobStream? {
        requirePrivateBlobOwner(owner)
        privateBlobId(path) ?: return null
        val blob = storage.get(BlobId.of(bucket, path)) ?: return null
        if (blob.metadata?.get(PRIVATE_BLOB_OWNER) != owner || blob.metadata?.get(PRIVATE_BLOB_STATE) != "live") return null
        val generation = blob.generation ?: return null
        val channel = storage.reader(BlobId.of(bucket, path, generation), Storage.BlobSourceOption.generationMatch(generation))
        return LogDateBlobStream(blob.size, Channels.newInputStream(channel))
    }

    fun deletePayload(path: String): Boolean {
        val id = BlobId.of(bucket, path)
        val blob = storage.get(id) ?: return false
        if (retired(blob) || blob.metadata?.get(PRIVATE_BLOB_OWNER) == null) return false
        return storage.delete(id, Storage.BlobSourceOption.generationMatch(blob.generation))
    }

    fun retired(path: String): Boolean = storage.get(BlobId.of(bucket, path))?.let(::retired) == true

    fun retired(blob: Blob): Boolean = blob.metadata?.get(PRIVATE_BLOB_STATE) == PRIVATE_BLOB_RETIRED
}
