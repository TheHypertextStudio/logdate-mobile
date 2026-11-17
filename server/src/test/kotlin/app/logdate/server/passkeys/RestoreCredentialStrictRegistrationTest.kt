package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class RestoreCredentialStrictRegistrationTest {
    // -----------------------------------------------------------------------
    // Strict mode — registration
    // -----------------------------------------------------------------------

    @Test
    fun `strict registration validates base64url encoding of fields`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            val userId = Uuid.random()

            // Challenge missing entirely
            val missingChallenge =
                service.verifyRegistration(
                    userId,
                    "missing-challenge",
                    PasskeyRegistrationResponse("id", "id", AuthenticatorAttestationResponse("x", "y")),
                )
            assertFalse(missingChallenge.success)

            // Challenge present but not valid base64url
            putChallenge(service, "not*base64", challenge(userId, "restore-registration"))
            val invalidChallenge =
                service.verifyRegistration(
                    userId,
                    "not*base64",
                    PasskeyRegistrationResponse("id", "id", AuthenticatorAttestationResponse("x", "y")),
                )
            assertFalse(invalidChallenge.success)
            assertContains(invalidChallenge.error.orEmpty(), "Challenge is not valid base64url")

            // Valid challenge, invalid attestation object
            val opts = service.generateRegistrationOptions(userId, "u", "U")
            val invalidAtt =
                service.verifyRegistration(
                    userId,
                    opts.challenge,
                    PasskeyRegistrationResponse(
                        id = "id",
                        rawId = "id",
                        response =
                            AuthenticatorAttestationResponse(
                                clientDataJSON = encodeBase64Url("cd".encodeToByteArray()),
                                attestationObject = "*invalid*",
                            ),
                    ),
                )
            assertFalse(invalidAtt.success)
            assertContains(invalidAtt.error.orEmpty(), "Attestation object is not valid base64url")

            // Valid challenge, invalid clientDataJSON
            val opts2 = service.generateRegistrationOptions(userId, "u2", "U2")
            val invalidCd =
                service.verifyRegistration(
                    userId,
                    opts2.challenge,
                    PasskeyRegistrationResponse(
                        id = "id",
                        rawId = "id",
                        response =
                            AuthenticatorAttestationResponse(
                                clientDataJSON = "*invalid*",
                                attestationObject = encodeBase64Url("ao".encodeToByteArray()),
                            ),
                    ),
                )
            assertFalse(invalidCd.success)
            assertContains(invalidCd.error.orEmpty(), "Client data is not valid base64url")
        }

    @Test
    fun `strict registration handles missing attested credential data and conversion errors`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
            coEvery { repository.deactivateAllForUser(any()) } returns true
            coEvery { repository.store(any(), any(), any(), any()) } returns true

            val manager = mockk<WebAuthnManager>()
            val converter = mockk<AttestedCredentialDataConverter>()
            val registrationData = mockk<RegistrationData>()
            val attestationObject = mockk<AttestationObject>()
            val authenticatorData = mockk<AuthenticatorData<RegistrationExtensionAuthenticatorOutput>>()

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            setPrivateField(service, "webAuthnManager", manager)
            setPrivateField(service, "attestedCredentialDataConverter", converter)

            val userId = Uuid.random()
            val response =
                PasskeyRegistrationResponse(
                    id = encodeBase64Url(byteArrayOf(1, 2, 3)),
                    rawId = encodeBase64Url(byteArrayOf(1, 2, 3)),
                    response =
                        AuthenticatorAttestationResponse(
                            clientDataJSON = encodeBase64Url("cd".encodeToByteArray()),
                            attestationObject = encodeBase64Url("ao".encodeToByteArray()),
                        ),
                )

            // Missing attestation object
            every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } returns registrationData
            every { registrationData.attestationObject } returns null
            val opts1 = service.generateRegistrationOptions(userId, "u", "U")
            val noAttestation = service.verifyRegistration(userId, opts1.challenge, response)
            assertFalse(noAttestation.success)
            assertContains(noAttestation.error.orEmpty(), "Attested credential data is missing")

            // Missing attested credential data
            every { registrationData.attestationObject } returns attestationObject
            every { attestationObject.authenticatorData } returns authenticatorData
            every { authenticatorData.attestedCredentialData } returns null
            val opts2 = service.generateRegistrationOptions(userId, "u2", "U2")
            val noAttestedData = service.verifyRegistration(userId, opts2.challenge, response)
            assertFalse(noAttestedData.success)
            assertContains(noAttestedData.error.orEmpty(), "Attested credential data is missing")

            // Data conversion exception
            val opts3 = service.generateRegistrationOptions(userId, "u3", "U3")
            every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } throws DataConversionException("dc")
            val conversionError = service.verifyRegistration(userId, opts3.challenge, response)
            assertFalse(conversionError.success)
            assertContains(conversionError.error.orEmpty(), "Registration data conversion failed")
        }

    @Test
    fun `strict registration stores credential without deactivating other installations`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns listOf("old-cred")
            coEvery { repository.deactivateAllForUser(any()) } returns true
            coEvery { repository.store(any(), any(), any(), any()) } returns true

            val manager = mockk<WebAuthnManager>()
            val converter = mockk<AttestedCredentialDataConverter>()
            val registrationData = mockk<RegistrationData>()
            val attestationObject = mockk<AttestationObject>()
            val authenticatorData = mockk<AuthenticatorData<RegistrationExtensionAuthenticatorOutput>>()
            val attestedCredentialData = mockk<AttestedCredentialData>()
            val credentialBytes = byteArrayOf(9, 8, 7, 6)
            val canonicalId = encodeBase64Url(credentialBytes)

            every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } returns registrationData
            every { registrationData.attestationObject } returns attestationObject
            every { attestationObject.authenticatorData } returns authenticatorData
            every { authenticatorData.attestedCredentialData } returns attestedCredentialData
            every { authenticatorData.signCount } returns 0L
            every { attestedCredentialData.credentialId } returns credentialBytes
            every { converter.convert(attestedCredentialData) } returns byteArrayOf(0xFF.toByte())

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            setPrivateField(service, "webAuthnManager", manager)
            setPrivateField(service, "attestedCredentialDataConverter", converter)

            val userId = Uuid.random()
            val opts = service.generateRegistrationOptions(userId, "alice", "Alice")
            val result =
                service.verifyRegistration(
                    userId,
                    opts.challenge,
                    PasskeyRegistrationResponse(
                        id = canonicalId,
                        rawId = canonicalId,
                        response =
                            AuthenticatorAttestationResponse(
                                clientDataJSON = encodeBase64Url("cd".encodeToByteArray()),
                                attestationObject = encodeBase64Url("ao".encodeToByteArray()),
                            ),
                    ),
                )

            assertTrue(result.success)
            assertEquals(canonicalId, result.credentialId)
            coVerify(exactly = 0) { repository.deactivateAllForUser(userId) }
            coVerify(exactly = 1) { repository.store(userId, canonicalId, byteArrayOf(0xFF.toByte()), 0L) }
        }
}
