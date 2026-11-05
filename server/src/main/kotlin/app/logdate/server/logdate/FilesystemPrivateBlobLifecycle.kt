package app.logdate.server.logdate

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Short OS locks serialize publication with a durable marker; ciphertext streaming holds no lock. */
internal class FilesystemPrivateBlobLifecycle(
    private val root: Path,
    private val forcePayload: (Path) -> Unit = { path ->
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    },
) {
    fun pending(
        blobId: String,
        owner: String,
    ): Path =
        locked(blobId) { path ->
            requirePrivateBlobOwner(owner)
            if (Files.exists(path, NOFOLLOW_LINKS)) throw LogDateBlobCollisionException()
            val pending = pendingPath(blobId, owner)
            Files.createFile(pending)
            try {
                tag(pending, owner, "live")
            } catch (error: Exception) {
                Files.deleteIfExists(pending)
                throw error
            }
            pending
        }

    fun publish(
        blobId: String,
        owner: String,
        pending: Path,
    ) = locked(blobId) { path ->
        check(attribute(pending, PRIVATE_BLOB_OWNER) == owner && attribute(pending, PRIVATE_BLOB_STATE) == "live")
        forcePayload(pending)
        try {
            Files.createLink(path, pending)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            throw LogDateBlobCollisionException()
        }
        Files.delete(pending)
        syncDirectory(path.parent)
    }

    fun open(
        storagePath: String,
        owner: String,
    ): LogDateBlobStream? {
        requirePrivateBlobOwner(owner)
        val blobId = privateBlobId(storagePath) ?: return null
        return locked(blobId) { path ->
            if (attribute(path, PRIVATE_BLOB_OWNER) != owner || attribute(path, PRIVATE_BLOB_STATE) != "live") return@locked null
            // A previous publisher may have lost its acknowledgement while syncing the directory.
            forcePayload(path)
            syncDirectory(path.parent)
            val channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)
            try {
                LogDateBlobStream(
                    channel.size(),
                    java.nio.channels.Channels
                        .newInputStream(channel),
                )
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }
    }

    fun settle(
        blobId: String,
        owner: String,
    ) = locked(blobId) {
        val pending = pendingPath(blobId, owner)
        if (attribute(pending, PRIVATE_BLOB_OWNER) == owner) Files.deleteIfExists(pending)
    }

    fun retire(
        blobId: String,
        owner: String,
    ): Boolean =
        locked(blobId) { path ->
            requirePrivateBlobOwner(owner)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                if (attribute(path, PRIVATE_BLOB_OWNER) != owner) return@locked false
                if (retired(path)) {
                    removePending(blobId, owner)
                    syncDirectory(path.parent)
                    return@locked true
                }
            }
            val marker = Files.createTempFile(path.parent, ".private-retirement-", ".pending")
            try {
                tag(marker, owner, PRIVATE_BLOB_RETIRED)
                FileChannel.open(marker, WRITE).use { it.force(true) }
                if (Files.exists(path, NOFOLLOW_LINKS)) {
                    // Private publishers share this lock; ownership cannot change between the read and replace.
                    Files.move(marker, path, ATOMIC_MOVE, REPLACE_EXISTING)
                } else {
                    try {
                        Files.createLink(path, marker)
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        return@locked false
                    }
                }
                removePending(blobId, owner)
                syncDirectory(path.parent)
                true
            } finally {
                Files.deleteIfExists(marker)
            }
        }

    fun delete(blobId: String): Boolean =
        locked(blobId) { path ->
            if (retired(path) || attribute(path, PRIVATE_BLOB_OWNER) == null) return@locked false
            Files.deleteIfExists(path)
        }

    fun retired(path: Path): Boolean = attribute(path, PRIVATE_BLOB_STATE) == PRIVATE_BLOB_RETIRED

    private fun removePending(
        blobId: String,
        owner: String,
    ) {
        val pending = pendingPath(blobId, owner)
        if (attribute(pending, PRIVATE_BLOB_OWNER) == owner) Files.deleteIfExists(pending)
    }

    private fun pendingPath(
        blobId: String,
        owner: String,
    ) = root.resolve("$PRIVATE_BLOB_PREFIX.$blobId.$owner.pending")

    private fun tag(
        path: Path,
        owner: String,
        state: String,
    ) {
        val view = requireNotNull(Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java, NOFOLLOW_LINKS))
        view.write(PRIVATE_BLOB_OWNER, ByteBuffer.wrap(owner.toByteArray(Charsets.US_ASCII)))
        view.write(PRIVATE_BLOB_STATE, ByteBuffer.wrap(state.toByteArray(Charsets.US_ASCII)))
    }

    private fun attribute(
        path: Path,
        key: String,
    ): String? {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java, NOFOLLOW_LINKS) ?: return null
        return try {
            if (key !in view.list()) return null
            val size = view.size(key)
            if (size !in 1..64) return null
            val bytes = ByteBuffer.allocate(size)
            view.read(key, bytes)
            String(bytes.array(), Charsets.US_ASCII)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun syncDirectory(path: Path) {
        FileChannel.open(path, READ).use { it.force(true) }
    }

    private fun <T> locked(
        blobId: String,
        block: (Path) -> T,
    ): T =
        processLock.withLock {
            val path = root.resolve(privateBlobPath(blobId))
            Files.createDirectories(path.parent)
            FileChannel.open(path.resolveSibling(".${path.fileName}.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use { block(path) }
            }
        }

    private companion object {
        val processLock = ReentrantLock()
    }
}
