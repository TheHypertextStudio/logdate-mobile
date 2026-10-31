package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyRegistrationResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Validates the strict verification behavior of the WebAuthn passkey service.
 *
 * This suite specifically tests the "strict mode" configuration, ensuring that the service
 * rigorously enforces Base64URL encoding requirements and protocol-compliant payloads
 * for both registration and authentication, while allowing for more relaxed behavior
 * during development or testing when strict mode is disabled.
 */
@OptIn(ExperimentalUuidApi::class)
class WebAuthnPasskeyServiceStrictModeTest {
    @Test
    fun `registration options advertise ES256 so standards-conforming clients can prepare credentials`() =
        runBlocking<Unit> {
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    strictVerificationEnabled = true,
                )

            val options = service.generateRegistrationOptions(Uuid.random(), "es256_user", "ES256 User")
            val parameters =
                Json
                    .encodeToJsonElement(PasskeyRegistrationOptions.serializer(), options)
                    .jsonObject["pubKeyCredParams"]
                    ?.jsonArray

            assertTrue(
                parameters?.any { parameter ->
                    val fields = parameter.jsonObject
                    fields["type"]?.jsonPrimitive?.content == "public-key" &&
                        fields["alg"]?.jsonPrimitive?.content == "-7"
                } == true,
                "Production registration options must advertise the ES256 algorithm",
            )
        }

    @Test
    fun `strict mode rejects non base64url registration payloads`() =
        runBlocking<Unit> {
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    strictVerificationEnabled = true,
                )
            val userId = Uuid.random()
            val options = service.generateRegistrationOptions(userId, "strict_user", "Strict User")

            val result =
                service.verifyRegistration(
                    userId = userId,
                    challenge = options.challenge,
                    registrationResponse =
                        PasskeyRegistrationResponse(
                            id = "not-base64",
                            rawId = "not-base64",
                            response =
                                AuthenticatorAttestationResponse(
                                    clientDataJSON = "not-base64",
                                    attestationObject = "not-base64",
                                ),
                        ),
                )

            assertFalse(result.success)
            assertNotNull(result.error)
        }

    @Test
    fun `strict mode rejects non base64url authentication payloads`() =
        runBlocking<Unit> {
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    strictVerificationEnabled = true,
                )

            val result =
                service.verifyAuthentication(
                    challenge = "not-base64",
                    authenticationResponse =
                        PasskeyAuthenticationResponse(
                            id = "not-base64",
                            rawId = "not-base64",
                            response =
                                AuthenticatorAssertionResponse(
                                    clientDataJSON = "not-base64",
                                    authenticatorData = "not-base64",
                                    signature = "not-base64",
                                    userHandle = null,
                                ),
                        ),
                )

            assertFalse(result.success)
            assertNotNull(result.error)
        }

    @Test
    fun `non strict mode accepts simplified registration payloads for test flows`() =
        runBlocking<Unit> {
            val service =
                WebAuthnPasskeyService(
                    passkeyRepository = InMemoryPasskeyRepository(),
                    strictVerificationEnabled = false,
                )
            val userId = Uuid.random()
            val options = service.generateRegistrationOptions(userId, "legacy_user", "Legacy User")

            val result =
                service.verifyRegistration(
                    userId = userId,
                    challenge = options.challenge,
                    registrationResponse =
                        PasskeyRegistrationResponse(
                            id = "test-credential",
                            rawId = "test-credential",
                            response =
                                AuthenticatorAttestationResponse(
                                    clientDataJSON = "test-client-data",
                                    attestationObject = "test-attestation",
                                ),
                        ),
                )

            assertTrue(result.success)
            assertNotNull(result.credentialId)
        }
}
