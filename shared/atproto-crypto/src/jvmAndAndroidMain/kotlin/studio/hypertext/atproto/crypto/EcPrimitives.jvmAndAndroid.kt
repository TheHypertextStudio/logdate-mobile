package studio.hypertext.atproto.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import java.math.BigInteger
import java.security.SecureRandom

/**
 * BouncyCastle's own elliptic-curve classes, not a `java.security` provider. Android ships a cut-down
 * provider under BouncyCastle's name that has no secp256k1, so going through `java.security` there
 * would fail for K-256.
 */
internal actual object EcPrimitives {
    actual fun generatePrivateKey(curve: EcCurve): ByteArray {
        val generator = ECKeyPairGenerator()
        generator.init(ECKeyGenerationParameters(domain(curve), SecureRandom()))
        val privateKey = generator.generateKeyPair().private as ECPrivateKeyParameters
        return privateKey.d.toFixedWidth(curve.coordinateBytes)
    }

    actual fun publicKey(
        curve: EcCurve,
        privateKey: ByteArray,
    ): ByteArray = FixedPointCombMultiplier().multiply(domain(curve).g, BigInteger(1, privateKey)).getEncoded(true)

    actual fun compressPublicKey(
        curve: EcCurve,
        encoded: ByteArray,
    ): ByteArray = decodePoint(curve, encoded).getEncoded(true)

    actual fun decompressPublicKey(
        curve: EcCurve,
        compressed: ByteArray,
    ): ByteArray = decodePoint(curve, compressed).getEncoded(false)

    actual fun signSha256(
        curve: EcCurve,
        privateKey: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        // RFC 6979: the nonce comes from the key and the message, so no random source can weaken it.
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, privateKey), domain(curve)))
        val (r, s) = signer.generateSignature(sha256(payload))
        return r.toFixedWidth(curve.coordinateBytes) + s.toFixedWidth(curve.coordinateBytes)
    }

    actual fun verifySha256(
        curve: EcCurve,
        compressedPublicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val width = curve.coordinateBytes
        val signer = ECDSASigner()
        signer.init(false, ECPublicKeyParameters(decodePoint(curve, compressedPublicKey), domain(curve)))
        return signer.verifySignature(
            sha256(payload),
            BigInteger(1, signature.copyOfRange(0, width)),
            BigInteger(1, signature.copyOfRange(width, signature.size)),
        )
    }

    private fun domain(curve: EcCurve): ECDomainParameters {
        val parameters =
            requireNotNull(CustomNamedCurves.getByName(curve.parameterSpecName)) {
                "Unsupported curve ${curve.parameterSpecName}"
            }
        return ECDomainParameters(parameters.curve, parameters.g, parameters.n, parameters.h)
    }

    /** Decoding checks the point is on the curve; the point at infinity is not a key. */
    private fun decodePoint(
        curve: EcCurve,
        encoded: ByteArray,
    ): ECPoint {
        val point =
            try {
                domain(curve).curve.decodePoint(encoded)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Not a point on ${curve.signingKeyAlgorithm}", error)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Not a point on ${curve.signingKeyAlgorithm}", error)
            }
        require(!point.isInfinity && point.isValid) { "Not a point on ${curve.signingKeyAlgorithm}" }
        return point
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(bytes, 0, bytes.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    private fun BigInteger.toFixedWidth(width: Int): ByteArray {
        val encoded = toByteArray()
        return when {
            encoded.size == width -> encoded
            encoded.size < width -> ByteArray(width - encoded.size) + encoded
            else -> encoded.copyOfRange(encoded.size - width, encoded.size)
        }
    }
}
