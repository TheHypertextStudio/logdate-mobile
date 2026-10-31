package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
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
class RestoreCredentialStrictAuthenticationTest {
    // -----------------------------------------------------------------------
    // Strict mode — authentication
    // -----------------------------------------------------------------------

    @Test
    fun `strict authentication validates base64url encoding of payload fields`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            val validId = encodeBase64Url(byteArrayOf(1, 2))
            val stored =
                StoredRestoreCredentialData(
                    credentialId = validId,
                    publicKey = byteArrayOf(1, 2, 3),
                    signCount = 0L,
                    userId = Uuid.random(),
                )
            coEvery { repository.findByCredentialId(validId) } returns stored
            coEvery { repository.findByCredentialId(any()) } returns null

            val baseResponse =
                PasskeyAuthenticationResponse(
                    id = validId,
                    rawId = validId,
                    response =
                        AuthenticatorAssertionResponse(
                            clientDataJSON = encodeBase64Url("cd".encodeToByteArray()),
                            authenticatorData = encodeBase64Url("ad".encodeToByteArray()),
                            signature = encodeBase64Url("sig".encodeToByteArray()),
                            userHandle = null,
                        ),
                )

            // Invalid credential ID
            val invalidId =
                service.verifyAuthentication(
                    service.generateAuthOptions().challenge,
                    baseResponse.copy(id = "*invalid*"),
                )
            assertFalse(invalidId.success)
            assertContains(invalidId.error.orEmpty(), "Credential ID is not valid base64url")

            // Credential not found
            coEvery { repository.findByCredentialId(validId) } returns null
            val notFound =
                service.verifyAuthentication(
                    service.generateAuthOptions().challenge,
                    baseResponse,
                )
            assertFalse(notFound.success)
            assertContains(notFound.error.orEmpty(), "Restore credential not found")
        }

    @Test
    fun `strict authentication handles field decode errors`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val userId = Uuid.random()
            val credentialBytes = byteArrayOf(5, 6, 7)
            val credentialId = encodeBase64Url(credentialBytes)
            val stored =
                StoredRestoreCredentialData(
                    credentialId = credentialId,
                    publicKey = byteArrayOf(1, 2, 3),
                    signCount = 1L,
                    userId = userId,
                )
            val converter = mockk<AttestedCredentialDataConverter>()
            val manager = mockk<WebAuthnManager>(relaxed = true)
            val attestedCredentialData = mockk<AttestedCredentialData>()
            coEvery { repository.findByCredentialId(credentialId) } returns stored
            every { converter.convert(stored.publicKey) } returns attestedCredentialData

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            setPrivateField(service, "webAuthnManager", manager)
            setPrivateField(service, "attestedCredentialDataConverter", converter)

            // Invalid authenticator data
            val invalidAuthData =
                service.verifyAuthentication(
                    service.generateAuthOptions().challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response = AuthenticatorAssertionResponse("cd", "*invalid*", "sig", null),
                    ),
                )
            assertFalse(invalidAuthData.success)
            assertContains(invalidAuthData.error.orEmpty(), "Authenticator data is not valid base64url")

            // Invalid client data
            val invalidCd =
                service.verifyAuthentication(
                    service.generateAuthOptions().challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response = AuthenticatorAssertionResponse("*invalid*", encodeBase64Url("ad".encodeToByteArray()), "sig", null),
                    ),
                )
            assertFalse(invalidCd.success)
            assertContains(invalidCd.error.orEmpty(), "Client data is not valid base64url")

            // Invalid signature
            val invalidSig =
                service.verifyAuthentication(
                    service.generateAuthOptions().challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response =
                            AuthenticatorAssertionResponse(
                                encodeBase64Url("cd".encodeToByteArray()),
                                encodeBase64Url("ad".encodeToByteArray()),
                                "*invalid*",
                                null,
                            ),
                    ),
                )
            assertFalse(invalidSig.success)
            assertContains(invalidSig.error.orEmpty(), "Signature is not valid base64url")
        }

    @Test
    fun `strict authentication surfaces data conversion failure`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val userId = Uuid.random()
            val credentialBytes = byteArrayOf(3, 3, 3)
            val credentialId = encodeBase64Url(credentialBytes)
            val stored =
                StoredRestoreCredentialData(
                    credentialId = credentialId,
                    publicKey = byteArrayOf(9, 9),
                    signCount = 0L,
                    userId = userId,
                )
            val converter = mockk<AttestedCredentialDataConverter>()
            val manager = mockk<WebAuthnManager>(relaxed = true)
            coEvery { repository.findByCredentialId(credentialId) } returns stored
            every { converter.convert(stored.publicKey) } throws DataConversionException("bad key")

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            setPrivateField(service, "webAuthnManager", manager)
            setPrivateField(service, "attestedCredentialDataConverter", converter)

            val result =
                service.verifyAuthentication(
                    service.generateAuthOptions().challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response =
                            AuthenticatorAssertionResponse(
                                encodeBase64Url("cd".encodeToByteArray()),
                                encodeBase64Url("ad".encodeToByteArray()),
                                encodeBase64Url("sig".encodeToByteArray()),
                                null,
                            ),
                    ),
                )
            assertFalse(result.success)
            assertContains(result.error.orEmpty(), "Authentication data conversion failed")
        }

    @Test
    fun `strict authentication returns userId and records authentication on success`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val userId = Uuid.random()
            val credentialBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
            val credentialId = encodeBase64Url(credentialBytes)
            val stored =
                StoredRestoreCredentialData(
                    credentialId = credentialId,
                    publicKey = byteArrayOf(1, 2, 3),
                    signCount = 2L,
                    userId = userId,
                )
            val converter = mockk<AttestedCredentialDataConverter>()
            val manager = mockk<WebAuthnManager>()
            val authenticationData = mockk<AuthenticationData>()
            val authDataObj = mockk<AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>>()
            val attestedCredentialData = mockk<AttestedCredentialData>()

            coEvery { repository.findByCredentialId(credentialId) } returns stored
            coEvery { repository.recordAuthentication(stored, 5L) } returns true
            every { converter.convert(stored.publicKey) } returns attestedCredentialData
            every { manager.verify(any<AuthenticationRequest>(), any<AuthenticationParameters>()) } returns authenticationData
            every { authenticationData.authenticatorData } returns authDataObj
            every { authDataObj.signCount } returns 5L

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = true)
            setPrivateField(service, "webAuthnManager", manager)
            setPrivateField(service, "attestedCredentialDataConverter", converter)

            val challenge = service.generateAuthOptions().challenge
            val result =
                service.verifyAuthentication(
                    challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response =
                            AuthenticatorAssertionResponse(
                                clientDataJSON = encodeBase64Url("cd".encodeToByteArray()),
                                authenticatorData = encodeBase64Url("ad".encodeToByteArray()),
                                signature = encodeBase64Url("sig".encodeToByteArray()),
                                userHandle = null,
                            ),
                    ),
                )

            assertTrue(result.success)
            assertEquals(userId, result.userId)
            assertEquals(credentialId, result.credentialId)
            coVerify(exactly = 1) { repository.recordAuthentication(stored, 5L) }
        }
}
