package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationOptions
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
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class PassiveRestoreCredentialTest {
    @Test
    fun `strict restore accepts fresh assertions and rejects exact challenge replay`() =
        runBlocking<Unit> {
            val (service, authenticator) = registeredRestore()
            val challenge = service.generateAuthOptions().challenge
            assertTrue(service.verifyAuthentication(challenge, authenticator.assertion(challenge)).success)
            val nextChallenge = service.generateAuthOptions().challenge
            assertTrue(service.verifyAuthentication(nextChallenge, authenticator.assertion(nextChallenge)).success)
            assertFalse(service.verifyAuthentication(challenge, authenticator.assertion(challenge)).success)
        }

    @Test
    fun `positive counters must advance on fresh challenges`() =
        runBlocking<Unit> {
            val (service, authenticator) = registeredRestore()
            val first = service.generateAuthOptions().challenge
            assertTrue(service.verifyAuthentication(first, authenticator.assertion(first, signCount = 1)).success)
            val stale = service.generateAuthOptions().challenge
            assertFalse(service.verifyAuthentication(stale, authenticator.assertion(stale, signCount = 1)).success)
            val next = service.generateAuthOptions().challenge
            assertTrue(service.verifyAuthentication(next, authenticator.assertion(next, signCount = 2)).success)
        }

    @Test
    fun `passive restore still rejects a modified signature`() =
        runBlocking<Unit> {
            val (service, authenticator) = registeredRestore()
            val challenge = service.generateAuthOptions().challenge
            val assertion = authenticator.assertion(challenge)
            assertFalse(
                service
                    .verifyAuthentication(
                        challenge,
                        assertion.copy(response = assertion.response.copy(signature = encode(byteArrayOf(1, 2, 3)))),
                    ).success,
            )
        }

    @Test
    fun `passive restore still checks origin and challenge binding`() =
        runBlocking<Unit> {
            for (wrongOrigin in listOf(false, true)) {
                val (service, authenticator) = registeredRestore()
                val challenge = service.generateAuthOptions().challenge
                val assertion =
                    authenticator.assertion(
                        if (wrongOrigin) challenge else encode(byteArrayOf(42)),
                        if (wrongOrigin) "https://other.example" else ORIGIN,
                    )
                assertFalse(service.verifyAuthentication(challenge, assertion).success)
            }
        }

    private suspend fun registeredRestore(): Pair<RestoreCredentialService, PassiveRestoreAuthenticator> {
        val service =
            RestoreCredentialService(
                InMemoryRestoreCredentialRepository(),
                origins = setOf(ORIGIN),
                strictVerificationEnabled = true,
            )
        val authenticator = PassiveRestoreAuthenticator()
        val owner = Uuid.random()
        val options = service.generateRegistrationOptions(owner, "fixture", "Fixture")
        assertTrue(service.verifyRegistration(owner, options.challenge, authenticator.registration(options)).success)
        return service to authenticator
    }
}

internal class PassiveRestoreAuthenticator(
    idSeed: Int = 1,
) {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val credentialId = ByteArray(32) { (it + idSeed).toByte() }

    fun registration(options: PasskeyRegistrationOptions): PasskeyRegistrationResponse {
        val data =
            AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                hash(options.rpId.toByteArray()),
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
            encode(credentialId),
            encode(credentialId),
            AuthenticatorAttestationResponse(encode(clientData("webauthn.create", options.challenge, ORIGIN)), encode(attestation)),
        )
    }

    fun assertion(
        challenge: String,
        origin: String = ORIGIN,
        signCount: Int = 0,
    ): PasskeyAuthenticationResponse {
        val clientData = clientData("webauthn.get", challenge, origin)
        // Passive restore provides no fresh user-presence or biometric ceremony.
        val authenticatorData = hash("logdate.app".toByteArray()) + byteArrayOf(0) + ByteBuffer.allocate(4).putInt(signCount).array()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(authenticatorData)
                update(hash(clientData))
                sign()
            }
        return PasskeyAuthenticationResponse(
            encode(credentialId),
            encode(credentialId),
            AuthenticatorAssertionResponse(encode(clientData), encode(authenticatorData), encode(signature), null),
        )
    }

    private fun clientData(
        type: String,
        challenge: String,
        origin: String,
    ): ByteArray = """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()
}

private const val ORIGIN = "https://logdate.app"

private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
