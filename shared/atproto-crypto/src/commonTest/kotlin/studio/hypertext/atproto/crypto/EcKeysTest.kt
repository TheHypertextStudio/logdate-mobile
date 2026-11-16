package studio.hypertext.atproto.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The multiplatform key types must behave the same on every platform: the same public key for a
 * private key, signatures every other platform accepts, and the AT Protocol's low-S rule.
 *
 * The fixed signatures here were made by other implementations (RFC 6979's P-256 example and a
 * widely used secp256k1 example), so passing on a platform shows it agrees with them, not only
 * with itself.
 */
class EcKeysTest {
    @Test
    fun `the private key one has the curve's base point as its public key`() {
        val one = ByteArray(32).also { it[31] = 1 }

        assertContentEquals(
            hex("036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"),
            EcPrivateKey.fromByteArray(EcCurve.P256, one).publicKey.toCompressedByteArray(),
        )
        assertContentEquals(
            hex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            EcPrivateKey.fromByteArray(EcCurve.K256, one).publicKey.toCompressedByteArray(),
        )
    }

    @Test
    fun `a P-256 key read from JWK coordinates verifies the RFC 6979 signature`() {
        val publicKey = EcPublicKey.fromJwkCoordinates(EcCurve.P256, hex(P256_X), hex(P256_Y))
        val payload = "sample".encodeToByteArray()
        // RFC 6979 publishes the high-S form; the AT Protocol's form of the same signature is (r, n - s).
        val highS = hex(P256_R + P256_HIGH_S)
        val lowS = hex(P256_R + P256_LOW_S)

        assertEquals(publicKey, EcPrivateKey.fromByteArray(EcCurve.P256, hex(P256_PRIVATE)).publicKey)
        assertContentEquals(hex("03$P256_X"), publicKey.toCompressedByteArray())
        assertContentEquals(hex("04$P256_X$P256_Y"), publicKey.toUncompressedByteArray())
        assertTrue(publicKey.verifySha256(payload, lowS))
        assertFalse(publicKey.verifySha256(payload, highS), "A high-S signature is refused by default")
        assertTrue(publicKey.verifySha256(payload, highS, allowHighS = true))
        assertFalse(publicKey.verifySha256("simple".encodeToByteArray(), lowS))
    }

    @Test
    fun `a K-256 key verifies a signature made by another implementation`() {
        val privateKey = EcPrivateKey.fromByteArray(EcCurve.K256, ByteArray(32).also { it[31] = 1 })
        val signature = hex(K256_SIGNATURE)

        assertTrue(EcSignatures.hasLowS(EcCurve.K256, signature))
        assertTrue(privateKey.publicKey.verifySha256("Satoshi Nakamoto".encodeToByteArray(), signature))
        assertFalse(privateKey.publicKey.verifySha256("Satoshi Nakamoto!".encodeToByteArray(), signature))
    }

    @Test
    fun `a generated key signs low-S signatures its public key verifies`() {
        val payload = "hello, atproto".encodeToByteArray()

        EcCurve.entries.forEach { curve ->
            val privateKey = EcPrivateKey.generate(curve)
            val restored = EcPrivateKey.fromByteArray(curve, privateKey.toByteArray())
            val signature = privateKey.signSha256(payload)

            assertEquals(64, signature.size)
            assertTrue(EcSignatures.hasLowS(curve, signature))
            assertEquals(privateKey.publicKey, restored.publicKey)
            assertTrue(restored.publicKey.verifySha256(payload, signature))
            assertFalse(restored.publicKey.verifySha256("tampered".encodeToByteArray(), signature))
            assertNotEquals(privateKey.publicKey, EcPrivateKey.generate(curve).publicKey)
        }
    }

    @Test
    fun `a public key round-trips through its multikey and keeps its curve`() {
        EcCurve.entries.forEach { curve ->
            val publicKey = EcPrivateKey.generate(curve).publicKey
            val decoded = EcPublicKey.fromMultikey(publicKey.toMultikey())

            assertEquals(curve, decoded.curve)
            assertEquals(publicKey, decoded)
            assertEquals(publicKey, EcPublicKey.fromCompressedByteArray(curve, publicKey.toCompressedByteArray()))
        }
    }

    @Test
    fun `a private key must be a scalar between one and the curve order`() {
        EcCurve.entries.forEach { curve ->
            assertFailsWith<IllegalArgumentException> { EcPrivateKey.fromByteArray(curve, ByteArray(32)) }
            assertFailsWith<IllegalArgumentException> { EcPrivateKey.fromByteArray(curve, ByteArray(32) { 0xff.toByte() }) }
            assertFailsWith<IllegalArgumentException> { EcPrivateKey.fromByteArray(curve, ByteArray(31) { 1 }) }
        }
    }

    @Test
    fun `bytes that are not a point on the curve are not a public key`() {
        EcCurve.entries.forEach { curve ->
            // An X coordinate of all ones is larger than the field, so no point has it.
            val outOfRange = byteArrayOf(0x02) + ByteArray(32) { 0xff.toByte() }
            assertFailsWith<IllegalArgumentException> { EcPublicKey.fromCompressedByteArray(curve, outOfRange) }
            assertFailsWith<IllegalArgumentException> { EcPublicKey.fromCompressedByteArray(curve, ByteArray(33)) }
            // (1, 1) satisfies neither curve's equation.
            val one = ByteArray(32).also { it[31] = 1 }
            assertFailsWith<IllegalArgumentException> { EcPublicKey.fromJwkCoordinates(curve, one, one) }
        }
        assertFailsWith<IllegalArgumentException> { EcPublicKey.fromMultikey("z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK") }
    }

    @Test
    fun `a signature of the wrong length is not valid`() {
        val privateKey = EcPrivateKey.generate(EcCurve.K256)
        val payload = "length".encodeToByteArray()
        val signature = privateKey.signSha256(payload)

        assertFalse(privateKey.publicKey.verifySha256(payload, signature.copyOf(63)))
        assertFalse(privateKey.publicKey.verifySha256(payload, signature + 0))
        assertFailsWith<IllegalArgumentException> { EcSignatures.hasLowS(EcCurve.K256, signature.copyOf(63)) }
    }

    @Test
    fun `a private key never prints its scalar`() {
        val privateKey = EcPrivateKey.generate(EcCurve.P256)

        assertEquals("EcPrivateKey(curve=P256)", privateKey.toString())
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        // RFC 6979, appendix A.2.5: P-256 with SHA-256 and the message "sample".
        const val P256_PRIVATE = "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721"
        const val P256_X = "60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6"
        const val P256_Y = "7903fe1008b8bc99a41ae9e95628bc64f2f1b20c2d7e9f5177a3c294d4462299"
        const val P256_R = "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716"
        const val P256_HIGH_S = "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8"

        // n - P256_HIGH_S for the P-256 order n.
        const val P256_LOW_S = "0834e36ad29a83bf2bc9385e491d6099c8fdf9d1ed67aa7ea5f51f93782857a9"

        // The private key one signing "Satoshi Nakamoto" with RFC 6979 nonces, low-S.
        const val K256_SIGNATURE =
            "934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d8" +
                "2442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5"
    }
}
