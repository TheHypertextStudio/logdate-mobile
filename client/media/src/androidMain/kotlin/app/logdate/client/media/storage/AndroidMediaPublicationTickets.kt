package app.logdate.client.media.storage

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Durable handoff for one publication attempt, independent of a deduplicated media URI. */
data class AndroidMediaPublicationTicket(
    val id: String,
    val destination: String,
    val sourcePath: String,
    val reference: String? = null,
)

/** App-private ticket ledger. The source remains in custody until its destination claim is durable. */
class AndroidMediaPublicationTickets(
    private val noBackupFilesDir: File,
    private val claimFence: MediaClaimFence = MediaClaimFence.Disabled,
) {
    private val root = File(noBackupFilesDir, "media-reclamation")
    private val directory = File(root, "tickets")
    private val mutex = processMutexes.getOrPut(root.absolutePath) { Mutex() }

    internal suspend fun <T> withClaim(block: suspend () -> T): T = claimFence.withClaim(block)

    suspend fun reserve(
        destination: String,
        sourcePath: String,
        reference: String? = null,
    ): AndroidMediaPublicationTicket =
        claimFence.withClaim {
            locked {
                require(destination.isNotBlank() && destination.encodeToByteArray().size <= MAX_FIELD_BYTES)
                require(sourcePath.isNotBlank() && sourcePath.encodeToByteArray().size <= MAX_FIELD_BYTES)
                require(reference == null || (reference.isNotBlank() && reference.encodeToByteArray().size <= MAX_FIELD_BYTES))
                AndroidMediaPublicationTicket(Uuid.random().toString(), destination, sourcePath, reference).also(::write)
            }
        }

    suspend fun publish(
        id: String,
        reference: String,
    ): Unit =
        locked {
            require(reference.isNotBlank() && reference.encodeToByteArray().size <= MAX_FIELD_BYTES)
            val current = read(ticketFile(id))
            check(current.reference == null || current.reference == reference) { "Media ticket was published to another reference" }
            if (current.reference == null) write(current.copy(reference = reference))
        }

    /** The caller must have committed the destination claim before acknowledging its ticket. */
    suspend fun acknowledge(
        id: String,
        reference: String,
    ): Unit =
        locked {
            val file = ticketFile(id)
            check(read(file).reference == reference) { "Media ticket reference changed" }
            Files.delete(file.toPath())
            syncDirectory(directory)
        }

    suspend fun pending(): List<AndroidMediaPublicationTicket> = locked(::readPending)

    private fun readPending(): List<AndroidMediaPublicationTicket> =
        checkNotNull(directory.listFiles()) { "Could not read media publication tickets" }
            .filter { it.name.endsWith(TICKET_SUFFIX) }
            .map(::read)
            .sortedBy { it.id }

    private fun ticketFile(id: String): File {
        require(Uuid.parse(id).toString() == id) { "Invalid media ticket identity" }
        return File(directory, "$id$TICKET_SUFFIX")
    }

    private fun write(ticket: AndroidMediaPublicationTicket) {
        val body =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(FORMAT)
                    output.writeUTF(ticket.id)
                    output.writeUTF(ticket.destination)
                    output.writeUTF(ticket.sourcePath)
                    output.writeBoolean(ticket.reference != null)
                    ticket.reference?.let(output::writeUTF)
                }
                bytes.toByteArray()
            }
        val target = ticketFile(ticket.id)
        val temporary = File.createTempFile(".ticket-", ".part", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(body)
                output.write(digest(body))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally {
            temporary.delete()
        }
    }

    private fun read(file: File): AndroidMediaPublicationTicket {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid media ticket file" }
        check(file.length() in MIN_RECORD_BYTES..MAX_RECORD_BYTES) { "Invalid media ticket length" }
        val encoded = file.readBytes()
        val body = encoded.copyOfRange(0, encoded.size - DIGEST_BYTES)
        check(digest(body).contentEquals(encoded.copyOfRange(encoded.size - DIGEST_BYTES, encoded.size))) {
            "Media ticket checksum changed"
        }
        val ticket =
            try {
                DataInputStream(ByteArrayInputStream(body)).use { input ->
                    check(input.readInt() == FORMAT) { "Unsupported media ticket version" }
                    val result =
                        AndroidMediaPublicationTicket(
                            id = input.readUTF(),
                            destination = input.readUTF(),
                            sourcePath = input.readUTF(),
                            reference = if (input.readBoolean()) input.readUTF() else null,
                        )
                    check(input.available() == 0) { "Media ticket has trailing data" }
                    result
                }
            } catch (error: Exception) {
                throw IllegalStateException("Invalid media publication ticket", error)
            }
        check(ticketFile(ticket.id).name == file.name) { "Media ticket identity changed" }
        check(ticket.destination.isNotBlank() && ticket.sourcePath.isNotBlank()) { "Media ticket destination or source is empty" }
        return ticket
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.lock()
            try {
                ensureDirectory(root)
                ensureDirectory(directory)
                FileChannel
                    .open(
                        File(root, ".tickets.lock").toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { channel -> acquireLock(channel).use { block() } }
            } finally {
                mutex.unlock()
            }
        }

    private suspend fun acquireLock(channel: FileChannel): FileLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val acquired =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (acquired != null) return acquired
            delay(25)
        }
    }

    private fun ensureDirectory(file: File) {
        check(file.isDirectory || file.mkdirs() || file.isDirectory) { "Could not create media ticket directory" }
        check(!Files.isSymbolicLink(file.toPath())) { "Media ticket directory is a symlink" }
    }

    private fun syncDirectory(path: File) {
        if (System.getProperty("java.vm.name") != "Dalvik") return
        val descriptor = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        val processMutexes = ConcurrentHashMap<String, Mutex>()
        const val FORMAT = 1
        const val DIGEST_BYTES = 32
        const val MIN_RECORD_BYTES = DIGEST_BYTES + 4
        const val MAX_FIELD_BYTES = 4096
        const val MAX_RECORD_BYTES = 3 * MAX_FIELD_BYTES + DIGEST_BYTES + 128L
        const val TICKET_SUFFIX = ".ticket"
    }
}
