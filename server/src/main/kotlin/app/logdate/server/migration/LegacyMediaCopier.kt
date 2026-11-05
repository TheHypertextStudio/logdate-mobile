package app.logdate.server.migration

import app.logdate.server.crypto.PayloadPrefixes
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.logdate.deleteLegacyObject
import app.logdate.server.logging.SpaceMigrationNotices
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceStore
import app.logdate.shared.model.sync.RegisteredLegacyMedia
import app.logdate.shared.model.sync.SpaceMigrationErrorCodes
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.repo.Cid
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val COPY_CHUNK_BYTES = 64 * 1024
private const val SPACE_BLOB_MIME_TYPE = "application/octet-stream"

/** CIDv1, the `raw` codec, then a 32-byte SHA-256 multihash: the prefix of every space blob CID. */
private val RAW_SHA256_CID_PREFIX = byteArrayOf(0x01, 0x55, 0x12, 0x20)

/** Storage holds different bytes from the legacy file it copied. */
internal class LegacyMediaCopyMismatchException : IllegalStateException("The copied file doesn't match the legacy file")

/**
 * Registers a legacy media file the apps already encrypted (LDCE1 or LDCE2) as a blob in the
 * account's personal space, without the device uploading it again.
 *
 * The file is copied inside object storage rather than shared, so deleting the legacy copy after the
 * switch can never take a space blob with it. The copy is hashed as it is written, committed with
 * storage's own length and checksum check, and then read back and hashed again; only a copy whose
 * bytes match the legacy file exactly becomes the blob, named by the CID of those bytes. A file the
 * server encrypted itself (LDSM1), or one stored without encryption, is refused: the device downloads
 * it, encrypts it and uploads it instead.
 */
@OptIn(ExperimentalUuidApi::class)
internal class LegacyMediaCopier(
    private val media: LogDateMediaBlobRepository,
    private val storage: LogDateBlobStorage,
    private val spaces: SpaceStore,
    private val uploads: SpaceObjectUploads = SpaceObjectUploads.Direct,
) {
    suspend fun copy(
        accountId: UUID,
        space: String,
        mediaId: String,
        migration: SpaceMigration? = null,
    ): SpaceMigrationResult<RegisteredLegacyMedia> {
        val record = withContext(Dispatchers.IO) { media.getMedia(accountId, mediaId) } ?: return missing()
        val path = record.storagePath
        val source: InputStream =
            if (path == null) {
                ByteArrayInputStream(record.data)
            } else {
                withContext(Dispatchers.IO) { storage.openBlob(path) }?.input ?: return missing()
            }
        val copied =
            source.use { input ->
                val head = withContext(Dispatchers.IO) { input.readNBytes(PayloadPrefixes.CLIENT_MEDIA.size) }
                if (!head.isClientEncrypted()) {
                    return refused(
                        SpaceMigrationErrorCodes.MEDIA_NOT_CLIENT_ENCRYPTED,
                        "The file wasn't encrypted by an app; upload it again",
                    )
                }
                writeCopy(accountId, space, head, input, migration)
            }
        val blob = SpaceBlob(cid = copied.cid, mimeType = SPACE_BLOB_MIME_TYPE, size = copied.size, storagePath = copied.path)
        val stored =
            try {
                uploads.register(copied.admission, storage) {
                    val admission = copied.admission
                    if (admission == null) {
                        spaces.putBlob(accountId, space, blob, identityEpoch = migration?.identityEpoch ?: 0)
                    } else {
                        spaces.putAdmittedBlob(accountId, space, blob, admission.id, identityEpoch = admission.origin.identityEpoch)
                    }
                }
            } catch (error: Throwable) {
                if (copied.admission == null) cleanupCopy(accountId, space, copied.cid, copied.path)
                throw error
            }
        if (stored.storagePath != copied.path && copied.admission == null) cleanupCopy(accountId, space, copied.cid, copied.path)
        return SpaceMigrationResult.Done(RegisteredLegacyMedia(mediaId, stored.cid, stored.size))
    }

    private class Copied(
        val cid: String,
        val size: Long,
        val path: String,
        val admission: ObjectUpload?,
    )

    /** Writes [head] and the rest of [input] into a new space object, hashing on the way, then checks what storage holds. */
    private suspend fun writeCopy(
        accountId: UUID,
        space: String,
        head: ByteArray,
        input: InputStream,
        migration: SpaceMigration?,
    ): Copied =
        withContext(Dispatchers.IO) {
            val request = LogDateBlobStreamRequest(accountId, LogDateBlobNamespace.SPACE, Uuid.random().toString(), SPACE_BLOB_MIME_TYPE)
            val admission = uploads.beginMigration(accountId, migration, space, storage, request)
            val writer = storage.openBlobWriter(request)
            check(admission == null || writer.storagePath == admission.path) { "Provider wrote an unexpected object path" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var commitAttempted = false
            try {
                writer.output.write(head)
                digest.update(head)
                size += head.size
                val buffer = ByteArray(COPY_CHUNK_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    writer.output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    size += count
                }
                commitAttempted = true
                writer.commit()
            } catch (error: Throwable) {
                if (commitAttempted) {
                    // A failed acknowledgement may still name a committed object. Keep WRITING.
                    runCatching { writer.closeForCleanup() }.exceptionOrNull()?.let(error::addSuppressed)
                } else {
                    runCatching { uploads.discard(admission, writer, storage) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
            val expected = digest.digest()
            val cid = Cid.fromBytes(RAW_SHA256_CID_PREFIX + expected).value
            if (!storedMatches(writer.storagePath, expected, size)) {
                if (admission == null) {
                    cleanupCopy(accountId, space, cid, writer.storagePath)
                } else {
                    uploads.discard(admission, writer, storage)
                }
                Napier.e(SpaceMigrationNotices.COPY_MISMATCH)
                throw LegacyMediaCopyMismatchException()
            }
            Copied(cid, size, writer.storagePath, admission)
        }

    /** A failed acknowledgement is not proof that metadata failed to commit. */
    private suspend fun cleanupCopy(
        accountId: UUID,
        space: String,
        cid: String,
        path: String,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        try {
            if (spaces.getBlob(space, cid)?.storagePath == path) return@withContext
            // PostgreSQL rechecks every registered reference and retires the exact path before I/O.
            if (!media.cleanupUnreferencedObject(accountId, path) { storage.deleteLegacyObject(it) }) {
                Napier.w("Copied object retained because cleanup was not confirmed")
            }
        } catch (_: Exception) {
            // Preserve uncertain bytes and the original copy result, including cancellation.
            Napier.w("Copied object retained because cleanup was not confirmed")
        }
    }

    /** Whether the object at [path] holds exactly [size] bytes hashing to [expected]. */
    private fun storedMatches(
        path: String,
        expected: ByteArray,
        size: Long,
    ): Boolean {
        val stored = storage.openBlob(path) ?: return false
        return stored.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_CHUNK_BYTES)
            var read = 0L
            while (true) {
                val count = stream.input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
            }
            read == size && digest.digest().contentEquals(expected)
        }
    }

    private fun ByteArray.isClientEncrypted(): Boolean =
        contentEquals(PayloadPrefixes.CLIENT_MEDIA) || contentEquals(PayloadPrefixes.CLIENT_MEDIA_CHUNKED)

    private fun missing() = refused(MEDIA_NOT_FOUND, "The legacy file isn't stored for this account")

    companion object {
        const val MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND"
    }
}
