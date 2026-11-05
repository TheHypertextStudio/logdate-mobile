package app.logdate.server.logdate

import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32C

/** Where a streamed object goes: [blobId] within [ownerId]'s [namespace]. */
data class LogDateBlobStreamRequest(
    val ownerId: UUID,
    val namespace: LogDateBlobNamespace,
    val blobId: String,
    val contentType: String,
    val createOnly: Boolean = false,
    val privateOwner: String? = null,
)

/**
 * An object being written as its bytes arrive, so no copy of it is kept in memory or on local disk.
 * Write to [output], then [commit]. Until [commit] succeeds the object is not stored for anyone to
 * read; [abort] deletes whatever was written.
 */
interface LogDateBlobWriter {
    /** The path the object is stored at once committed. */
    val storagePath: String

    /** Takes the object's bytes. Every byte written here is counted towards the integrity check. */
    val output: OutputStream

    /**
     * Finishes the object and checks that storage holds exactly the bytes written to [output], by
     * length and CRC32C. A mismatch throws [LogDateBlobIntegrityException]; the caller must
     * settle the writer and clean its unpublished candidate through its reference-checked lifecycle.
     */
    fun commit()

    /** Stops further provider writes without deleting the object; cleanup must verify references first. */
    fun closeForCleanup() = output.close()

    /** Deletes whatever was written. Calling it after a failed [commit], or twice, is safe. */
    fun abort()
}

/** Storage holds different bytes from the ones a [LogDateBlobWriter] was given. */
class LogDateBlobIntegrityException(
    message: String,
) : RuntimeException(message)

/** Counts the bytes written through it and their CRC32C, the checksum object stores report. */
class ChecksummingOutputStream(
    output: OutputStream,
) : FilterOutputStream(output) {
    private val crc = CRC32C()

    var size = 0L
        private set

    /** The CRC32C of everything written so far, as eight lowercase hex digits. */
    val crc32cHex: String get() = "%08x".format(crc.value)

    override fun write(b: Int) {
        out.write(b)
        crc.update(b)
        size++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        out.write(b, off, len)
        crc.update(b, off, len)
        size += len
    }
}

/** A pre-existing physical object is never owned by a new upload attempt. */
class LogDateBlobCollisionException : RuntimeException("The object path already exists")
