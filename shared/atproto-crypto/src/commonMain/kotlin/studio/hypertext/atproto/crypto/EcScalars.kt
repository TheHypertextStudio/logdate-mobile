package studio.hypertext.atproto.crypto

/**
 * Arithmetic on 32-byte big-endian scalars against a curve's order `n`.
 *
 * This covers only what is public about a signature or checked once about a key: comparing with
 * `n` and `n / 2`, and replacing `s` with `n - s`. The secret-dependent curve arithmetic is done by
 * each platform's cryptography library.
 */
internal object EcScalars {
    private val p256Order = hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
    private val k256Order = hex("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")
    private val p256HalfOrder = half(p256Order)
    private val k256HalfOrder = half(k256Order)

    /** The order `n` of [curve]'s base point. */
    fun order(curve: EcCurve): ByteArray =
        when (curve) {
            EcCurve.P256 -> p256Order
            EcCurve.K256 -> k256Order
        }

    private fun halfOrder(curve: EcCurve): ByteArray =
        when (curve) {
            EcCurve.P256 -> p256HalfOrder
            EcCurve.K256 -> k256HalfOrder
        }

    /** A private key is a scalar `d` with `0 < d < n`. */
    fun isValidPrivateKey(
        curve: EcCurve,
        scalar: ByteArray,
    ): Boolean = scalar.size == curve.coordinateBytes && scalar.any { it != ZERO } && compare(scalar, order(curve)) < 0

    /** Whether the `s` half of a raw `r || s` [signature] is at most `n / 2`. */
    fun hasLowS(
        curve: EcCurve,
        signature: ByteArray,
    ): Boolean = compare(signature.copyOfRange(curve.coordinateBytes, signature.size), halfOrder(curve)) <= 0

    /** Returns [signature] with a high `s` replaced by `n - s`, which signs the same message. */
    fun toLowS(
        curve: EcCurve,
        signature: ByteArray,
    ): ByteArray {
        val width = curve.coordinateBytes
        check(signature.size == width * 2) { "Unexpected ECDSA signature size ${signature.size}" }
        if (hasLowS(curve, signature)) return signature
        return signature.copyOfRange(0, width) + subtract(order(curve), signature.copyOfRange(width, signature.size))
    }

    /** Compares two equal-length big-endian unsigned integers. */
    private fun compare(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        for (index in left.indices) {
            val difference = (left[index].toInt() and BYTE_MASK) - (right[index].toInt() and BYTE_MASK)
            if (difference != 0) return difference
        }
        return 0
    }

    /** `minuend - subtrahend` for equal-length big-endian unsigned integers with `minuend >= subtrahend`. */
    private fun subtract(
        minuend: ByteArray,
        subtrahend: ByteArray,
    ): ByteArray {
        val result = ByteArray(minuend.size)
        var borrow = 0
        for (index in minuend.indices.reversed()) {
            val difference = (minuend[index].toInt() and BYTE_MASK) - (subtrahend[index].toInt() and BYTE_MASK) - borrow
            borrow = if (difference < 0) 1 else 0
            result[index] = (difference + (borrow shl BITS_PER_BYTE)).toByte()
        }
        return result
    }

    /** `value / 2` for a big-endian unsigned integer. */
    private fun half(value: ByteArray): ByteArray {
        val result = ByteArray(value.size)
        var carry = 0
        for (index in value.indices) {
            val byte = value[index].toInt() and BYTE_MASK
            result[index] = ((byte shr 1) or (carry shl (BITS_PER_BYTE - 1))).toByte()
            carry = byte and 1
        }
        return result
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }

    private const val ZERO: Byte = 0
    private const val BYTE_MASK: Int = 0xff
    private const val BITS_PER_BYTE: Int = 8
    private const val HEX_RADIX: Int = 16
}
