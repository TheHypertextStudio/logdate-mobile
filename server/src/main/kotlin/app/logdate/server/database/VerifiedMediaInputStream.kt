package app.logdate.server.database

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Bounds the response and verifies its complete bytes before an EOF can be accepted. */
internal class VerifiedMediaInputStream(
    input: InputStream,
    private val size: Long,
    private val expectedHash: String?,
    private val complete: () -> Unit,
) : FilterInputStream(input) {
    private val hash = MessageDigest.getInstance("SHA-256")
    private var received = 0L
    private var finished = false

    override fun markSupported(): Boolean = false

    override fun reset(): Unit = error("Verified media streams cannot be rewound")

    override fun skip(n: Long): Long = 0

    override fun read(): Int {
        val bytes = ByteArray(1)
        return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (finished) return -1
        var count = `in`.read(bytes, offset, minOf(length.toLong(), size - received + 1).toInt())
        if (count == 0) {
            val byte = `in`.read()
            if (byte < 0) {
                count = -1
            } else {
                bytes[offset] = byte.toByte()
                count = 1
            }
        }
        if (count < 0) {
            check(received == size) { "The media stream ended early" }
            val actual = HexFormat.of().formatHex(hash.digest())
            check(expectedHash == null || actual == expectedHash) { "The media stream digest changed" }
            complete()
            finished = true
            return -1
        }
        received += count
        check(received <= size) { "The media stream exceeded its declared size" }
        hash.update(bytes, offset, count)
        return count
    }
}
