@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package app.logdate.client.media

import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.importFileIntoLibrary
import io.github.aakira.napier.Napier
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeAudio
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** PhotoKit lookup, rendering and library import, separate from durable file publication. */
internal class IosPhotoLibrary(
    private val mediaFiles: MediaFileResolver,
) {
    private val fileManager = NSFileManager.defaultManager

    suspend fun resolvePhotoLibraryImageUri(localIdentifier: String): String? {
        val asset = fetchPhotoLibraryAsset(localIdentifier) ?: return null
        if (asset.mediaType != PHAssetMediaTypeImage) {
            return null
        }
        return importAssetIntoLibrary(asset)
    }

    suspend fun resolvePhotoLibraryVideoUri(localIdentifier: String): String? {
        val asset = fetchPhotoLibraryAsset(localIdentifier) ?: return null
        if (asset.mediaType != PHAssetMediaTypeVideo) {
            return null
        }
        return importAssetIntoLibrary(asset) ?: photoLibraryUri(localIdentifier)
    }

    private suspend fun importAssetIntoLibrary(asset: PHAsset): String? {
        val resource = preferredAssetResource(asset) ?: return null
        val exported = exportResourceToCacheFile(asset, resource)?.path ?: return null
        val fileName = "${asset.localIdentifier.sanitizeForPath()}-${resource.originalFilename.sanitizeForPath()}"
        return importIntoLibrary(exported, fileName)
    }

    internal fun importIntoLibrary(
        sourcePath: String,
        fileName: String,
    ): String? =
        importFileIntoLibrary(mediaFiles, sourcePath, fileName).also { imported ->
            if (imported == null) Napier.e("Failed to copy $sourcePath into the media library")
        }

    suspend fun listPhotoLibraryMedia(fetchLimit: Int? = null): List<MediaObject> {
        if (!hasPhotoLibraryReadAccess()) {
            return emptyList()
        }

        val options =
            PHFetchOptions().apply {
                sortDescriptors = listOf(platform.Foundation.NSSortDescriptor(key = "creationDate", ascending = false))
                if (fetchLimit != null) {
                    this.fetchLimit = fetchLimit.toULong()
                }
            }

        val results = PHAsset.fetchAssetsWithOptions(options)
        val count = results.count.toInt()

        return buildList(capacity = count.coerceAtMost(fetchLimit ?: count)) {
            for (index in 0 until count) {
                val asset = results.objectAtIndex(index.toULong()) as? PHAsset ?: continue
                asset.toPhotoLibraryMediaObject()?.let(::add)
            }
        }
    }

    fun fetchPhotoLibraryAsset(localIdentifier: String?): PHAsset? {
        val identifier = localIdentifier?.takeIf { it.isNotBlank() } ?: return null
        if (!hasPhotoLibraryReadAccess()) {
            return null
        }

        val results = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(identifier), null)
        return results.firstObject as? PHAsset
    }

    private suspend fun PHAsset.toPhotoLibraryMediaObject(): MediaObject? {
        val timestamp =
            creationDate?.let { date ->
                Instant.fromEpochMilliseconds((date.timeIntervalSince1970 * 1000).toLong())
            } ?: Instant.fromEpochMilliseconds(0)

        val resource = preferredAssetResource(this)
        val fileName = resource?.originalFilename?.takeIf { it.isNotBlank() } ?: defaultPhotoLibraryFileName(this)

        return when (mediaType) {
            PHAssetMediaTypeImage -> {
                val uri = resolveRenderableUri(this) ?: return null
                MediaObject.Image(
                    uri = uri,
                    size = 0,
                    name = fileName,
                    timestamp = timestamp,
                )
            }

            PHAssetMediaTypeVideo -> {
                val uri = resolveRenderableUri(this) ?: photoLibraryUri(localIdentifier)
                MediaObject.Video(
                    name = fileName,
                    uri = uri,
                    size = 0,
                    timestamp = timestamp,
                    duration = (duration * 1000).toLong().milliseconds,
                )
            }

            PHAssetMediaTypeAudio -> {
                null
            }

            else -> {
                null
            }
        }
    }

    suspend fun readPhotoLibraryPayload(localIdentifier: String?): MediaPayload {
        val asset = fetchPhotoLibraryAsset(localIdentifier) ?: error("Photo-library asset not found: $localIdentifier")
        val resource = preferredAssetResource(asset) ?: error("Photo-library asset is missing a readable resource")
        val data = readAssetResource(resource)
        val fileName = resource.originalFilename.takeIf { it.isNotBlank() } ?: defaultPhotoLibraryFileName(asset)
        val mimeType = resource.uniformTypeIdentifier.toMimeType()

        return MediaPayload(
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = data.length.toLong(),
            data = data.toByteArray(),
        )
    }

    private suspend fun resolveRenderableUri(asset: PHAsset): String? =
        preferredAssetResource(asset)
            ?.let { resource -> exportResourceToCacheFile(asset, resource) }
            ?.absoluteString

    private suspend fun exportResourceToCacheFile(
        asset: PHAsset,
        resource: PHAssetResource,
    ): NSURL? {
        val cacheDir = ensurePhotoLibraryCacheDir()
        val fileName = "${asset.localIdentifier.sanitizeForPath()}-${resource.originalFilename.sanitizeForPath()}"
        val path = "$cacheDir/$fileName"
        if (fileManager.fileExistsAtPath(path)) {
            return NSURL.fileURLWithPath(path)
        }

        val data = readAssetResource(resource)
        if (!data.writeToFile(path, true)) {
            Napier.e("Failed to write renderable photo-library cache file to $path")
            return null
        }
        return NSURL.fileURLWithPath(path)
    }

    private suspend fun readAssetResource(resource: PHAssetResource): NSData =
        suspendCancellableCoroutine { continuation ->
            val chunks = mutableListOf<ByteArray>()
            val options =
                PHAssetResourceRequestOptions().apply {
                    networkAccessAllowed = true
                }

            PHAssetResourceManager
                .defaultManager()
                .requestDataForAssetResource(
                    resource = resource,
                    options = options,
                    dataReceivedHandler = { chunk ->
                        chunk?.let { chunks += it.toByteArray() }
                    },
                    completionHandler = { error ->
                        if (error != null) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException(error.localizedDescription),
                                )
                            }
                        } else if (continuation.isActive) {
                            continuation.resume(chunks.flattenToNSData())
                        }
                    },
                )
        }

    private fun preferredAssetResource(asset: PHAsset): PHAssetResource? {
        val resources =
            PHAssetResource
                .assetResourcesForAsset(asset)
                .filterIsInstance<PHAssetResource>()

        return when (asset.mediaType) {
            PHAssetMediaTypeImage -> {
                resources.firstOrNull { it.type == PHAssetResourceTypeFullSizePhoto }
                    ?: resources.firstOrNull { it.type == PHAssetResourceTypePhoto }
                    ?: resources.firstOrNull()
            }

            PHAssetMediaTypeVideo -> {
                resources.firstOrNull { it.type == PHAssetResourceTypeFullSizeVideo }
                    ?: resources.firstOrNull { it.type == PHAssetResourceTypeVideo }
                    ?: resources.firstOrNull { it.type == PHAssetResourceTypePairedVideo }
                    ?: resources.firstOrNull()
            }

            else -> {
                resources.firstOrNull()
            }
        }
    }

    private fun ensurePhotoLibraryCacheDir(): String {
        val url: NSURL? =
            fileManager.URLForDirectory(
                directory = NSCachesDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )
        val basePath = requireNotNull(url?.path)
        val cachePath = "$basePath/$PHOTO_LIBRARY_CACHE_DIR_NAME"
        fileManager.createDirectoryAtPath(
            cachePath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return cachePath
    }

    private fun hasPhotoLibraryReadAccess(): Boolean {
        val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        return status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited
    }

    private fun defaultPhotoLibraryFileName(asset: PHAsset): String {
        val baseName = asset.localIdentifier.sanitizeForPath()
        return when (asset.mediaType) {
            PHAssetMediaTypeImage -> "$baseName.jpg"
            PHAssetMediaTypeVideo -> "$baseName.mov"
            else -> baseName
        }
    }

    private fun String.toMimeType(): String =
        when (lowercase()) {
            "public.jpeg", "public.jpg" -> "image/jpeg"
            "public.png" -> "image/png"
            "com.compuserve.gif" -> "image/gif"
            "org.webmproject.webp" -> "image/webp"
            "public.heic", "public.heif" -> "image/heic"
            "com.apple.quicktime-movie" -> "video/quicktime"
            "public.mpeg-4" -> "video/mp4"
            else -> "application/octet-stream"
        }

    suspend fun mediaObject(asset: PHAsset): MediaObject? = asset.toPhotoLibraryMediaObject()

    private fun photoLibraryUri(localIdentifier: String): String = "$PHOTO_LIBRARY_URI_PREFIX$localIdentifier"

    private fun String.sanitizeForPath(): String =
        replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")

    private companion object {
        const val PHOTO_LIBRARY_URI_PREFIX = "ph://"
        const val PHOTO_LIBRARY_CACHE_DIR_NAME = "photo-library-renderable"
    }
}
