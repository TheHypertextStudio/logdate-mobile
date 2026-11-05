package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyChallenge
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationResponse
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Behavioral contract tests for the [WebAuthnPasskeyService].
 *
 * This exhaustive test suite validates the internal state transitions and error
 * handling of the WebAuthn service. It covers edge cases in both simplified and
 * strict modes, including repository failures, malformed payloads, and detailed
 * WebAuthn structure validation using mocked components.
 */
@OptIn(ExperimentalUuidApi::class)
class WebAuthnPasskeyServiceBehaviorContractTest {
    @Test
    fun `registration result exposes passkey getter`() {
        val passkey =
            PasskeyInfo(
                id = Uuid.random(),
                credentialId = "cred-getter",
                nickname = "Device",
                deviceType = "platform",
                createdAt = Clock.System.now(),
                lastUsedAt = null,
                isActive = true,
            )
        val result =
            WebAuthnPasskeyService.RegistrationResult(
                success = true,
                credentialId = "cred-getter",
                passkey = passkey,
                error = null,
            )

        assertNotNull(result.passkey)
        assertEquals("cred-getter", result.passkey.credentialId)
    }

    @Test
    fun `simplified mode handles default auth options invalid challenge and storage failures`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        coEvery { repository.getPasskeyByCredentialId(any()) } returns null
        coEvery { repository.storePasskey(any(), any(), any(), any(), any()) } returns false
        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = false)
        val userId = Uuid.random()

        val defaults = service.generateAuthenticationOptions()
        assertTrue(defaults.allowCredentials.isEmpty())

        val regOptions = service.generateRegistrationOptions(userId, "u", "U")
        val registration =
            service.verifyRegistration(
                userId,
                regOptions.challenge,
                PasskeyRegistrationResponse(
                    id = "cred",
                    rawId = "cred",
                    response =
                        AuthenticatorAttestationResponse(
                            clientDataJSON = "client",
                            attestationObject = "attestation",
                        ),
                ),
            )
        assertFalse(registration.success)
        assertContains(registration.error.orEmpty(), "Failed to store passkey")

        val invalidChallenge =
            service.verifyAuthentication(
                challenge = "missing",
                authenticationResponse =
                    PasskeyAuthenticationResponse(
                        id = "cred",
                        rawId = "cred",
                        response =
                            AuthenticatorAssertionResponse(
                                clientDataJSON = "cd",
                                authenticatorData = "ad",
                                signature = "sig",
                                userHandle = null,
                            ),
                    ),
            )
        assertFalse(invalidChallenge.success)
        assertContains(invalidChallenge.error.orEmpty(), "Invalid challenge")

        val validChallenge = service.generateAuthenticationOptions(userId = userId)
        val missingCredential =
            service.verifyAuthentication(
                challenge = validChallenge.challenge,
                authenticationResponse =
                    PasskeyAuthenticationResponse(
                        id = "cred",
                        rawId = "cred",
                        response =
                            AuthenticatorAssertionResponse(
                                clientDataJSON = "cd",
                                authenticatorData = "ad",
                                signature = "sig",
                                userHandle = null,
                            ),
                    ),
            )
        assertFalse(missingCredential.success)
        assertContains(missingCredential.error.orEmpty(), "Credential not found")
    }

    @Test
    fun `simplified mode catches repository exceptions and missing credentials`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        coEvery { repository.storePasskey(any(), any(), any(), any(), any()) } throws IllegalStateException("store boom")
        coEvery { repository.getPasskeyByCredentialId(any()) } throws IllegalStateException("lookup boom")
        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = false)
        val userId = Uuid.random()

        val regOptions = service.generateRegistrationOptions(userId, "u", "U")
        val regResult =
            service.verifyRegistration(
                userId,
                regOptions.challenge,
                PasskeyRegistrationResponse(
                    id = "cred",
                    rawId = "cred",
                    response = AuthenticatorAttestationResponse(clientDataJSON = "cd", attestationObject = "ao"),
                ),
            )
        assertFalse(regResult.success)
        assertContains(regResult.error.orEmpty(), "store boom")

        val authOptions = service.generateAuthenticationOptions(userId = userId)
        val authResult =
            service.verifyAuthentication(
                authOptions.challenge,
                PasskeyAuthenticationResponse(
                    id = "cred",
                    rawId = "cred",
                    response = AuthenticatorAssertionResponse("cd", "ad", "sig", null),
                ),
            )
        assertFalse(authResult.success)
        assertContains(authResult.error.orEmpty(), "lookup boom")
    }

    @Test
    fun `strict registration validates challenge payload and webauthn structures`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        val userId = Uuid.random()

        val missingChallenge =
            service.verifyRegistration(
                userId = userId,
                challenge = "missing",
                registrationResponse =
                    PasskeyRegistrationResponse(
                        id = "id",
                        rawId = "id",
                        response = AuthenticatorAttestationResponse(clientDataJSON = "x", attestationObject = "y"),
                    ),
            )
        assertFalse(missingChallenge.success)
        assertContains(missingChallenge.error.orEmpty(), "Invalid challenge")

        putChallenge(
            service,
            challenge = "not*base64",
            challengeData = challenge(userId, "registration"),
        )
        val invalidChallengePayload =
            service.verifyRegistration(
                userId = userId,
                challenge = "not*base64",
                registrationResponse =
                    PasskeyRegistrationResponse(
                        id = "id",
                        rawId = "id",
                        response = AuthenticatorAttestationResponse(clientDataJSON = "x", attestationObject = "y"),
                    ),
            )
        assertFalse(invalidChallengePayload.success)
        assertContains(invalidChallengePayload.error.orEmpty(), "Challenge is not valid base64url")

        val options = service.generateRegistrationOptions(userId, "strict", "Strict")
        val invalidAttestation =
            service.verifyRegistration(
                userId = userId,
                challenge = options.challenge,
                registrationResponse =
                    PasskeyRegistrationResponse(
                        id = "id",
                        rawId = "id",
                        response =
                            AuthenticatorAttestationResponse(
                                clientDataJSON = encodeBase64Url("client".encodeToByteArray()),
                                attestationObject = "*invalid*",
                            ),
                    ),
            )
        assertFalse(invalidAttestation.success)
        assertContains(invalidAttestation.error.orEmpty(), "Attestation object is not valid base64url")

        val options2 = service.generateRegistrationOptions(userId, "strict2", "Strict2")
        val invalidClientData =
            service.verifyRegistration(
                userId = userId,
                challenge = options2.challenge,
                registrationResponse =
                    PasskeyRegistrationResponse(
                        id = "id",
                        rawId = "id",
                        response =
                            AuthenticatorAttestationResponse(
                                clientDataJSON = "*invalid*",
                                attestationObject = encodeBase64Url("att".encodeToByteArray()),
                            ),
                    ),
            )
        assertFalse(invalidClientData.success)
        assertContains(invalidClientData.error.orEmpty(), "Client data is not valid base64url")
    }

    @Test
    fun `strict registration handles missing attestation components and conversion errors`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        coEvery { repository.storePasskey(any(), any(), any(), any(), any()) } returns false

        val manager = mockk<WebAuthnManager>()
        val converter = mockk<AttestedCredentialDataConverter>()
        val registrationData = mockk<RegistrationData>()
        val attestationObject = mockk<AttestationObject>()
        val authenticatorData = mockk<AuthenticatorData<RegistrationExtensionAuthenticatorOutput>>()
        val attestedCredentialData = mockk<AttestedCredentialData>()

        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)
        val userId = Uuid.random()
        val options = service.generateRegistrationOptions(userId, "strict3", "Strict3")
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

        every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } returns registrationData
        every { registrationData.attestationObject } returns null
        val noAttestation = service.verifyRegistration(userId, options.challenge, response)
        assertFalse(noAttestation.success)
        assertContains(noAttestation.error.orEmpty(), "Attestation object is missing")

        val options2 = service.generateRegistrationOptions(userId, "strict4", "Strict4")
        every { registrationData.attestationObject } returns attestationObject
        every { attestationObject.authenticatorData } returns authenticatorData
        val options3 = service.generateRegistrationOptions(userId, "strict5", "Strict5")
        every { authenticatorData.attestedCredentialData } returns null
        val noAttestedData = service.verifyRegistration(userId, options3.challenge, response)
        assertFalse(noAttestedData.success)
        assertContains(noAttestedData.error.orEmpty(), "Attested credential data is missing")

        val options4 = service.generateRegistrationOptions(userId, "strict6", "Strict6")
        every { authenticatorData.attestedCredentialData } returns attestedCredentialData
        every { attestedCredentialData.credentialId } returns byteArrayOf(1, 2, 3)
        every { converter.convert(attestedCredentialData) } returns byteArrayOf(9)
        every { authenticatorData.signCount } returns 0L
        val storeFailed = service.verifyRegistration(userId, options4.challenge, response)
        assertFalse(storeFailed.success)
        assertContains(storeFailed.error.orEmpty(), "Failed to store passkey")

        val options5 = service.generateRegistrationOptions(userId, "strict7", "Strict7")
        every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } throws DataConversionException("dc")
        val conversionError = service.verifyRegistration(userId, options5.challenge, response)
        assertFalse(conversionError.success)
        assertContains(conversionError.error.orEmpty(), "Registration data conversion failed")
    }

    @Test
    fun `strict authentication validates payload fields and challenge encoding`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        val userId = Uuid.random()
        val validId = encodeBase64Url(byteArrayOf(1, 2))
        val baseResponse =
            PasskeyAuthenticationResponse(
                id = validId,
                rawId = validId,
                response =
                    AuthenticatorAssertionResponse(
                        clientDataJSON = encodeBase64Url("cd".encodeToByteArray()),
                        authenticatorData = encodeBase64Url("ad".encodeToByteArray()),
                        signature = encodeBase64Url("sg".encodeToByteArray()),
                        userHandle = null,
                    ),
            )

        val invalidId =
            service.verifyAuthentication(
                service.generateAuthenticationOptions(userId = userId).challenge,
                baseResponse.copy(id = "*invalid*"),
            )
        assertFalse(invalidId.success)
        assertContains(invalidId.error.orEmpty(), "Credential ID is not valid base64url")

        coEvery { repository.getPasskeyByCredentialId(validId) } returns null
        val notFound =
            service.verifyAuthentication(
                service.generateAuthenticationOptions(userId = userId).challenge,
                baseResponse,
            )
        assertFalse(notFound.success)
        assertContains(notFound.error.orEmpty(), "Credential not found")

        val manager = mockk<WebAuthnManager>(relaxed = true)
        val converter = mockk<AttestedCredentialDataConverter>()
        val attested = mockk<AttestedCredentialData>()
        val stored =
            StoredPasskeyData(
                credentialId = validId,
                publicKey = byteArrayOf(1, 1, 1),
                signCount = 1L,
                info = passkeyInfo(validId),
                userId = userId,
            )
        coEvery { repository.getPasskeyByCredentialId(validId) } returns (userId to stored)
        every { converter.convert(stored.publicKey) } returns attested
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)

        putChallenge(service, "bad!challenge", challenge(userId, "authentication"))
        val invalidChallenge =
            service.verifyAuthentication(
                "bad!challenge",
                baseResponse.copy(id = validId),
            )
        assertFalse(invalidChallenge.success)
        assertContains(invalidChallenge.error.orEmpty(), "Challenge is not valid base64url")
    }

    @Test
    fun `strict authentication handles field decode errors and generic exceptions`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        val manager = mockk<WebAuthnManager>()
        val converter = mockk<AttestedCredentialDataConverter>()
        val attestedCredentialData = mockk<AttestedCredentialData>()
        val userId = Uuid.random()
        val credentialBytes = byteArrayOf(9, 8, 7)
        val credentialId = encodeBase64Url(credentialBytes)
        val stored =
            StoredPasskeyData(
                credentialId = credentialId,
                publicKey = byteArrayOf(1, 1, 1),
                signCount = 4L,
                info = passkeyInfo(credentialId),
                userId = userId,
            )
        coEvery { repository.getPasskeyByCredentialId(credentialId) } returns (userId to stored)
        every { converter.convert(stored.publicKey) } returns attestedCredentialData

        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)

        val invalidAuthData =
            service.verifyAuthentication(
                service.generateAuthenticationOptions(userId = userId).challenge,
                PasskeyAuthenticationResponse(
                    id = credentialId,
                    rawId = credentialId,
                    response = AuthenticatorAssertionResponse("cd", "*invalid*", "sg", null),
                ),
            )
        assertFalse(invalidAuthData.success)
        assertContains(invalidAuthData.error.orEmpty(), "Authenticator data is not valid base64url")

        val invalidClientData =
            service.verifyAuthentication(
                service.generateAuthenticationOptions(userId = userId).challenge,
                PasskeyAuthenticationResponse(
                    id = credentialId,
                    rawId = credentialId,
                    response = AuthenticatorAssertionResponse("*invalid*", encodeBase64Url("ad".encodeToByteArray()), "sg", null),
                ),
            )
        assertFalse(invalidClientData.success)
        assertContains(invalidClientData.error.orEmpty(), "Client data is not valid base64url")

        val invalidSignature =
            service.verifyAuthentication(
                service.generateAuthenticationOptions(userId = userId).challenge,
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
        assertFalse(invalidSignature.success)
        assertContains(invalidSignature.error.orEmpty(), "Signature is not valid base64url")

        every { manager.verify(any<AuthenticationRequest>(), any<AuthenticationParameters>()) } throws IllegalStateException("verify boom")
        val genericFailure =
            service.verifyAuthentication(
                service.generateAuthenticationOptions(userId = userId).challenge,
                PasskeyAuthenticationResponse(
                    id = credentialId,
                    rawId = credentialId,
                    response =
                        AuthenticatorAssertionResponse(
                            encodeBase64Url("cd".encodeToByteArray()),
                            encodeBase64Url("ad".encodeToByteArray()),
                            encodeBase64Url("sg".encodeToByteArray()),
                            null,
                        ),
                ),
            )
        assertFalse(genericFailure.success)
        assertContains(genericFailure.error.orEmpty(), "Authentication verification failed")
    }

    @Test
    fun `challenge validation rejects used wrong type wrong user and expired entries`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = false)
        val userId = Uuid.random()
        val registration =
            PasskeyRegistrationResponse(
                id = "cred",
                rawId = "cred",
                response = AuthenticatorAttestationResponse("cd", "ao"),
            )

        putChallenge(
            service,
            challenge = "used-challenge",
            challengeData = challenge(userId, "registration", isUsed = true),
        )
        assertFalse(service.verifyRegistration(userId, "used-challenge", registration).success)

        putChallenge(
            service,
            challenge = "wrong-type",
            challengeData = challenge(userId, "authentication"),
        )
        assertFalse(service.verifyRegistration(userId, "wrong-type", registration).success)

        putChallenge(
            service,
            challenge = "wrong-user",
            challengeData = challenge(Uuid.random(), "registration"),
        )
        assertFalse(service.verifyRegistration(userId, "wrong-user", registration).success)

        putChallenge(
            service,
            challenge = "expired",
            challengeData =
                PasskeyChallenge(
                    challenge = "expired",
                    userId = userId,
                    type = "registration",
                    expiresAt = (Clock.System.now() - 1.minutes).toString(),
                    isUsed = false,
                ),
        )
        assertFalse(service.verifyRegistration(userId, "expired", registration).success)
    }

    @Test
    fun `strict registration stores canonical credential id when verification succeeds`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        coEvery { repository.storePasskey(any(), any(), any(), any(), any()) } returns true

        val manager = mockk<WebAuthnManager>()
        val converter = mockk<AttestedCredentialDataConverter>()
        val registrationData = mockk<RegistrationData>()
        val attestationObject = mockk<AttestationObject>()
        val authenticatorData = mockk<AuthenticatorData<RegistrationExtensionAuthenticatorOutput>>()
        val attestedCredentialData = mockk<AttestedCredentialData>()
        val credentialBytes = byteArrayOf(1, 2, 3, 4, 5)
        val canonicalCredentialId = encodeBase64Url(credentialBytes)

        every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } returns registrationData
        every { registrationData.attestationObject } returns attestationObject
        every { attestationObject.authenticatorData } returns authenticatorData
        every { authenticatorData.attestedCredentialData } returns attestedCredentialData
        every { authenticatorData.signCount } returns 7L
        every { attestedCredentialData.credentialId } returns credentialBytes
        every { converter.convert(attestedCredentialData) } returns byteArrayOf(9, 8, 7)

        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)

        val userId = Uuid.random()
        val options = service.generateRegistrationOptions(userId, "strict_user", "Strict User")
        val response =
            PasskeyRegistrationResponse(
                id = canonicalCredentialId,
                rawId = canonicalCredentialId,
                response =
                    AuthenticatorAttestationResponse(
                        clientDataJSON = encodeBase64Url("client".encodeToByteArray()),
                        attestationObject = encodeBase64Url("attestation".encodeToByteArray()),
                    ),
            )

        val result = service.verifyRegistration(userId, options.challenge, response)
        assertTrue(result.success)
        assertEquals(canonicalCredentialId, result.credentialId)
        coVerify(exactly = 1) {
            repository.storePasskey(
                userId = userId,
                credentialId = canonicalCredentialId,
                publicKey = byteArrayOf(9, 8, 7),
                signCount = 7L,
                info = any(),
            )
        }
    }

    @Test
    fun `strict registration rejects mismatched credential identifiers`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()

        val manager = mockk<WebAuthnManager>()
        val converter = mockk<AttestedCredentialDataConverter>(relaxed = true)
        val registrationData = mockk<RegistrationData>()
        val attestationObject = mockk<AttestationObject>()
        val authenticatorData = mockk<AuthenticatorData<RegistrationExtensionAuthenticatorOutput>>()
        val attestedCredentialData = mockk<AttestedCredentialData>()
        val canonicalCredentialId = encodeBase64Url(byteArrayOf(4, 4, 4))

        every { manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>()) } returns registrationData
        every { registrationData.attestationObject } returns attestationObject
        every { attestationObject.authenticatorData } returns authenticatorData
        every { authenticatorData.attestedCredentialData } returns attestedCredentialData
        every { attestedCredentialData.credentialId } returns byteArrayOf(4, 4, 4)

        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)

        val userId = Uuid.random()
        val options = service.generateRegistrationOptions(userId, "strict_user_2", "Strict User 2")
        val response =
            PasskeyRegistrationResponse(
                id = encodeBase64Url(byteArrayOf(5, 5, 5)),
                rawId = encodeBase64Url(byteArrayOf(5, 5, 5)),
                response =
                    AuthenticatorAttestationResponse(
                        clientDataJSON = encodeBase64Url("client".encodeToByteArray()),
                        attestationObject = encodeBase64Url("attestation".encodeToByteArray()),
                    ),
            )

        val result = service.verifyRegistration(userId, options.challenge, response)
        assertFalse(result.success)
        assertContains(result.error.orEmpty(), "Credential ID mismatch")
        assertEquals(canonicalCredentialId, encodeBase64Url(byteArrayOf(4, 4, 4)))
    }

    @Test
    fun `strict authentication treats an empty user handle as absent`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()

        val manager = mockk<WebAuthnManager>()
        val converter = mockk<AttestedCredentialDataConverter>()
        val authenticationData = mockk<AuthenticationData>()
        val authenticationRequest = slot<AuthenticationRequest>()
        val authenticatorData = mockk<AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>>()
        val attestedCredentialData = mockk<AttestedCredentialData>()

        val credentialBytes = byteArrayOf(7, 7, 7, 7)
        val canonicalCredentialId = encodeBase64Url(credentialBytes)
        val paddedCredentialId = Base64.getUrlEncoder().encodeToString(credentialBytes)
        val userId = Uuid.random()
        val passkeyInfo =
            PasskeyInfo(
                id = Uuid.random(),
                credentialId = canonicalCredentialId,
                nickname = "device",
                deviceType = "platform",
                createdAt = Clock.System.now(),
                lastUsedAt = null,
                isActive = true,
            )
        val stored =
            StoredPasskeyData(
                credentialId = canonicalCredentialId,
                publicKey = byteArrayOf(1, 2, 3),
                signCount = 3,
                info = passkeyInfo,
                userId = userId,
            )

        coEvery { repository.getPasskeyByCredentialId(paddedCredentialId) } returns null
        coEvery { repository.getPasskeyByCredentialId(canonicalCredentialId) } returns (userId to stored)
        coEvery { repository.updateSignCount(canonicalCredentialId, 11L) } returns true
        every { converter.convert(stored.publicKey) } returns attestedCredentialData
        every { manager.verify(capture(authenticationRequest), any<AuthenticationParameters>()) } returns authenticationData
        every { authenticationData.authenticatorData } returns authenticatorData
        every { authenticatorData.signCount } returns 11L

        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)

        val challenge = service.generateAuthenticationOptions(userId = userId).challenge
        val response =
            PasskeyAuthenticationResponse(
                id = paddedCredentialId,
                rawId = paddedCredentialId,
                response =
                    AuthenticatorAssertionResponse(
                        clientDataJSON = encodeBase64Url("client".encodeToByteArray()),
                        authenticatorData = encodeBase64Url("auth-data".encodeToByteArray()),
                        signature = encodeBase64Url("signature".encodeToByteArray()),
                        userHandle = "",
                    ),
            )

        val result = service.verifyAuthentication(challenge, response)
        assertTrue(result.success)
        assertNull(authenticationRequest.captured.userHandle)
        assertEquals(userId, result.userId)
        assertEquals(canonicalCredentialId, result.credentialId)
        coVerify(exactly = 1) { repository.updateSignCount(canonicalCredentialId, 11L) }
    }

    @Test
    fun `strict authentication surfaces data conversion failures`() {
        val repository = mockk<PasskeyRepository>()
        coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
        val converter = mockk<AttestedCredentialDataConverter>()
        val manager = mockk<WebAuthnManager>(relaxed = true)
        val userId = Uuid.random()
        val credentialBytes = byteArrayOf(9, 9, 9)
        val credentialId = encodeBase64Url(credentialBytes)
        val stored =
            StoredPasskeyData(
                credentialId = credentialId,
                publicKey = byteArrayOf(4, 5, 6),
                signCount = 1L,
                info =
                    PasskeyInfo(
                        id = Uuid.random(),
                        credentialId = credentialId,
                        nickname = "n",
                        deviceType = "platform",
                        createdAt = Clock.System.now(),
                        lastUsedAt = null,
                        isActive = true,
                    ),
                userId = userId,
            )
        coEvery { repository.getPasskeyByCredentialId(credentialId) } returns (userId to stored)
        every { converter.convert(stored.publicKey) } throws DataConversionException("bad-key")

        val service = WebAuthnPasskeyService(passkeyRepository = repository, strictVerificationEnabled = true)
        setPrivateField(service, "webAuthnManager", manager)
        setPrivateField(service, "attestedCredentialDataConverter", converter)
        val challenge = service.generateAuthenticationOptions(userId = userId).challenge

        val result =
            service.verifyAuthentication(
                challenge,
                PasskeyAuthenticationResponse(
                    id = credentialId,
                    rawId = credentialId,
                    response =
                        AuthenticatorAssertionResponse(
                            clientDataJSON = encodeBase64Url("client".encodeToByteArray()),
                            authenticatorData = encodeBase64Url("auth-data".encodeToByteArray()),
                            signature = encodeBase64Url("signature".encodeToByteArray()),
                            userHandle = null,
                        ),
                ),
            )
        assertFalse(result.success)
        assertContains(result.error.orEmpty(), "Authentication data conversion failed")
    }

    private fun encodeBase64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun challenge(
        userId: Uuid,
        type: String,
        isUsed: Boolean = false,
    ): PasskeyChallenge =
        PasskeyChallenge(
            challenge = "c",
            userId = userId,
            type = type,
            expiresAt = (Clock.System.now() + 5.minutes).toString(),
            isUsed = isUsed,
        )

    @Suppress("UNCHECKED_CAST")
    private fun putChallenge(
        service: WebAuthnPasskeyService,
        challenge: String,
        challengeData: PasskeyChallenge,
    ) {
        val field = service::class.java.getDeclaredField("challenges")
        field.isAccessible = true
        val map = field.get(service) as MutableMap<String, PasskeyChallenge>
        map[challenge] = challengeData.copy(challenge = challenge)
    }

    private fun passkeyInfo(credentialId: String): PasskeyInfo =
        PasskeyInfo(
            id = Uuid.random(),
            credentialId = credentialId,
            nickname = "n",
            deviceType = "platform",
            createdAt = Clock.System.now(),
            lastUsedAt = null,
            isActive = true,
        )

    private fun setPrivateField(
        target: Any,
        fieldName: String,
        value: Any,
    ) {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
