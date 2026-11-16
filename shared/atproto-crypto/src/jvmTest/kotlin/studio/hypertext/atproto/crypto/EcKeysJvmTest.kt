package studio.hypertext.atproto.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What only the JVM implementation promises: deterministic signatures, and agreement between the
 * multiplatform key types and the `java.security` ones.
 */
class EcKeysJvmTest {
    @Test
    fun `signing is deterministic and reproduces the published RFC 6979 signatures`() {
        val p256 = EcPrivateKey.fromByteArray(EcCurve.P256, hex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721"))
        val k256 = EcPrivateKey.fromByteArray(EcCurve.K256, ByteArray(32).also { it[31] = 1 })

        assertContentEquals(
            hex(
                "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716" +
                    "0834e36ad29a83bf2bc9385e491d6099c8fdf9d1ed67aa7ea5f51f93782857a9",
            ),
            p256.signSha256("sample".encodeToByteArray()),
        )
        assertContentEquals(
            hex(
                "934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d8" +
                    "2442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5",
            ),
            k256.signSha256("Satoshi Nakamoto".encodeToByteArray()),
        )
    }

    @Test
    fun `the curve orders match BouncyCastle's`() {
        EcCurve.entries.forEach { curve ->
            val order = CustomNamedCurves.getByName(curve.parameterSpecName).n

            assertEquals(order, java.math.BigInteger(1, EcScalars.order(curve)), curve.name)
        }
    }

    @Test
    fun `java security keys convert to the multiplatform keys and sign the same`() {
        val payload = "bridge".encodeToByteArray()

        EcCurve.entries.forEach { curve ->
            val keyPair = EcKeySupport.generateKeyPair(curve)
            val privateKey = EcKeySupport.toEcPrivateKey(keyPair.private, curve)
            val publicKey = EcKeySupport.toEcPublicKey(keyPair.public, curve)

            assertEquals(publicKey, privateKey.publicKey)
            assertEquals(
                publicKey.toMultikey(),
                EcKeySupport.encodePublicKeyMultibase(keyPair.public as java.security.interfaces.ECPublicKey, curve),
            )
            assertContentEquals(EcKeySupport.signSha256(keyPair.private, curve, payload), privateKey.signSha256(payload))
            assertTrue(EcKeySupport.verifySha256(keyPair.public, curve, payload, privateKey.signSha256(payload)))
        }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
