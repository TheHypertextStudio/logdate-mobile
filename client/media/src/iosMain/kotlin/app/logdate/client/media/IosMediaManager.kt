@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package app.logdate.client.media

import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.iosMediaFileResolver
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * iOS implementation of MediaManager backed by app-scoped storage and the user's Photos library.
 *
 * Photos-library assets are represented with stable `ph://<localIdentifier>` URIs so the editor
 * can pass selected images through save/sync flows before they are copied into app storage.
 */
class IosMediaManager(
    private val mediaFiles: MediaFileResolver = iosMediaFileResolver(),
) : MediaManager,
    ConditionalMediaSaver {
    private val fileManager = NSFileManager.defaultManager
    private val photoLibrary = IosPhotoLibrary(mediaFiles)
    private val mediaRootPath = mediaFiles.directory(MediaCollection.Library)
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")
    private val videoExtensions = setOf("mp4", "mov", "m4v")

    override suspend fun getMedia(uri: String): MediaObject =
        withContext(Dispatchers.Default) {
            if (uri.isPhotoLibraryUri()) {
                val localIdentifier =
                    uri.photoLibraryLocalIdentifier() ?: return@withContext error(
                        "Invalid photo library URI: $uri",
                    )
                val asset = photoLibrary.fetchPhotoLibraryAsset(localIdentifier)
                return@withContext asset?.let { photoLibrary.mediaObject(it) } ?: error("Unsupported or missing media at $uri")
            }

            val path = resolvePath(uri)
            val media = path?.let { toMediaObject(it) }
            return@withContext media ?: error("Unsupported or missing media at $uri")
        }

    /**
     * Deletes a file LogDate itself copied into app storage, and reports whether it was there.
     *
     * A `ph://` URI is an asset in the user's photo library and is refused outright -- removing an
     * entry must never remove a photo from their library. A file is deleted only when it is in
     * LogDate's media library, so a file referenced from anywhere else is left alone.
     */
    override suspend fun deleteOwnedMedia(uri: String): Boolean =
        withContext(Dispatchers.Default) {
            val path = resolvePath(uri) ?: return@withContext false
            if (mediaFiles.refFor(path)?.collection != MediaCollection.Library) return@withContext false
            if (!fileManager.fileExistsAtPath(path)) return@withContext false

            fileManager.removeItemAtPath(path, error = null)
        }

    override suspend fun exists(mediaId: String): Boolean =
        withContext(Dispatchers.Default) {
            if (mediaId.isPhotoLibraryUri()) {
                return@withContext photoLibrary.fetchPhotoLibraryAsset(mediaId.photoLibraryLocalIdentifier()) != null
            }

            val path = resolvePath(mediaId)
            path != null && fileManager.fileExistsAtPath(path)
        }

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> =
        withContext(Dispatchers.Default) {
            val media =
                (photoLibrary.listPhotoLibraryMedia(fetchLimit = MAX_RECENT_MEDIA) + listMediaObjects())
                    .sortedByDescending { it.timestamp }
                    .take(MAX_RECENT_MEDIA)
            flowOf(media)
        }

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> =
        withContext(Dispatchers.Default) {
            val media =
                (photoLibrary.listPhotoLibraryMedia() + listMediaObjects())
                    .filter { it.timestamp >= start && it.timestamp < end }
            flowOf(media)
        }

    override suspend fun addToDefaultCollection(uri: String) =
        withContext(Dispatchers.Default) {
            if (uri.isPhotoLibraryUri()) {
                val payload =
                    runCatching { photoLibrary.readPhotoLibraryPayload(uri.photoLibraryLocalIdentifier()) }
                        .getOrElse { error ->
                            Napier.e("Failed to copy photo-library media into app storage", error)
                            return@withContext
                        }

                ensureMediaDir()
                val targetName =
                    "${uri.photoLibraryLocalIdentifier()?.sanitizeForPath() ?: Uuid.random()}-" +
                        payload.fileName.sanitizeForPath()
                val destination = buildMediaPath(targetName)
                if (!fileManager.fileExistsAtPath(destination)) {
                    val data = payload.data.toNSData()
                    if (!data.writeToFile(destination, true)) {
                        Napier.e("Failed to write copied photo-library media to $destination")
                    }
                }
                return@withContext
            }

            val sourcePath = resolvePath(uri) ?: return@withContext
            ensureMediaDir()
            if (mediaFiles.refFor(sourcePath)?.collection == MediaCollection.Library) {
                return@withContext
            }
            val destination = buildMediaPath(sourcePath.substringAfterLast('/'))
            if (!fileManager.fileExistsAtPath(destination)) {
                fileManager.copyItemAtPath(sourcePath, destination, error = null)
            }
        }

    override suspend fun readMedia(uri: String): MediaPayload =
        withContext(Dispatchers.Default) {
            if (uri.isPhotoLibraryUri()) {
                return@withContext photoLibrary.readPhotoLibraryPayload(uri.photoLibraryLocalIdentifier())
            }

            val path = resolvePath(uri) ?: error("Invalid media URI: $uri")
            val data = fileManager.contentsAtPath(path) ?: error("Unable to read media at $uri")
            val fileName = path.substringAfterLast('/')
            val mimeType = guessMimeType(fileName)
            return@withContext MediaPayload(
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = data.length.toLong(),
                data = data.toByteArray(),
            )
        }

    override suspend fun openMedia(uri: String): MediaFileSource {
        // Photo library assets are only reachable through PhotoKit, which hands back whole
        // payloads rather than a file to stream.
        if (uri.isPhotoLibraryUri()) return super.openMedia(uri)
        return withContext(Dispatchers.Default) {
            val path = Path(resolvePath(uri) ?: error("Invalid media URI: $uri"))
            val sizeBytes = SystemFileSystem.metadataOrNull(path)?.size ?: error("Unable to read media at $uri")
            MediaFileSource(
                fileName = path.name,
                mimeType = guessMimeType(path.name),
                sizeBytes = sizeBytes,
            ) { SystemFileSystem.source(path) }
        }
    }

    override suspend fun saveMedia(payload: MediaPayload): String =
        withContext(Dispatchers.Default) {
            ensureMediaDir()
            val sanitizedName =
                payload.fileName
                    .replace("..", "_")
                    .replace("/", "_")
                    .replace("\\", "_")
            val fileName = "${Uuid.random()}-$sanitizedName"
            val path = buildMediaPath(fileName)
            val data = payload.data.toNSData()
            publishIosMediaFile(path) { data.writeToFile(path, true) }
            return@withContext "file://$path"
        }

    override suspend fun saveMedia(
        payload: MediaPayload,
        canCommit: () -> Boolean,
    ): String {
        if (!canCommit()) throw CancellationException("Media hydration scope changed")
        val uri = saveMedia(payload)
        if (!canCommit()) {
            withContext(NonCancellable) { deleteOwnedMedia(uri) }
            throw CancellationException("Media hydration scope changed")
        }
        return uri
    }

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String =
        withContext(Dispatchers.Default) {
            ensureMediaDir()
            val sanitizedName = fileName.sanitizeForPath()
            val destFileName = "${Uuid.random()}-$sanitizedName"
            val destPath = buildMediaPath(destFileName)
            publishIosMediaFile(destPath) { fileManager.copyItemAtPath(sourceFilePath, destPath, error = null) }
            return@withContext NSURL.fileURLWithPath(destPath).absoluteString ?: "file://$destPath"
        }

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
        canCommit: () -> Boolean,
    ): String {
        if (!canCommit()) throw CancellationException("Media hydration scope changed")
        val uri = saveMediaFromFile(sourceFilePath, fileName, mimeType)
        if (!canCommit()) {
            withContext(NonCancellable) { deleteOwnedMedia(uri) }
            throw CancellationException("Media hydration scope changed")
        }
        return uri
    }

    /** Copies a selected photo into the durable library; the renderable cache stays purgeable. */
    suspend fun resolvePhotoLibraryImageUri(localIdentifier: String): String? = photoLibrary.resolvePhotoLibraryImageUri(localIdentifier)

    /** Copies a selected video, or retains its Photos reference if it cannot be copied. */
    suspend fun resolvePhotoLibraryVideoUri(localIdentifier: String): String? = photoLibrary.resolvePhotoLibraryVideoUri(localIdentifier)

    /** Keeps an existing library copy when importing the same named Photos original again. */
    internal fun importIntoLibrary(
        sourcePath: String,
        fileName: String,
    ): String? = photoLibrary.importIntoLibrary(sourcePath, fileName)

    private fun listMediaObjects(): List<MediaObject> {
        ensureMediaDir()
        val files = fileManager.contentsOfDirectoryAtPath(mediaRootPath, error = null) ?: return emptyList()
        return files
            .filterIsInstance<String>()
            .mapNotNull { fileName -> toMediaObject("$mediaRootPath/$fileName") }
    }

    private fun toMediaObject(path: String): MediaObject? {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return null
        val name = path.substringAfterLast('/')
        val attributes = fileManager.attributesOfItemAtPath(path, error = null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        val modifiedAt = attributes?.get(NSFileModificationDate) as? NSDate
        val timestamp =
            modifiedAt?.let { date ->
                Instant.fromEpochMilliseconds((date.timeIntervalSince1970 * 1000).toLong())
            } ?: Instant.fromEpochMilliseconds(0)

        return when {
            imageExtensions.contains(extension) -> {
                MediaObject.Image(
                    uri = "file://$path",
                    size = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    name = name,
                    timestamp = timestamp,
                )
            }

            videoExtensions.contains(extension) -> {
                MediaObject.Video(
                    name = name,
                    uri = "file://$path",
                    size = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    timestamp = timestamp,
                    duration = resolveDuration(path),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun resolveDuration(path: String): Duration {
        val url = NSURL.fileURLWithPath(path)
        val asset = AVURLAsset.URLAssetWithURL(url, null)
        val seconds = CMTimeGetSeconds(asset.duration)
        if (seconds.isNaN() || seconds <= 0) {
            return Duration.ZERO
        }
        return (seconds * 1000).toLong().milliseconds
    }

    private fun resolvePath(uri: String): String? = mediaFiles.filePath(uri)

    private fun ensureMediaDir() {
        fileManager.createDirectoryAtPath(
            mediaRootPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private fun buildMediaPath(fileName: String): String = "$mediaRootPath/$fileName"

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov", "m4v" -> "video/quicktime"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        private const val MAX_RECENT_MEDIA = 50
        private const val PHOTO_LIBRARY_URI_PREFIX = "ph://"
    }

    private fun String.isPhotoLibraryUri(): Boolean = startsWith(PHOTO_LIBRARY_URI_PREFIX)

    private fun String.photoLibraryLocalIdentifier(): String? = removePrefix(PHOTO_LIBRARY_URI_PREFIX).takeIf { it.isNotBlank() }

    private fun String.sanitizeForPath(): String =
        replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
}
