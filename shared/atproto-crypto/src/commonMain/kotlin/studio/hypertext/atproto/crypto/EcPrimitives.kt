package studio.hypertext.atproto.crypto

/**
 * The curve operations each platform's cryptography library provides: BouncyCastle on the JVM and
 * Android, OpenSSL on iOS.
 *
 * Keys cross this boundary as bytes. A private key is its 32-byte big-endian scalar; a public key is
 * its 33-byte SEC1 compressed point. Signatures are raw 64-byte `r || s`, in either S form.
 */
internal expect object EcPrimitives {
    /** A new private scalar from the platform's secure random source. */
    fun generatePrivateKey(curve: EcCurve): ByteArray

    /** The compressed public point of [privateKey]. */
    fun publicKey(
        curve: EcCurve,
        privateKey: ByteArray,
    ): ByteArray

    /**
     * Checks that [encoded], a SEC1 compressed or uncompressed point, is on [curve] and returns it
     * compressed.
     *
     * @throws IllegalArgumentException if it is not a point on the curve.
     */
    fun compressPublicKey(
        curve: EcCurve,
        encoded: ByteArray,
    ): ByteArray

    /** The 65-byte SEC1 uncompressed form of a compressed point. */
    fun decompressPublicKey(
        curve: EcCurve,
        compressed: ByteArray,
    ): ByteArray

    /** ECDSA over the SHA-256 hash of [payload]. */
    fun signSha256(
        curve: EcCurve,
        privateKey: ByteArray,
        payload: ByteArray,
    ): ByteArray

    /** Checks an ECDSA [signature] over the SHA-256 hash of [payload], accepting either S form. */
    fun verifySha256(
        curve: EcCurve,
        compressedPublicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean
}
