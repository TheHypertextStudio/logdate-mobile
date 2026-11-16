package studio.hypertext.atproto.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * `java.security` key types for the AT Protocol curves, for JVM code that stores keys as PKCS#8 or
 * hands them to other `java.security` APIs.
 *
 * Signing and verification are done by [EcPrivateKey] and [EcPublicKey], which work on every
 * platform; [toEcPrivateKey] and [toEcPublicKey] convert to them.
 */
public object EcKeySupport {
    init {
        if (Security.getProvider(BOUNCY_CASTLE_PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generates a new EC [KeyPair] for [curve].
     */
    public fun generateKeyPair(
        curve: EcCurve,
        secureRandom: SecureRandom = SecureRandom(),
    ): KeyPair =
        KeyPairGenerator
            .getInstance(KEY_ALGORITHM, BOUNCY_CASTLE_PROVIDER_NAME)
            .apply { initialize(ECGenParameterSpec(curve.parameterSpecName), secureRandom) }
            .generateKeyPair()

    /**
     * Decodes a PKCS#8 EC private key.
     */
    public fun decodePrivateKey(pkcs8: ByteArray): PrivateKey =
        KeyFactory
            .getInstance(KEY_ALGORITHM, BOUNCY_CASTLE_PROVIDER_NAME)
            .generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /**
     * Converts a `java.security` EC [privateKey] on [curve] to the multiplatform key type.
     */
    public fun toEcPrivateKey(
        privateKey: PrivateKey,
        curve: EcCurve,
    ): EcPrivateKey = EcPrivateKey.fromByteArray(curve, (privateKey as ECPrivateKey).s.toFixedWidth(curve.coordinateBytes))

    /**
     * Converts a `java.security` EC [publicKey] on [curve] to the multiplatform key type.
     */
    public fun toEcPublicKey(
        publicKey: PublicKey,
        curve: EcCurve,
    ): EcPublicKey {
        val point = (publicKey as ECPublicKey).w
        return EcPublicKey.fromJwkCoordinates(
            curve = curve,
            x = point.affineX.toFixedWidth(curve.coordinateBytes),
            y = point.affineY.toFixedWidth(curve.coordinateBytes),
        )
    }

    /**
     * Encodes [publicKey] to an AT Protocol multikey value for [curve].
     */
    public fun encodePublicKeyMultibase(
        publicKey: ECPublicKey,
        curve: EcCurve,
    ): String = toEcPublicKey(publicKey, curve).toMultikey()

    /**
     * Decodes an EC public key from [multibase].
     */
    public fun decodePublicKey(multibase: String): DecodedEcPublicKey {
        val key =
            try {
                EcPublicKey.fromMultikey(multibase)
            } catch (error: IllegalArgumentException) {
                error("Unsupported EC multikey: ${error.message}")
            }
        val uncompressed = key.toUncompressedByteArray()
        val width = key.curve.coordinateBytes
        return DecodedEcPublicKey(
            curve = key.curve,
            publicKey =
                decodePublicKeyFromJwk(
                    curve = key.curve,
                    x = uncompressed.copyOfRange(1, 1 + width),
                    y = uncompressed.copyOfRange(1 + width, uncompressed.size),
                ),
        )
    }

    /**
     * Decodes an EC public key from JWK coordinates for [curve].
     */
    public fun decodePublicKeyFromJwk(
        curve: EcCurve,
        x: ByteArray,
        y: ByteArray,
    ): ECPublicKey {
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        return KeyFactory
            .getInstance(KEY_ALGORITHM, BOUNCY_CASTLE_PROVIDER_NAME)
            .generatePublic(ECPublicKeySpec(point, parameters(curve))) as ECPublicKey
    }

    /**
     * Signs [payload] with SHA-256 and deterministic ECDSA for [curve]. The signature is low-S.
     */
    public fun signSha256(
        privateKey: PrivateKey,
        curve: EcCurve,
        payload: ByteArray,
    ): ByteArray = toEcPrivateKey(privateKey, curve).signSha256(payload)

    /**
     * Verifies a SHA-256 ECDSA signature for [payload].
     *
     * High-S signatures are rejected unless [allowHighS] is true; [EcPublicKey.verifySha256] explains
     * when to allow them. A [publicKey] that is not a point on [curve] verifies nothing.
     */
    public fun verifySha256(
        publicKey: PublicKey,
        curve: EcCurve,
        payload: ByteArray,
        signature: ByteArray,
        allowHighS: Boolean = false,
    ): Boolean {
        require(signature.size == curve.coordinateBytes * 2) { "Unexpected ECDSA signature size" }
        // A key that is not a point on the curve cannot have signed anything.
        val key = runCatching { toEcPublicKey(publicKey, curve) }.getOrNull() ?: return false
        return key.verifySha256(payload, signature, allowHighS)
    }

    /**
     * Returns whether [signature], raw `r || s` for [curve], has an `s` no greater than half the
     * curve order. The AT Protocol accepts only these low-S signatures.
     */
    public fun hasLowS(
        signature: ByteArray,
        curve: EcCurve,
    ): Boolean = EcSignatures.hasLowS(curve, signature)

    private fun parameters(curve: EcCurve): ECParameterSpec =
        AlgorithmParameters
            .getInstance(KEY_ALGORITHM, BOUNCY_CASTLE_PROVIDER_NAME)
            .apply { init(ECGenParameterSpec(curve.parameterSpecName)) }
            .getParameterSpec(ECParameterSpec::class.java)

    private fun BigInteger.toFixedWidth(width: Int): ByteArray {
        val encoded = toByteArray()
        return when {
            encoded.size == width -> encoded
            encoded.size < width -> ByteArray(width - encoded.size) + encoded
            else -> encoded.copyOfRange(encoded.size - width, encoded.size)
        }
    }

    private const val KEY_ALGORITHM: String = "EC"
    private const val BOUNCY_CASTLE_PROVIDER_NAME: String = "BC"
}

/**
 * Decoded EC public key plus the AT Protocol curve it uses.
 */
public data class DecodedEcPublicKey(
    val curve: EcCurve,
    val publicKey: ECPublicKey,
)
