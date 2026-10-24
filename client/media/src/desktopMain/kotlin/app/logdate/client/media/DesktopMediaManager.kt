package app.logdate.client.media

import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.desktopMediaFileResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.function.BiPredicate
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.streams.asSequence
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DesktopMediaManager(
    /** Resolves stored media references. Injectable so a test never touches a real home directory. */
    private val mediaFiles: MediaFileResolver = desktopMediaFileResolver(),
) : MediaManager,
    ConditionalMediaSaver {
    /** Where LogDate keeps its own copies. */
    private val mediaRoot: Path = Path.of(mediaFiles.directory(MediaCollection.Library))

    override suspend fun getMedia(uri: String): MediaObject {
        val path = resolvePath(uri)
        return toMediaObject(path) ?: error("Unsupported or missing media at $uri")
    }

    /**
     * Deletes a file LogDate itself wrote, and reports whether it was there to delete.
     *
     * Refuses anything that does not resolve inside the directory `saveMedia` and
     * `saveMediaFromFile` write to. Media referenced by an entry may be a file the user picked from
     * anywhere on their disk, and removing an entry must never remove that. Symlinks are refused
     * for the same reason: one could resolve to somewhere outside the store.
     */
    override suspend fun deleteOwnedMedia(uri: String): Boolean {
        val target = runCatching { resolvePath(uri) }.getOrNull() ?: return false
        if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) return false

        val root = runCatching { mediaRoot.toRealPath() }.getOrNull() ?: return false
        val resolved = runCatching { target.toRealPath() }.getOrNull() ?: return false
        if (!resolved.startsWith(root)) return false

        return Files.deleteIfExists(resolved)
    }

    override suspend fun exists(mediaId: String): Boolean = mediaFiles.filePath(mediaId)?.let { Files.exists(Path.of(it)) } ?: false

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> {
        val media =
            listLibraryMediaObjects()
                .sortedByDescending { it.timestamp }
                .take(limit)
        return flowOf(media)
    }

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> {
        val media =
            listLibraryMediaObjects()
                .filter { it.timestamp >= start && it.timestamp < end }
        return flowOf(media)
    }

    override suspend fun addToDefaultCollection(uri: String) {
        val source = resolvePath(uri)
        ensureMediaDir()
        if (source.startsWith(mediaRoot)) {
            return
        }
        val sanitizedName =
            source.name
                .replace("..", "_")
                .replace("/", "_")
                .replace("\\", "_")
        val targetName = "${Uuid.random()}-$sanitizedName"
        val target = mediaRoot.resolve(targetName)
        if (!Files.exists(target)) {
            Files.copy(source, target)
        }
    }

    override suspend fun readMedia(uri: String): MediaPayload {
        val path = resolvePath(uri)
        val data = Files.readAllBytes(path)
        val fileName = path.name
        val mimeType = Files.probeContentType(path) ?: guessMimeType(fileName)
        return MediaPayload(
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = data.size.toLong(),
            data = data,
        )
    }

    override suspend fun openMedia(uri: String): MediaFileSource {
        val path = resolvePath(uri)
        val fileName = path.name
        return MediaFileSource(
            fileName = fileName,
            mimeType = Files.probeContentType(path) ?: guessMimeType(fileName),
            sizeBytes = Files.size(path),
        ) { Files.newInputStream(path).asSource() }
    }

    override suspend fun saveMedia(payload: MediaPayload): String {
        val directory = ensureMediaDir()
        val sanitizedName =
            payload.fileName
                .replace("..", "_")
                .replace("/", "_")
                .replace("\\", "_")
        val fileName = "${Uuid.random()}-$sanitizedName"
        val filePath = directory.resolve(fileName)
        Files.write(filePath, payload.data)
        return "file://${filePath.pathString}"
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
    ): String {
        val directory = ensureMediaDir()
        val sanitizedName =
            fileName
                .replace("..", "_")
                .replace("/", "_")
                .replace("\\", "_")
        val destFileName = "${Uuid.random()}-$sanitizedName"
        val destPath = directory.resolve(destFileName)
        Files.copy(Path.of(sourceFilePath), destPath)
        return destPath.toUri().toString()
    }

    private fun resolvePath(uri: String): Path = Path.of(mediaFiles.filePath(uri) ?: error("Not a local media file: $uri"))

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

    private fun ensureMediaDir(): Path {
        val directory = mediaRoot
        directory.createDirectories()
        return directory
    }

    private fun listLibraryMediaObjects(): List<MediaObject> {
        val libraryRoots = existingLibraryRoots()
        if (libraryRoots.isEmpty()) {
            return emptyList()
        }

        return libraryRoots
            .flatMap(::scanLibraryRoot)
            .distinctBy { it.uri }
    }

    private fun scanLibraryRoot(root: Path): List<MediaObject> =
        Files
            .find(
                root,
                LIBRARY_SCAN_DEPTH,
                BiPredicate<Path, BasicFileAttributes> { path, attributes ->
                    attributes.isRegularFile && !path.hasIgnoredLibrarySegment(root)
                },
            ).use { stream ->
                stream
                    .asSequence()
                    .mapNotNull(::toMediaObject)
                    .toList()
            }

    private fun existingLibraryRoots(): List<Path> =
        if (Files.exists(picturesRoot)) {
            listOf(picturesRoot)
        } else {
            listOf(mediaRoot).filter(Files::exists)
        }

    private fun toMediaObject(path: Path): MediaObject? {
        val extension = path.extension.lowercase()
        val name = path.name
        val size = Files.size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val lastModified = Files.getLastModifiedTime(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val timestamp = fileTimeToInstant(lastModified)

        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif" -> {
                MediaObject.Image(
                    uri = "file://${path.pathString}",
                    size = size,
                    name = name,
                    timestamp = timestamp,
                )
            }

            "mp4", "mov", "m4v" -> {
                MediaObject.Video(
                    name = name,
                    uri = "file://${path.pathString}",
                    size = size,
                    timestamp = timestamp,
                    duration = Duration.ZERO,
                )
            }

            else -> {
                null
            }
        }
    }

    private fun fileTimeToInstant(fileTime: FileTime): Instant = Instant.fromEpochMilliseconds(fileTime.toMillis())

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        if (extension.isBlank()) {
            return "application/octet-stream"
        }
        return when (extension.lowercase()) {
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
        private const val LIBRARY_SCAN_DEPTH = 4
        private val picturesRoot: Path = Path.of(System.getProperty("user.home"), "Pictures")
    }
}

private fun Path.hasIgnoredLibrarySegment(root: Path): Boolean {
    val relativePath = root.relativize(this)
    val relativeNameCount = relativePath.nameCount
    return (0 until relativeNameCount).any { index ->
        val segment = relativePath.getName(index).toString()
        segment.startsWith(".") ||
            segment.endsWith(".photoslibrary") ||
            segment.endsWith(".app")
    }
}
