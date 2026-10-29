package app.logdate.server.oauth

import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAssertionAuthenticatorResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A platform authenticator that produces real, verifiable WebAuthn ceremonies so strict
 * verification in [WebAuthnPasskeyService] checks them exactly as it would a browser's.
 */
internal class PasskeyTestAuthenticator(
    private val rpId: String = "logdate.app",
    private val origin: String = "https://$rpId",
) {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val credentialId = ByteArray(32).also(SecureRandom()::nextBytes)
    private var signCount = 0

    val credentialIdB64: String get() = encode(credentialId)

    /** Registers this authenticator's passkey to [userId] through the real registration ceremony. */
    @OptIn(ExperimentalUuidApi::class)
    fun registerWith(
        service: WebAuthnPasskeyService,
        userId: Uuid,
        username: String,
    ) {
        val options = service.generateRegistrationOptions(userId, username, username)
        val result = service.verifyRegistration(userId, options.challenge, register(options.challenge))
        check(result.success) { "Test passkey registration failed: ${result.error}" }
    }

    fun register(challenge: String): PasskeyRegistrationResponse {
        val data =
            AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                hash(rpId.toByteArray()),
                (AuthenticatorData.BIT_UP.toInt() or AuthenticatorData.BIT_UV.toInt() or AuthenticatorData.BIT_AT.toInt()).toByte(),
                0L,
                AttestedCredentialData(
                    AAGUID.ZERO,
                    credentialId,
                    EC2COSEKey.create(key.public as ECPublicKey, COSEAlgorithmIdentifier.ES256),
                ),
            )
        val attestation = AttestationObjectConverter(ObjectConverter()).convertToBytes(AttestationObject(data, NoneAttestationStatement()))
        return PasskeyRegistrationResponse(
            credentialIdB64,
            credentialIdB64,
            AuthenticatorAttestationResponse(encode(clientData("webauthn.create", challenge)), encode(attestation)),
        )
    }

    /** Signs [challenge] the way `navigator.credentials.get` would on [origin]. */
    fun assert(
        challenge: String,
        tamperSignature: Boolean = false,
    ): PasskeyAssertionResponse {
        val clientData = clientData("webauthn.get", challenge)
        signCount += 1
        val flags = (AuthenticatorData.BIT_UP.toInt() or AuthenticatorData.BIT_UV.toInt()).toByte()
        val authenticatorData = hash(rpId.toByteArray()) + byteArrayOf(flags) + ByteBuffer.allocate(4).putInt(signCount).array()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(authenticatorData)
                update(hash(clientData))
                sign()
            }
        if (tamperSignature) signature[signature.size - 1] = (signature.last().toInt() xor 0x01).toByte()
        return PasskeyAssertionResponse(
            id = credentialIdB64,
            rawId = credentialIdB64,
            response =
                PasskeyAssertionAuthenticatorResponse(
                    clientDataJSON = encode(clientData),
                    authenticatorData = encode(authenticatorData),
                    signature = encode(signature),
                    userHandle = "",
                ),
        )
    }

    private fun clientData(
        type: String,
        challenge: String,
    ): ByteArray = """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()
}

private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
