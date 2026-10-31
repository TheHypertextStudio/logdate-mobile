package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAttestationResponse
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
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Proves the production-critical fix: strict WebAuthn verification accepts a passkey created
 * through the Android Credential Manager, whose `clientDataJSON.origin` is an
 * `android:apk-key-hash:<hash>` value rather than an `https://` URL.
 *
 * The ceremony here is built with webauthn4j's own serializers and a real EC P-256 attestation —
 * the same crypto a platform authenticator performs — so it passes strict verification only when
 * the origin is in the relying party's allowlist. Registration and authentication share the exact
 * same origin set, so covering registration covers the wiring for both.
 */
@OptIn(ExperimentalUuidApi::class)
class WebAuthnPasskeyServiceAndroidOriginTest {
    private val androidOrigin = "android:apk-key-hash:pNiP8Z6X1xH6vQX0r1Tq8m9Hb3kq9b0c0d1e2f3g4h5"
    private val webOrigin = "https://cloud-staging.logdate.app"
    private val rpId = "cloud-staging.logdate.app"

    @Test
    fun `strict mode accepts the native Apple RP origin without accepting unrelated subdomains`() =
        runBlocking<Unit> {
            val config =
                WebAuthnConfig.fromEnvironment(
                    relyingPartyId = "logdate.app",
                    relyingPartyName = "LogDate",
                    origin = "https://cloud.logdate.app",
                    allowedOrigins = androidOrigin,
                    serverOrigin = null,
                )
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    relyingPartyId = config.relyingPartyId,
                    origins = config.origins,
                    strictVerificationEnabled = true,
                )
            assertTrue(service.registerWithOrigin("https://logdate.app").success)
            assertFalse(service.registerWithOrigin("https://untrusted.logdate.app").success)
        }

    @Test
    fun `strict mode accepts a passkey whose origin is an allowlisted android apk-key-hash`() =
        runBlocking<Unit> {
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    relyingPartyId = rpId,
                    origins = setOf(webOrigin, androidOrigin),
                    strictVerificationEnabled = true,
                )

            val result = service.registerWithOrigin(origin = androidOrigin)

            assertTrue(result.success, "expected android-origin passkey to verify; error=${result.error}")
        }

    @Test
    fun `strict mode rejects an android origin that is not allowlisted`() =
        runBlocking<Unit> {
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    relyingPartyId = rpId,
                    // Only the web origin is allowed; the on-device android origin must be refused.
                    origins = setOf(webOrigin),
                    strictVerificationEnabled = true,
                )

            val result = service.registerWithOrigin(origin = androidOrigin)

            assertFalse(result.success, "expected non-allowlisted android origin to be rejected")
        }

    /**
     * Drives a full strict-mode registration against [service], producing a real EC P-256
     * attestation whose `clientDataJSON.origin` is [origin].
     */
    private suspend fun WebAuthnPasskeyService.registerWithOrigin(origin: String): WebAuthnPasskeyService.RegistrationResult {
        val userId = Uuid.random()
        val options = generateRegistrationOptions(userId, "android_user", "Android User")

        val keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
        val coseKey = EC2COSEKey.create(keyPair.public as ECPublicKey, COSEAlgorithmIdentifier.ES256)

        val credentialId = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val attestedCredentialData = AttestedCredentialData(AAGUID.ZERO, credentialId, coseKey)

        val flags =
            (
                AuthenticatorData.BIT_UP.toInt() or
                    AuthenticatorData.BIT_UV.toInt() or
                    AuthenticatorData.BIT_AT.toInt()
            ).toByte()
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(options.rpId.toByteArray())
        val authenticatorData =
            AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                rpIdHash,
                flags,
                0L,
                attestedCredentialData,
            )
        val attestationObject = AttestationObject(authenticatorData, NoneAttestationStatement())
        val attestationObjectBytes = AttestationObjectConverter(ObjectConverter()).convertToBytes(attestationObject)

        val clientDataJson =
            """{"type":"webauthn.create","challenge":"${options.challenge}","origin":"$origin","crossOrigin":false}"""

        val credentialIdB64 = base64Url(credentialId)
        return verifyRegistration(
            userId = userId,
            challenge = options.challenge,
            registrationResponse =
                PasskeyRegistrationResponse(
                    id = credentialIdB64,
                    rawId = credentialIdB64,
                    response =
                        AuthenticatorAttestationResponse(
                            clientDataJSON = base64Url(clientDataJson.toByteArray()),
                            attestationObject = base64Url(attestationObjectBytes),
                        ),
                ),
        )
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
