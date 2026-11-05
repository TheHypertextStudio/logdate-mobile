package app.logdate.client.sync.space.adoption

import app.logdate.client.sync.crypto.AesGcmMediaPayloadCrypto
import app.logdate.space.adoption.IdentityAdoptionBlobInventory
import app.logdate.space.adoption.IdentityAdoptionException
import app.logdate.space.adoption.IdentityAdoptionFailure
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.operations.HashFunction
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import studio.hypertext.atproto.lexicon.data.Bytes
import kotlin.coroutines.cancellation.CancellationException

/** Expected ciphertext evidence from the authenticated adoption snapshot. */
data class IdentityAdoptionMediaEvidence(
    val cid: String,
    val size: Long,
    val digest: Bytes,
)

/** Authenticates both copies without retaining decrypted media. Ownership is checked by the host. */
object IdentityAdoptionMediaVerifier {
    suspend fun verify(
        expected: IdentityAdoptionMediaEvidence,
        mediaKey: ByteArray,
        source: () -> RawSource,
        target: () -> RawSource,
    ): IdentityAdoptionMediaEvidence =
        try {
            evidenceCheck(mediaKey.size == 32 && expected.size in 1..MAXIMUM_BYTES)
            IdentityAdoptionBlobInventory().use {
                it.append(expected.cid, expected.size, expected.digest)
                it.finish()
            }
            val capturedKey = mediaKey.copyOf()
            try {
                authenticate(expected, capturedKey, source)
                authenticate(expected, capturedKey, target)
                expected
            } finally {
                capturedKey.fill(0)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IdentityAdoptionException) {
            throw error
        } catch (_: Exception) {
            throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
        }

    private suspend fun authenticate(
        expected: IdentityAdoptionMediaEvidence,
        key: ByteArray,
        open: () -> RawSource,
    ) {
        CryptographyProvider.Default.get(SHA256).hasher().createHashFunction().use { hash ->
            val captured = HashingSource(open(), hash, expected.size)
            captured.buffered().use { input ->
                val header = Buffer()
                val peek = input.peek()
                while (header.size < 5) {
                    if (peek.readAtMostTo(header, 5 - header.size) == -1L) break
                }
                // Ordinary downloads permit cleartext; adoption requires authenticated ciphertext.
                evidenceCheck(header.readByteArray().decodeToString() in setOf("LDCE1", "LDCE2"))
                DiscardingSink().buffered().use { output ->
                    AesGcmMediaPayloadCrypto(key).streamDecryptor().decrypt(input, output)
                }
                evidenceCheck(input.exhausted() && captured.count == expected.size)
                evidenceCheck(Bytes(hash.hashToByteArray()) == expected.digest)
            }
        }
    }

    private const val MAXIMUM_BYTES = 100L * 1024 * 1024
}

/** Hashes exactly the same bytes the decoder reads, with a bounded extra read to detect overrun. */
private class HashingSource(
    private val delegate: RawSource,
    private val hash: HashFunction,
    private val expectedSize: Long,
) : RawSource {
    var count: Long = 0
        private set

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (byteCount == 0L) return 0
        val requested = minOf(byteCount, 64L * 1024, expectedSize - count + 1)
        val buffer = Buffer()
        val read = delegate.readAtMostTo(buffer, requested)
        if (read == -1L) return -1
        evidenceCheck(read in 1..requested && buffer.size == read && read <= expectedSize - count)
        val bytes = buffer.readByteArray()
        hash.update(bytes)
        sink.write(bytes)
        count += read
        return read
    }

    override fun close() = delegate.close()
}

private class DiscardingSink : RawSink {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) = source.skip(byteCount)

    override fun flush() = Unit

    override fun close() = Unit
}

private fun evidenceCheck(valid: Boolean) {
    if (!valid) throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
}
