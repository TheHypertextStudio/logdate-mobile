package app.logdate.client.sync.space.adoption

import app.logdate.client.sync.crypto.AesGcmMediaPayloadCrypto
import app.logdate.client.sync.crypto.legacyLdce1Encrypt
import app.logdate.space.adoption.IdentityAdoptionException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.repo.Cid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentityAdoptionMediaVerifierTest {
    private val key = ByteArray(32) { 7 }

    @Test
    fun `both ciphertext copies authenticate and streams close without retaining plaintext`() =
        runTest {
            val bytes = encrypt(ByteArray(200_000) { (it % 251).toByte() })
            val expected = evidence(bytes)
            val source = MeasuredSource(bytes)
            val target = MeasuredSource(bytes)
            assertEquals(expected, IdentityAdoptionMediaVerifier.verify(expected, key, { source }, { target }))
            assertTrue(source.closed && target.closed)
            assertEquals(bytes.size.toLong(), source.read)
            assertEquals(bytes.size.toLong(), target.read)
            assertTrue(source.maximumRequest <= 256 * 1024)
        }

    @Test
    fun `media key is captured before either input can change the callers key buffer`() =
        runTest {
            val bytes = encrypt("synthetic captured key".encodeToByteArray())
            val expected = evidence(bytes)
            val providedKey = key.copyOf()
            val actual =
                IdentityAdoptionMediaVerifier.verify(expected, providedKey, {
                    providedKey.fill(0)
                    MeasuredSource(bytes)
                }, { MeasuredSource(bytes) })
            assertEquals(expected, actual)
            assertTrue(providedKey.all { it == 0.toByte() })
        }

    @Test
    fun `legacy authenticated media remains readable without producing a replacement ciphertext`() =
        runTest {
            val bytes = legacyLdce1Encrypt(key, "synthetic legacy media".encodeToByteArray())
            val expected = evidence(bytes)
            val actual = IdentityAdoptionMediaVerifier.verify(expected, key, { MeasuredSource(bytes) }, { MeasuredSource(bytes) })
            assertEquals(expected, actual)
        }

    @Test
    fun `correct ciphertext hash cannot substitute for authentication under the captured media key`() =
        runTest {
            val bytes = encrypt("synthetic".encodeToByteArray())
            assertFailsWith<IdentityAdoptionException> {
                IdentityAdoptionMediaVerifier.verify(
                    evidence(bytes),
                    ByteArray(32) { 8 },
                    { MeasuredSource(bytes) },
                    { MeasuredSource(bytes) },
                )
            }
        }

    @Test
    fun `authentic but different target ciphertext is refused`() =
        runTest {
            val bytes = encrypt("first".encodeToByteArray())
            val different = encrypt("other".encodeToByteArray())
            assertFailsWith<IdentityAdoptionException> {
                IdentityAdoptionMediaVerifier.verify(evidence(bytes), key, { MeasuredSource(bytes) }, { MeasuredSource(different) })
            }
        }

    @Test
    fun `cleartext and future formats refuse even when their ciphertext evidence matches`() =
        runTest {
            for (bytes in listOf("plain text".encodeToByteArray(), "LDCE9unsupported".encodeToByteArray())) {
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionMediaVerifier.verify(evidence(bytes), key, { MeasuredSource(bytes) }, { MeasuredSource(bytes) })
                }
            }
        }

    @Test
    fun `altered or truncated bytes refuse even with independently recomputed content identifiers`() =
        runTest {
            val bytes = encrypt(ByteArray(200_000) { 1 })
            val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            for (bad in listOf(damaged, bytes.copyOf(43 + 2 * (64 * 1024 + 16)))) {
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionMediaVerifier.verify(evidence(bad), key, { MeasuredSource(bad) }, { MeasuredSource(bad) })
                }
            }
        }

    @Test
    fun `size digest and identifier must all describe the exact ciphertext`() =
        runTest {
            val bytes = encrypt("synthetic".encodeToByteArray())
            val expected = evidence(bytes)
            for (bad in listOf(
                expected.copy(size = expected.size - 1),
                expected.copy(size = expected.size + 1),
                expected.copy(cid = "invalid"),
                expected.copy(digest = Bytes(ByteArray(32))),
            )) {
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionMediaVerifier.verify(bad, key, { MeasuredSource(bytes) }, { MeasuredSource(bytes) })
                }
            }
        }

    @Test
    fun `cancellation closes source and never opens target or becomes verification evidence`() =
        runTest {
            val bytes = encrypt("synthetic".encodeToByteArray())
            val source = MeasuredSource(bytes, cancel = true)
            var targetOpened = false
            assertFailsWith<CancellationException> {
                IdentityAdoptionMediaVerifier.verify(evidence(bytes), key, { source }, {
                    targetOpened = true
                    MeasuredSource(bytes)
                })
            }
            assertTrue(source.closed)
            assertEquals(false, targetOpened)
        }

    private suspend fun encrypt(bytes: ByteArray): ByteArray =
        AesGcmMediaPayloadCrypto(key)
            .streamEncryptor()
            .encrypt(Buffer().apply { write(bytes) })
            .buffered()
            .use { it.readByteArray() }

    private suspend fun evidence(bytes: ByteArray): IdentityAdoptionMediaEvidence {
        val digest =
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .hash(bytes)
        val cid = Cid.rawSha256(bytes).value
        return IdentityAdoptionMediaEvidence(cid, bytes.size.toLong(), Bytes(digest))
    }

    private class MeasuredSource(
        bytes: ByteArray,
        private val cancel: Boolean = false,
    ) : RawSource {
        private val source = Buffer().apply { write(bytes) }
        var closed = false
        var read = 0L
        var maximumRequest = 0L

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (cancel) throw CancellationException("Synthetic cancellation")
            maximumRequest = maxOf(maximumRequest, byteCount)
            return source.readAtMostTo(sink, byteCount).also { if (it > 0) read += it }
        }

        override fun close() {
            closed = true
            source.close()
        }
    }
}
