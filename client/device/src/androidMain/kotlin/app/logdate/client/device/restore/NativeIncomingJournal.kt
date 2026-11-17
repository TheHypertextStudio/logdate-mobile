package app.logdate.client.device.restore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Incoming native data is immutable input, never a replacement for the working journal. */
class NativeIncomingJournal(
    private val directory: File,
) {
    data class Payload(
        val path: String,
        val file: File,
        val complete: Boolean,
        val expectedSize: Long,
        val cohortId: String? = null,
    )

    @Serializable
    private data class Entry(
        val path: String,
        val expectedSize: Long,
        val size: Long,
        val sha256: String,
    )

    @Serializable
    private data class Manifest(
        val version: Int = 2,
        val recordCount: Long,
        val inventorySha256: String,
    )

    fun begin(deliveryGeneration: String): String =
        synchronized(lock) {
            val root = cohort(deliveryGeneration)
            if (!root.exists()) {
                durableDirectory(root)
                writeAtomic(File(root, "pending"), byteArrayOf(1))
            }
            check(File(root, "pending").exists() || File(root, "manifest.json").exists())
            deliveryGeneration
        }

    fun pending(): Set<String> =
        synchronized(lock) {
            directory.listFiles().orEmpty().filter { File(it, "pending").exists() }.mapTo(mutableSetOf()) {
                Uuid.parse(it.name)
                it.name
            }
        }

    /** Routing metadata is not payload authority; import must still call [verified]. */
    internal fun recordedPathsForRouting(id: String): Set<String> =
        synchronized(lock) {
            recordDirectories(cohort(id)).mapTo(mutableSetOf()) { record ->
                val header = Json.decodeFromString<Entry>(readBounded(File(record, "header.json")).decodeToString())
                validatePath(header.path)
                check(header.expectedSize >= 0)
                val committed = File(record, "entry.json")
                if (committed.exists()) {
                    val entry = Json.decodeFromString<Entry>(readBounded(committed).decodeToString())
                    check(entry.path == header.path && entry.expectedSize == header.expectedSize)
                    check(entry.size in 0..entry.expectedSize && entry.sha256.matches(Regex("[a-f0-9]{64}")))
                }
                header.path
            }
        }

    /** Ambiguous revisions stay in custody; they never select an arbitrary database/WAL pair. */
    @Suppress("unused") // Kept: coherentIncomingPayloads and RetainedDatabaseCopyTest check custody with it
    fun coherentVerified(
        id: String,
        expectedHash: String,
    ): Map<String, Payload>? =
        synchronized(lock) {
            val result = mutableMapOf<String, Payload>()
            verified(id, expectedHash).groupBy(Payload::path).forEach { (path, revisions) ->
                val complete = revisions.filter(Payload::complete).distinctBy { checksum(it.file) }
                if (complete.size != 1) return@synchronized null
                val selected = complete.single()
                if (revisions.any {
                        !it.complete &&
                            (
                                it.expectedSize != selected.expectedSize ||
                                    !prefixMatches(
                                        it.file,
                                        selected.file,
                                    )
                            )
                    }
                ) {
                    return@synchronized null
                }
                result[path] = selected
            }
            result
        }

    fun capture(
        id: String,
        path: String,
        size: Long,
        input: InputStream,
    ) = synchronized(lock) {
        validatePath(path)
        require(size >= 0)
        val root = cohort(id)
        check(File(root, "pending").isFile && !File(root, "manifest.json").exists())
        val record = File(root, "records/${Uuid.random()}")
        durableDirectory(record)
        val header = Json.encodeToString(Entry(path, size, 0, "")).encodeToByteArray()
        check(header.size <= MAX_METADATA_BYTES)
        writeAtomic(File(record, "header.json"), header)
        val payload = File(record, "payload")
        var remaining = size
        try {
            FileOutputStream(payload).use { output ->
                try {
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                } finally {
                    output.fd.sync()
                }
            }
        } finally {
            syncDirectory(record)
            if (payload.exists()) commitEntry(record)
        }
        if (remaining != 0L) throw IOException("Native incoming journal ended before its declared size")
    }

    fun finish(id: String): String =
        synchronized(lock) {
            val root = cohort(id)
            val committed = File(root, "manifest.json")
            if (!committed.exists()) {
                check(File(root, "pending").isFile)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                recordDirectories(root).forEach { record ->
                    verifyEntry(record, commitEntry(record))
                    updateInventory(digest, record)
                    count++
                }
                val bytes = Json.encodeToString(Manifest(recordCount = count, inventorySha256 = digestHex(digest))).encodeToByteArray()
                check(bytes.size <= MAX_METADATA_BYTES)
                writeAtomic(committed, bytes)
            }
            val hash = checksum(committed)
            verified(id, hash)
            if (File(root, "pending").exists()) {
                Files.delete(File(root, "pending").toPath())
                syncDirectory(root)
            }
            hash
        }

    /** Reads at most one bounded metadata record at a time; payload hashing is streamed. */
    fun verified(
        id: String,
        expectedHash: String,
    ): List<Payload> =
        synchronized(lock) {
            require(expectedHash.matches(Regex("[a-f0-9]{64}")))
            val root = cohort(id)
            val manifestFile = File(root, "manifest.json")
            val manifest = Json.decodeFromString<Manifest>(readBounded(manifestFile).decodeToString())
            check(checksum(manifestFile) == expectedHash)
            check(manifest.version == 2 && manifest.recordCount >= 0 && manifest.inventorySha256.matches(Regex("[a-f0-9]{64}")))
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val result = mutableListOf<Pair<Triple<String, Long, String>, Payload>>()
            recordDirectories(root).forEach { record ->
                val entry = Json.decodeFromString<Entry>(readBounded(File(record, "entry.json")).decodeToString())
                verifyEntry(record, entry)
                updateInventory(digest, record)
                count++
                result += Triple(entry.path, entry.expectedSize, entry.sha256) to
                    Payload(entry.path, File(record, "payload"), entry.size == entry.expectedSize, entry.expectedSize, id)
            }
            check(count == manifest.recordCount && digestHex(digest) == manifest.inventorySha256)
            result.distinctBy { it.first }.map { it.second }
        }

    private fun recordDirectories(root: File): List<File> =
        File(root, "records")
            .listFiles()
            .orEmpty()
            .filter { record ->
                Uuid.parse(record.name)
                check(Files.isDirectory(record.toPath(), LinkOption.NOFOLLOW_LINKS))
                val header = File(record, "header.json")
                if (!header.exists()) check(!File(record, "payload").exists())
                header.exists()
            }.sortedBy(File::getName)

    private fun commitEntry(record: File): Entry {
        val header = Json.decodeFromString<Entry>(readBounded(File(record, "header.json")).decodeToString())
        validatePath(header.path)
        check(header.expectedSize >= 0)
        val payload = File(record, "payload")
        if (!payload.exists()) {
            FileOutputStream(payload).use { it.fd.sync() }
            syncDirectory(record)
        }
        val committed = File(record, "entry.json")
        if (committed.exists()) {
            return Json.decodeFromString<Entry>(readBounded(committed).decodeToString()).also {
                check(it.path == header.path && it.expectedSize == header.expectedSize)
            }
        }
        val entry = header.copy(size = payload.length(), sha256 = checksum(payload))
        val encoded = Json.encodeToString(entry).encodeToByteArray()
        check(encoded.size <= MAX_METADATA_BYTES)
        writeAtomic(committed, encoded)
        return entry
    }

    private fun verifyEntry(
        record: File,
        entry: Entry,
    ) {
        validatePath(entry.path)
        val file = File(record, "payload")
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(entry.size >= 0 && entry.expectedSize >= entry.size && file.length() == entry.size && checksum(file) == entry.sha256)
    }

    private fun updateInventory(
        digest: MessageDigest,
        record: File,
    ) {
        digest.update((record.name + ":" + checksum(File(record, "entry.json")) + "\n").encodeToByteArray())
    }

    private fun readBounded(file: File): ByteArray {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val bytes = ByteArray(MAX_METADATA_BYTES + 1)
        var count = 0
        file.inputStream().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
        }
        check(count in 1..MAX_METADATA_BYTES)
        return bytes.copyOf(count)
    }

    private fun cohort(id: String): File {
        require(Uuid.parse(id).toString() == id)
        return File(directory, id)
    }

    private fun validatePath(path: String) {
        require(path.length in 1..1024 && path.split('/').none { it.isEmpty() || it == "." || it == ".." })
        require(path.substringBefore('/') in setOf("database", "files", "shared_prefs"))
    }

    @Suppress("unused") // Kept: coherentVerified compares an interrupted revision with the complete one with it
    private fun prefixMatches(
        prefix: File,
        complete: File,
    ): Boolean =
        prefix.inputStream().use { left ->
            complete.inputStream().use rightStream@{ right ->
                val buffer = ByteArray(64 * 1024)
                val candidate = ByteArray(buffer.size)
                while (true) {
                    val count = left.read(buffer)
                    if (count < 0) return@rightStream true
                    var offset = 0
                    while (offset < count) {
                        val read = right.read(candidate, offset, count - offset)
                        if (read < 0) return@rightStream false
                        offset += read
                    }
                    if (!buffer.copyOf(count).contentEquals(candidate.copyOf(count))) return@rightStream false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }

    private fun checksum(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digestHex(digest)
        }

    private fun digestHex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    private fun writeAtomic(
        file: File,
        bytes: ByteArray,
    ) {
        durableDirectory(checkNotNull(file.parentFile))
        val temporary = File.createTempFile(".incoming-", ".part", file.parentFile)
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(checkNotNull(file.parentFile))
        } finally {
            temporary.delete()
        }
    }

    private fun durableDirectory(file: File) {
        if (file.isDirectory) return
        durableDirectory(checkNotNull(file.parentFile))
        check(file.mkdir() || file.isDirectory)
        syncDirectory(checkNotNull(file.parentFile))
    }

    private fun syncDirectory(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private companion object {
        const val MAX_METADATA_BYTES = 8192
        val lock = Any()
    }
}
