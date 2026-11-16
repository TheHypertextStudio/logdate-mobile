package studio.hypertext.atproto.crypto

/**
 * An elliptic-curve private key on one of the AT Protocol curves.
 *
 * The key is its 32-byte big-endian scalar, the same on every platform, so a key made or stored on
 * one device signs identically on another. Use [generate] for a new key and [fromByteArray] for a
 * stored one.
 */
public class EcPrivateKey private constructor(
    /** The curve this key belongs to. */
    public val curve: EcCurve,
    private val scalar: ByteArray,
) {
    /** The public key that verifies this key's signatures. */
    public val publicKey: EcPublicKey by lazy {
        EcPublicKey.fromCompressedByteArray(curve, EcPrimitives.publicKey(curve, scalar))
    }

    /** Returns a copy of the 32-byte big-endian scalar. Treat it as a secret. */
    public fun toByteArray(): ByteArray = scalar.copyOf()

    /**
     * Signs the SHA-256 hash of [payload] with ECDSA and returns the 64-byte `r || s` signature.
     *
     * The signature is always in the low-S form the AT Protocol requires; see
     * [EcPublicKey.verifySha256]. On the JVM and Android the signature is deterministic (RFC 6979),
     * so signing the same payload twice gives the same bytes. On iOS it uses a fresh random nonce,
     * so the bytes differ each time. Both verify everywhere.
     */
    public fun signSha256(payload: ByteArray): ByteArray = EcScalars.toLowS(curve, EcPrimitives.signSha256(curve, scalar, payload))

    /** Never includes the scalar. */
    override fun toString(): String = "EcPrivateKey(curve=$curve)"

    public companion object {
        /** Generates a new private key for [curve] from the platform's secure random source. */
        public fun generate(curve: EcCurve): EcPrivateKey = fromByteArray(curve, EcPrimitives.generatePrivateKey(curve))

        /**
         * Reads a private key from its 32-byte big-endian [scalar].
         *
         * @throws IllegalArgumentException if [scalar] is not 32 bytes, is zero, or is not below the
         *   curve order.
         */
        public fun fromByteArray(
            curve: EcCurve,
            scalar: ByteArray,
        ): EcPrivateKey {
            require(EcScalars.isValidPrivateKey(curve, scalar)) { "Not a valid ${curve.signingKeyAlgorithm} private key" }
            return EcPrivateKey(curve, scalar.copyOf())
        }
    }
}

/**
 * An elliptic-curve public key on one of the AT Protocol curves.
 *
 * Every instance holds a point that was checked to be on its curve, so a key built from untrusted
 * input is safe to verify with.
 */
public class EcPublicKey private constructor(
    /** The curve this key belongs to. */
    public val curve: EcCurve,
    private val compressed: ByteArray,
) {
    /** Returns the 33-byte SEC1 compressed point: `0x02` or `0x03`, then the X coordinate. */
    public fun toCompressedByteArray(): ByteArray = compressed.copyOf()

    /**
     * Returns the 65-byte SEC1 uncompressed point: `0x04`, then the X and Y coordinates. A JWK's `x`
     * and `y` members are bytes 1 to 32 and 33 to 64.
     */
    public fun toUncompressedByteArray(): ByteArray = EcPrimitives.decompressPublicKey(curve, compressed)

    /** Encodes the key as a multikey, the `z…` value of a DID document's `publicKeyMultibase`. */
    public fun toMultikey(): String =
        when (curve) {
            EcCurve.P256 -> Multikey.encodeP256PublicKey(compressed)
            EcCurve.K256 -> Multikey.encodeK256PublicKey(compressed)
        }

    /**
     * Checks a 64-byte `r || s` ECDSA [signature] over the SHA-256 hash of [payload].
     *
     * Every ECDSA signature `(r, s)` has a twin `(r, n - s)` that verifies just as well. The AT
     * Protocol accepts only the "low-S" form, where `s` is at most half the curve order, so that each
     * message has exactly one valid signature; repository commits and PLC operations must be
     * checked that way, and this function rejects high-S signatures by default. JOSE (JWTs, DPoP
     * proofs) does not require low-S, and browsers' Web Crypto produces either form, so callers
     * checking those pass [allowHighS] = true.
     *
     * A signature of the wrong length is never valid; it returns false.
     */
    public fun verifySha256(
        payload: ByteArray,
        signature: ByteArray,
        allowHighS: Boolean = false,
    ): Boolean {
        if (signature.size != curve.coordinateBytes * 2) return false
        if (!allowHighS && !EcScalars.hasLowS(curve, signature)) return false
        return EcPrimitives.verifySha256(curve, compressed, payload, signature)
    }

    override fun equals(other: Any?): Boolean = other is EcPublicKey && curve == other.curve && compressed.contentEquals(other.compressed)

    override fun hashCode(): Int = 31 * curve.hashCode() + compressed.contentHashCode()

    override fun toString(): String = "EcPublicKey(curve=$curve, multikey=${toMultikey()})"

    public companion object {
        /**
         * Reads a public key from its 33-byte SEC1 [compressed] point.
         *
         * @throws IllegalArgumentException if the bytes are not a point on [curve].
         */
        public fun fromCompressedByteArray(
            curve: EcCurve,
            compressed: ByteArray,
        ): EcPublicKey {
            require(compressed.size == COMPRESSED_BYTES && compressed[0] in COMPRESSED_PREFIXES) {
                "A compressed ${curve.signingKeyAlgorithm} public key is 33 bytes starting with 0x02 or 0x03"
            }
            return EcPublicKey(curve, EcPrimitives.compressPublicKey(curve, compressed))
        }

        /**
         * Reads a public key from a multikey, the `z…` value of a DID document's
         * `publicKeyMultibase`. The multikey's prefix says which curve the key is on.
         *
         * @throws IllegalArgumentException if [multikey] is not a P-256 or K-256 key.
         */
        public fun fromMultikey(multikey: String): EcPublicKey {
            val curve = requireNotNull(Multikey.curve(multikey)) { "Not a P-256 or K-256 multikey" }
            val prefix =
                when (curve) {
                    EcCurve.P256 -> Multikey.p256PublicKeyPrefix
                    EcCurve.K256 -> Multikey.k256PublicKeyPrefix
                }
            val decoded = Multikey.decode(multikey)
            return fromCompressedByteArray(curve, decoded.copyOfRange(prefix.size, decoded.size))
        }

        /**
         * Reads a public key from the [x] and [y] members of a JWK, each the base64url-decoded
         * big-endian coordinate.
         *
         * @throws IllegalArgumentException if the coordinates are not a point on [curve].
         */
        public fun fromJwkCoordinates(
            curve: EcCurve,
            x: ByteArray,
            y: ByteArray,
        ): EcPublicKey {
            val width = curve.coordinateBytes
            require(x.size == width && y.size == width) { "JWK coordinates for ${curve.jwkCurveName} are $width bytes each" }
            return EcPublicKey(curve, EcPrimitives.compressPublicKey(curve, byteArrayOf(UNCOMPRESSED_PREFIX) + x + y))
        }

        private const val COMPRESSED_BYTES: Int = 33
        private const val UNCOMPRESSED_PREFIX: Byte = 0x04
        private val COMPRESSED_PREFIXES: Set<Byte> = setOf(0x02, 0x03)
    }
}

/** Checks on raw `r || s` ECDSA signatures that need no key. */
public object EcSignatures {
    /**
     * Returns whether [signature], raw `r || s` for [curve], has an `s` no greater than half the
     * curve order. The AT Protocol accepts only these low-S signatures.
     *
     * @throws IllegalArgumentException if [signature] is not 64 bytes.
     */
    public fun hasLowS(
        curve: EcCurve,
        signature: ByteArray,
    ): Boolean {
        require(signature.size == curve.coordinateBytes * 2) { "Unexpected ECDSA signature size" }
        return EcScalars.hasLowS(curve, signature)
    }
}
