package app.logdate.tools.passkeyprovider

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/** Base64url without padding, the encoding every WebAuthn JSON field uses. */
fun ByteArray.b64u(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

fun String.unb64u(): ByteArray = Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

/**
 * The tiny slice of CBOR that WebAuthn attestation needs: definite-length maps, byte strings, text
 * strings, and small integers. Pulling in a full CBOR library for four value types is not worth it.
 */
object Cbor {
    private fun header(
        major: Int,
        value: Long,
    ): ByteArray {
        val mt = major shl 5
        return when {
            value < 24 -> {
                byteArrayOf((mt or value.toInt()).toByte())
            }

            value < 256 -> {
                byteArrayOf((mt or 24).toByte(), value.toByte())
            }

            value < 65536 -> {
                byteArrayOf((mt or 25).toByte(), (value shr 8).toByte(), value.toByte())
            }

            else -> {
                byteArrayOf(
                    (mt or 26).toByte(),
                    (value shr 24).toByte(),
                    (value shr 16).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
            }
        }
    }

    fun uint(value: Long): ByteArray = header(0, value)

    /**
     * Encodes the negative integer `-magnitude`, the form COSE uses for its alg and crv labels.
     *
     * CBOR stores a negative integer n as major type 1 with argument `-1 - n`, so `-7` is written
     * with argument 6 - hence `magnitude - 1`.
     */
    fun nint(magnitude: Long): ByteArray = header(1, magnitude - 1)

    fun bytes(value: ByteArray): ByteArray = header(2, value.size.toLong()) + value

    fun text(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        return header(3, raw.size.toLong()) + raw
    }

    fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(5, entries.size.toLong()))
        entries.forEach { (k, v) ->
            out.write(k)
            out.write(v)
        }
        return out.toByteArray()
    }
}

object WebAuthnCrypto {
    private const val AAGUID_SIZE = 16
    private const val FLAG_USER_PRESENT = 0x01
    private const val FLAG_USER_VERIFIED = 0x04
    private const val FLAG_ATTESTED_DATA = 0x40

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun privateKeyFrom(pkcs8: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey

    /** COSE_Key for an ES256 P-256 public key: kty=EC2, alg=ES256, crv=P-256, plus x/y. */
    fun coseKey(publicKey: ECPublicKey): ByteArray {
        val x = fixedWidth(publicKey.w.affineX.toByteArray())
        val y = fixedWidth(publicKey.w.affineY.toByteArray())
        return Cbor.map(
            listOf(
                Cbor.uint(1) to Cbor.uint(2), // kty: EC2
                Cbor.uint(3) to Cbor.nint(7), // alg: ES256 (-7)
                Cbor.nint(1) to Cbor.uint(1), // crv: P-256
                Cbor.nint(2) to Cbor.bytes(x),
                Cbor.nint(3) to Cbor.bytes(y),
            ),
        )
    }

    /** BigInteger.toByteArray may add a sign byte or drop leading zeros; COSE wants exactly 32. */
    private fun fixedWidth(
        raw: ByteArray,
        width: Int = 32,
    ): ByteArray =
        when {
            raw.size == width -> raw
            raw.size > width -> raw.copyOfRange(raw.size - width, raw.size)
            else -> ByteArray(width - raw.size) + raw
        }

    fun authenticatorData(
        rpId: String,
        signCount: Int,
        credentialId: ByteArray? = null,
        publicKey: ECPublicKey? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
        var flags = FLAG_USER_PRESENT or FLAG_USER_VERIFIED
        if (credentialId != null) flags = flags or FLAG_ATTESTED_DATA
        out.write(flags)
        out.write(
            byteArrayOf(
                (signCount ushr 24).toByte(),
                (signCount ushr 16).toByte(),
                (signCount ushr 8).toByte(),
                signCount.toByte(),
            ),
        )
        if (credentialId != null && publicKey != null) {
            out.write(ByteArray(AAGUID_SIZE))
            out.write(byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()))
            out.write(credentialId)
            out.write(coseKey(publicKey))
        }
        return out.toByteArray()
    }

    /** "none" attestation - the format a platform authenticator uses when it makes no claims. */
    fun attestationObject(authData: ByteArray): ByteArray =
        Cbor.map(
            listOf(
                Cbor.text("fmt") to Cbor.text("none"),
                Cbor.text("attStmt") to Cbor.map(emptyList()),
                Cbor.text("authData") to Cbor.bytes(authData),
            ),
        )

    fun sign(
        privateKey: ECPrivateKey,
        authData: ByteArray,
        clientDataJson: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(authData)
            update(sha256(clientDataJson))
            sign()
        }
}
