package app.logdate.client.sync.space.migration

import app.logdate.client.media.MediaManager
import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.cloud.FileMediaDataSource
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.space.SpaceMediaReference
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException

private const val HASH_CHUNK = 64L * 1024

/**
 * Downloads every media file the move put into the personal space and checks it: the download
 * itself checks the bytes against the file's content identifier and decrypts them, and the decrypted
 * file is compared with the one on this device, when this device has it.
 *
 * A file that doesn't match, or can't be read back, is marked to be uploaded from this device on the
 * next round. A file that isn't on this device either is reported, because nothing here can fix it.
 */
internal class SpaceMigrationMediaCheck(
    private val tokens: SyncTokenRefresher,
    private val scope: UploadScope,
    private val spaceMedia: FileMediaDataSource,
    private val mediaManager: MediaManager,
    private val refs: MigrationMediaRefs,
) {
    /** Checks the files under [keys], and returns how many were marked to upload again from this device. */
    suspend fun check(keys: Set<String>): Int {
        var marked = 0
        var unavailable = 0
        for ((key, moved) in refs.movedMedia().filterKeys { it in keys }) {
            val reference = SpaceMediaReference.parse(moved.spaceReference) ?: continue
            val local = moved.localUri?.takeUnless(::isOffDevice)?.let { localHash(it) }
            val copy = downloadedHash(moved.spaceReference, reference.cid)
            if (copy != null && (local == null || copy.contentEquals(local))) continue
            if (local == null) {
                unavailable++
                continue
            }
            refs.markForUpload(key)
            marked++
        }
        if (unavailable > 0) throw MigrationMediaUnavailableException("$unavailable files", count = unavailable)
        return marked
    }

    /** The SHA-256 of the decrypted copy in the personal space, or null when it can't be read back. */
    private suspend fun downloadedHash(
        reference: String,
        cid: String,
    ): ByteArray? {
        val result =
            tokens.withFreshToken(
                { token ->
                    spaceMedia.downloadMediaToFile(token, reference, cid)
                        ?: Result.failure(IllegalStateException("Not a space file"))
                },
                "checkMovedMedia($cid)",
                expectedScope = scope,
            )
        val downloaded = result.getOrNull() ?: return null
        return try {
            hashOf(SystemFileSystem.source(downloaded.path))
        } finally {
            SystemFileSystem.delete(downloaded.path, mustExist = false)
        }
    }

    /** The SHA-256 of the file on this device, or null when this device doesn't have it. */
    private suspend fun localHash(uri: String): ByteArray? =
        try {
            hashOf(mediaManager.openMedia(uri).open())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun hashOf(source: RawSource): ByteArray =
        source.use { input ->
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .createHashFunction()
                .use { function ->
                    val buffer = Buffer()
                    while (input.readAtMostTo(buffer, HASH_CHUNK) >= 0) {
                        function.update(buffer.readByteArray())
                    }
                    function.hashToByteArray()
                }
        }
}
