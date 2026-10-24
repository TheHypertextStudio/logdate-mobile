package app.logdate.integration.e2e.fixtures

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * A WebAuthn authenticator good enough for the relying party to actually verify.
 *
 * The suite previously posted placeholder strings for `clientDataJSON` and
 * `attestationObject`. Those only survive the in-memory repositories, so the harness could
 * never be pointed at a real database and the Postgres/MST path went untested. This performs
 * the real ceremony - P-256 keypair, CBOR `none` attestation, ECDSA assertions - so webauthn4j
 * verifies it exactly as it would a platform authenticator.
 *
 * User verification is asserted rather than performed; this is a test authenticator.
 */
internal class TestAuthenticator(
    private val rpId: String,
    val credentialId: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
) {
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private var signCount = 0

    val credentialIdB64: String get() = credentialId.b64u()

    /** Origin the relying party will compare against; derived from the rpId it advertised. */
    val origin: String get() = "https://$rpId"

    fun register(challenge: String): Pair<String, String> {
        val clientData = clientDataJson("webauthn.create", challenge)
        val authData = authenticatorData(includeAttestedCredential = true)
        return clientData.b64u() to attestationObject(authData).b64u()
    }

    fun assert(challenge: String): Triple<String, String, String> {
        val clientData = clientDataJson("webauthn.get", challenge)
        val authData = authenticatorData(includeAttestedCredential = false)
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(authData)
                update(sha256(clientData))
                sign()
            }
        return Triple(clientData.b64u(), authData.b64u(), signature.b64u())
    }

    private fun clientDataJson(
        type: String,
        challenge: String,
    ): ByteArray =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}"""
            .toByteArray(Charsets.UTF_8)

    private fun authenticatorData(includeAttestedCredential: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
        // UP | UV, plus AT when attested credential data follows.
        var flags = 0x01 or 0x04
        if (includeAttestedCredential) flags = flags or 0x40
        out.write(flags)
        signCount += 1
        out.write(
            byteArrayOf(
                (signCount ushr 24).toByte(),
                (signCount ushr 16).toByte(),
                (signCount ushr 8).toByte(),
                signCount.toByte(),
            ),
        )
        if (includeAttestedCredential) {
            out.write(ByteArray(16)) // zero AAGUID
            out.write(byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()))
            out.write(credentialId)
            out.write(coseKey(keyPair.public as ECPublicKey))
        }
        return out.toByteArray()
    }

    private fun attestationObject(authData: ByteArray): ByteArray =
        cborMap(
            listOf(
                cborText("fmt") to cborText("none"),
                cborText("attStmt") to cborMap(emptyList()),
                cborText("authData") to cborBytes(authData),
            ),
        )

    private fun coseKey(publicKey: ECPublicKey): ByteArray =
        cborMap(
            listOf(
                cborUInt(1) to cborUInt(2), // kty: EC2
                cborUInt(3) to cborNInt(7), // alg: ES256 (-7)
                cborNInt(1) to cborUInt(1), // crv: P-256
                cborNInt(2) to cborBytes(coordinate(publicKey.w.affineX.toByteArray())),
                cborNInt(3) to cborBytes(coordinate(publicKey.w.affineY.toByteArray())),
            ),
        )

    /** BigInteger.toByteArray may add a sign byte or drop leading zeros; COSE wants exactly 32. */
    private fun coordinate(raw: ByteArray): ByteArray =
        when {
            raw.size == COORDINATE_SIZE -> raw
            raw.size > COORDINATE_SIZE -> raw.copyOfRange(raw.size - COORDINATE_SIZE, raw.size)
            else -> ByteArray(COORDINATE_SIZE - raw.size) + raw
        }

    private companion object {
        const val COORDINATE_SIZE = 32
    }
}

private fun ByteArray.b64u(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

// The slice of CBOR WebAuthn attestation needs: definite-length maps, byte and text strings,
// and small integers. A full CBOR dependency is not worth it for four value types.
private fun cborHeader(
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

private fun cborUInt(value: Long): ByteArray = cborHeader(0, value)

/** Encodes `-magnitude`; CBOR stores negative n as major type 1 with argument `-1 - n`. */
private fun cborNInt(magnitude: Long): ByteArray = cborHeader(1, magnitude - 1)

private fun cborBytes(value: ByteArray): ByteArray = cborHeader(2, value.size.toLong()) + value

private fun cborText(value: String): ByteArray {
    val raw = value.toByteArray(Charsets.UTF_8)
    return cborHeader(3, raw.size.toLong()) + raw
}

private fun cborMap(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(cborHeader(5, entries.size.toLong()))
    entries.forEach { (k, v) ->
        out.write(k)
        out.write(v)
    }
    return out.toByteArray()
}
