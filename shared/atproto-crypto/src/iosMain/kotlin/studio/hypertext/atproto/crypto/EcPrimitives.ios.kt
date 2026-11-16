package studio.hypertext.atproto.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.providers.openssl3.Openssl3

/**
 * OpenSSL, linked into the app. Apple's own cryptography (CryptoKit and Security) has P-256 but no
 * secp256k1, and the AT Protocol needs both, so one library handles both curves here.
 */
internal actual object EcPrimitives {
    private val ecdsa: ECDSA by lazy { CryptographyProvider.Openssl3.get(ECDSA) }

    actual fun generatePrivateKey(curve: EcCurve): ByteArray =
        ecdsa
            .keyPairGenerator(curve.providerCurve)
            .generateKeyBlocking()
            .privateKey
            .encodeToByteArrayBlocking(EC.PrivateKey.Format.RAW)

    actual fun publicKey(
        curve: EcCurve,
        privateKey: ByteArray,
    ): ByteArray =
        decodePrivateKey(curve, privateKey)
            .getPublicKeyBlocking()
            .encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Compressed)

    actual fun compressPublicKey(
        curve: EcCurve,
        encoded: ByteArray,
    ): ByteArray = decodePublicKey(curve, encoded).encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Compressed)

    actual fun decompressPublicKey(
        curve: EcCurve,
        compressed: ByteArray,
    ): ByteArray = decodePublicKey(curve, compressed).encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed)

    actual fun signSha256(
        curve: EcCurve,
        privateKey: ByteArray,
        payload: ByteArray,
    ): ByteArray =
        decodePrivateKey(curve, privateKey)
            .signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW)
            .generateSignatureBlocking(payload)

    actual fun verifySha256(
        curve: EcCurve,
        compressedPublicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean =
        decodePublicKey(curve, compressedPublicKey)
            .signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
            .tryVerifySignatureBlocking(payload, signature)

    private fun decodePrivateKey(
        curve: EcCurve,
        scalar: ByteArray,
    ): ECDSA.PrivateKey = ecdsa.privateKeyDecoder(curve.providerCurve).decodeFromByteArrayBlocking(EC.PrivateKey.Format.RAW, scalar)

    /** OpenSSL checks the point is on the curve when it reads the key. */
    private fun decodePublicKey(
        curve: EcCurve,
        encoded: ByteArray,
    ): ECDSA.PublicKey {
        val format =
            if (encoded.firstOrNull() == UNCOMPRESSED_PREFIX) EC.PublicKey.Format.RAW.Uncompressed else EC.PublicKey.Format.RAW.Compressed
        return runCatching { ecdsa.publicKeyDecoder(curve.providerCurve).decodeFromByteArrayBlocking(format, encoded) }
            .getOrElse { throw IllegalArgumentException("Not a point on ${curve.signingKeyAlgorithm}", it) }
    }

    private val EcCurve.providerCurve: EC.Curve
        get() =
            when (this) {
                EcCurve.P256 -> EC.Curve.P256
                EcCurve.K256 -> EC.Curve.secp256k1
            }

    private const val UNCOMPRESSED_PREFIX: Byte = 0x04
}
