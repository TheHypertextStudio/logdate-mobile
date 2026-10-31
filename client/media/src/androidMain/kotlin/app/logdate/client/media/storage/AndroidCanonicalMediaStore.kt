package app.logdate.client.media.storage

import android.system.Os
import android.system.OsConstants
import app.logdate.client.device.restore.admitNativeContent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Stores attachment bytes in an app-private, content-addressed media store. */
class AndroidCanonicalMediaStore(
    private val filesDir: File,
    private val syncFile: (File) -> Unit = ::syncExistingFile,
    private val publicationTickets: AndroidMediaPublicationTickets? = null,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryOnAndroid,
) {
    private val objectsDirectory = File(filesDir, "media/objects/sha256")
    private val stagingDirectory = File(filesDir, "media/staging")

    suspend fun store(
        input: InputStream,
        mimeType: String,
        expectedSizeBytes: Long,
        canCommit: () -> Boolean = { true },
    ): String {
        require(expectedSizeBytes >= 0) { "Expected media size cannot be negative" }
        val extension = extensionFor(mimeType)
        admitNativeContent(File(filesDir, "media"))
        ensurePrivateDirectory(stagingDirectory)
        ensurePrivateDirectory(objectsDirectory)
        // Age alone cannot prove a staging file is abandoned: Android may suspend a writer mid-stream.
        val stagingFile = File(stagingDirectory, "${Uuid.random()}.tmp")

        try {
            val (hexDigest, copiedBytes) = copyToStaging(input, stagingFile, expectedSizeBytes)
            currentCoroutineContext().ensureActive()

            val destinationDirectory = File(objectsDirectory, hexDigest.take(2))
            ensurePrivateDirectory(destinationDirectory)
            if (!canCommit()) throw CancellationException("Media store commit is no longer valid")
            // Claims precede publication locks because recovery already holds a claim.
            return withPublicationClaim {
                publicationLock.withLock {
                    val lockChannel =
                        FileChannel.open(
                            File(stagingDirectory, ".publication.lock").toPath(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                    lockChannel.use {
                        lockChannel.lock().use {
                            publishUnderLock(stagingFile, destinationDirectory, hexDigest, extension, copiedBytes, canCommit)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                val cleanup = runCatching { !stagingFile.exists() || stagingFile.delete() }
                if (!cleanup.getOrDefault(false)) {
                    Napier.w("Unable to remove private media staging file", cleanup.exceptionOrNull())
                }
            }
        }
    }

    /** Copies the input into the staging file, returning its SHA-256 hex digest and byte count. */
    private suspend fun copyToStaging(
        input: InputStream,
        stagingFile: File,
        expectedSizeBytes: Long,
    ): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        FileOutputStream(stagingFile).use { output ->
            input.use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copiedBytes += read
                }
            }
            output.fd.sync()
        }
        require(copiedBytes == expectedSizeBytes) {
            "Expected $expectedSizeBytes media bytes but copied $copiedBytes"
        }
        return digest.digest().toHex() to copiedBytes
    }

    private suspend fun publishUnderLock(
        stagingFile: File,
        destinationDirectory: File,
        hexDigest: String,
        extension: String,
        copiedBytes: Long,
        canCommit: () -> Boolean,
    ): String {
        var variant = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!canCommit()) throw CancellationException("Media store commit is no longer valid")
            val name = if (variant == 0L) "$hexDigest.$extension" else "$hexDigest.retained-$variant.$extension"
            val destination = File(destinationDirectory, name)
            val ticket =
                publicationTickets?.reserve(
                    destination = "canonical-media",
                    sourcePath = stagingFile.absolutePath,
                    reference = destination.toURI().toString(),
                )
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (isVerifiedObject(destination, hexDigest, copiedBytes)) {
                    ticket?.let { publicationTickets.publish(it.id, destination.toURI().toString()) }
                    syncFile(destination)
                    syncDirectory(destinationDirectory)
                    currentCoroutineContext().ensureActive()
                    return destination.toURI().toString()
                }
                // A working entry may still reference this path. Never remove its bytes
                // merely because an incoming original claims the same digest path.
                variant++
                continue
            }
            try {
                moveIntoPlace(stagingFile, destination, copiedBytes)
            } catch (_: FileAlreadyExistsException) {
                // Another process claimed this path. Recheck it under the lock.
                continue
            }
            ticket?.let { publicationTickets.publish(it.id, destination.toURI().toString()) }
            syncDirectory(destinationDirectory)
            currentCoroutineContext().ensureActive()
            return destination.toURI().toString()
        }
    }

    /**
     * Low-level unlink for a caller that already holds exclusive proof that no claim can be added.
     * Path containment alone does not establish that proof: publication can finish before its
     * Room or DataStore claim is committed. Normal Android media cleanup must not call this until
     * every producer and the collector share a cross-process claim fence. Refuse paths outside
     * this store and symlinks even when that higher-level proof exists.
     */
    suspend fun deleteOwned(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = ownedTarget(uri)?.takeIf { it.isFile } ?: return@withContext false

            val objectsRoot =
                runCatching { objectsDirectory.canonicalFile.toPath() }.getOrNull()
                    ?: return@withContext false
            val resolved =
                runCatching { target.canonicalFile.toPath() }.getOrNull()
                    ?: return@withContext false

            if (!resolved.startsWith(objectsRoot)) return@withContext false
            if (Files.isSymbolicLink(target.toPath())) return@withContext false
            if (hasSymlinkInOwnedParents(target)) return@withContext false

            target.delete()
        }

    private fun hasSymlinkInOwnedParents(target: File): Boolean {
        val root = objectsDirectory.toPath().toAbsolutePath().normalize()
        var parent =
            target
                .toPath()
                .toAbsolutePath()
                .normalize()
                .parent
        while (parent != null && parent.startsWith(root)) {
            if (Files.isSymbolicLink(parent)) return true
            if (parent == root) break
            parent = parent.parent
        }
        return false
    }

    private fun ownedTarget(reference: String): File? {
        val ref = LocalMediaRef.parse(reference)
        if (ref != null) {
            if (ref.collection != MediaCollection.Library || !ref.path.startsWith("objects/sha256/")) return null
            return File(File(filesDir, "media"), ref.path)
        }
        return runCatching { File(URI(reference)) }.getOrNull()
    }

    private suspend fun <T> withPublicationClaim(block: suspend () -> T): T =
        if (publicationTickets == null) block() else publicationTickets.withClaim(block)

    private fun isVerifiedObject(
        file: File,
        expectedDigest: String,
        expectedSizeBytes: Long,
    ): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != expectedSizeBytes) {
            return false
        }
        return FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().toHex() == expectedDigest
        }
    }

    private fun moveIntoPlace(
        stagingFile: File,
        destination: File,
        expectedSizeBytes: Long,
    ) {
        check(stagingFile.length() == expectedSizeBytes) { "Private media staging file changed before publication" }
        // Android denies hard links to app-private files, so the already-synced staging file is
        // renamed into place: the path holds the complete object or nothing. Without a replace
        // option the move refuses any existing path, including a dangling symlink, and every
        // publisher checks that path under the same cross-process lock.
        Files.move(stagingFile.toPath(), destination.toPath())
    }

    private fun ensurePrivateDirectory(directory: File) {
        require(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Unable to create private media directory" }
        check(!Files.isSymbolicLink(directory.toPath())) { "Private media directory cannot be a symlink" }
    }

    private fun extensionFor(mimeType: String): String =
        when (mimeType.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> "jpg"
            "image/heic" -> "heic"
            "image/heic-sequence" -> "heics"
            "image/heif" -> "heif"
            "image/heif-sequence" -> "heifs"
            "image/avif" -> "avif"
            "image/bmp" -> "bmp"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/mpeg" -> "mpg"
            "video/ogg" -> "ogv"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            "video/3gpp2" -> "3g2"
            "video/x-matroska" -> "mkv"
            "video/x-msvideo" -> "avi"
            "video/x-ms-wmv" -> "wmv"
            "audio/mp4" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/aac" -> "aac"
            "audio/amr" -> "amr"
            "audio/flac" -> "flac"
            "audio/midi", "audio/x-midi" -> "mid"
            "audio/opus" -> "opus"
            "audio/3gpp" -> "3gp"
            "audio/3gpp2" -> "3g2"
            "audio/webm" -> "weba"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> throw IllegalArgumentException("Unsupported media MIME type: $mimeType")
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        private val publicationLock = Mutex()

        fun syncExistingFile(file: File) {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.force(true)
            }
        }

        fun syncDirectoryOnAndroid(directory: File) {
            if (System.getProperty("java.vm.name") != "Dalvik") return
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}
