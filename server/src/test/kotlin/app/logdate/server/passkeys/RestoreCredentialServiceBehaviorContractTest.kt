package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyChallenge
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyRegistrationResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Tests the specialized behavior of the [RestoreCredentialService].
 *
 * Restore credentials are a specific type of passkey used for account recovery.
 * This suite verifies the unique lifecycle of these credentials, including
 * independent registration for each installation and one-use authentication challenges.
 */
@OptIn(ExperimentalUuidApi::class)
class RestoreCredentialServiceBehaviorContractTest {
    // -----------------------------------------------------------------------
    // Simplified mode — registration
    // -----------------------------------------------------------------------

    @Test
    fun `simplified registration succeeds and stores credential`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
            coEvery { repository.deactivateAllForUser(any()) } returns true
            coEvery { repository.store(any(), any(), any(), any()) } returns true

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val userId = Uuid.random()
            val options = service.generateRegistrationOptions(userId, "alice", "Alice")

            val result =
                service.verifyRegistration(
                    userId,
                    options.challenge,
                    PasskeyRegistrationResponse(
                        id = "cred-abc",
                        rawId = "cred-abc",
                        response = AuthenticatorAttestationResponse(clientDataJSON = "client", attestationObject = "att"),
                    ),
                )

            assertTrue(result.success)
            assertNotNull(result.credentialId)
            assertNull(result.error)
        }

    @Test
    fun `simplified registration preserves previous credentials when storing new one`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val userId = Uuid.random()
            coEvery { repository.getCredentialIdsForUser(userId) } returns listOf("old-cred")
            coEvery { repository.deactivateAllForUser(userId) } returns true
            coEvery { repository.store(any(), any(), any(), any()) } returns true

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val options = service.generateRegistrationOptions(userId, "alice", "Alice")

            val result =
                service.verifyRegistration(
                    userId,
                    options.challenge,
                    PasskeyRegistrationResponse(
                        id = "new-cred",
                        rawId = "new-cred",
                        response = AuthenticatorAttestationResponse(clientDataJSON = "cd", attestationObject = "ao"),
                    ),
                )

            assertTrue(result.success)
            coVerify(exactly = 0) { repository.deactivateAllForUser(userId) }
            coVerify(exactly = 1) { repository.store(userId, any(), any(), any()) }
        }

    @Test
    fun `simplified registration rejects when storage fails`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
            coEvery { repository.deactivateAllForUser(any()) } returns true
            coEvery { repository.store(any(), any(), any(), any()) } throws IllegalStateException("store failed")

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val userId = Uuid.random()
            val options = service.generateRegistrationOptions(userId, "alice", "Alice")

            val result =
                service.verifyRegistration(
                    userId,
                    options.challenge,
                    PasskeyRegistrationResponse(
                        id = "cred",
                        rawId = "cred",
                        response = AuthenticatorAttestationResponse(clientDataJSON = "cd", attestationObject = "ao"),
                    ),
                )

            assertFalse(result.success)
            assertContains(result.error.orEmpty(), "store failed")
        }

    // -----------------------------------------------------------------------
    // Simplified mode — authentication
    // -----------------------------------------------------------------------

    @Test
    fun `simplified authentication returns userId and retains credential`() =
        runBlocking<Unit> {
            val userId = Uuid.random()
            val repository = mockk<RestoreCredentialRepository>()
            val stored =
                StoredRestoreCredentialData(
                    credentialId = "cred-xyz",
                    publicKey = byteArrayOf(1, 2, 3),
                    signCount = 0L,
                    userId = userId,
                )
            coEvery { repository.findByCredentialId("cred-xyz") } returns stored
            coEvery { repository.recordAuthentication(stored, 0L) } returns true

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val options = service.generateAuthOptions()

            val result =
                service.verifyAuthentication(
                    options.challenge,
                    PasskeyAuthenticationResponse(
                        id = "cred-xyz",
                        rawId = "cred-xyz",
                        response =
                            AuthenticatorAssertionResponse(
                                clientDataJSON = "cd",
                                authenticatorData = "ad",
                                signature = "sig",
                                userHandle = null,
                            ),
                    ),
                )

            assertTrue(result.success)
            assertEquals(userId, result.userId)
            assertEquals("cred-xyz", result.credentialId)
            coVerify(exactly = 1) { repository.recordAuthentication(stored, 0L) }
        }

    @Test
    fun `simplified authentication fails when credential not found`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.findByCredentialId(any()) } returns null

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val options = service.generateAuthOptions()

            val result =
                service.verifyAuthentication(
                    options.challenge,
                    PasskeyAuthenticationResponse(
                        id = "unknown-cred",
                        rawId = "unknown-cred",
                        response = AuthenticatorAssertionResponse("cd", "ad", "sig", null),
                    ),
                )

            assertFalse(result.success)
            assertContains(result.error.orEmpty(), "Restore credential not found")
        }

    @Test
    fun `simplified authentication prevents challenge replay`() =
        runBlocking<Unit> {
            val userId = Uuid.random()
            val repository = InMemoryRestoreCredentialRepository()
            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)

            val regOptions = service.generateRegistrationOptions(userId, "alice", "Alice")
            service.verifyRegistration(
                userId,
                regOptions.challenge,
                PasskeyRegistrationResponse(
                    id = encodeBase64Url(byteArrayOf(1, 2, 3)),
                    rawId = encodeBase64Url(byteArrayOf(1, 2, 3)),
                    response = AuthenticatorAttestationResponse("cd", "ao"),
                ),
            )

            val credentialId = encodeBase64Url(byteArrayOf(1, 2, 3))
            val authOptions1 = service.generateAuthOptions()
            val firstAttempt =
                service.verifyAuthentication(
                    authOptions1.challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response = AuthenticatorAssertionResponse("cd", "ad", "sig", null),
                    ),
                )
            assertTrue(firstAttempt.success)

            val replayAttempt =
                service.verifyAuthentication(
                    authOptions1.challenge,
                    PasskeyAuthenticationResponse(
                        id = credentialId,
                        rawId = credentialId,
                        response = AuthenticatorAssertionResponse("cd", "ad", "sig", null),
                    ),
                )
            assertFalse(replayAttempt.success)
            assertContains(replayAttempt.error.orEmpty(), "Invalid or expired challenge")
        }

    // -----------------------------------------------------------------------
    // Challenge validation
    // -----------------------------------------------------------------------

    @Test
    fun `challenge validation rejects used wrong-type wrong-user and expired challenges`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
            coEvery { repository.deactivateAllForUser(any()) } returns true
            coEvery { repository.store(any(), any(), any(), any()) } returns true

            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val userId = Uuid.random()
            val registration =
                PasskeyRegistrationResponse(
                    id = "cred",
                    rawId = "cred",
                    response = AuthenticatorAttestationResponse("cd", "ao"),
                )

            // Used challenge
            putChallenge(service, "used-reg", challenge(userId, "restore-registration", isUsed = true))
            assertFalse(service.verifyRegistration(userId, "used-reg", registration).success)

            // Wrong type for registration (authentication type supplied)
            putChallenge(service, "wrong-type-reg", challenge(userId, "restore-authentication"))
            assertFalse(service.verifyRegistration(userId, "wrong-type-reg", registration).success)

            // Wrong user
            putChallenge(service, "wrong-user-reg", challenge(Uuid.random(), "restore-registration"))
            assertFalse(service.verifyRegistration(userId, "wrong-user-reg", registration).success)

            // Expired
            putChallenge(
                service,
                "expired-reg",
                PasskeyChallenge(
                    challenge = "expired-reg",
                    userId = userId,
                    type = "restore-registration",
                    expiresAt = (Clock.System.now() - 1.minutes).toString(),
                    isUsed = false,
                ),
            )
            assertFalse(service.verifyRegistration(userId, "expired-reg", registration).success)

            // Missing entirely
            assertFalse(service.verifyRegistration(userId, "no-such-challenge", registration).success)
        }

    @Test
    fun `authentication challenge validation rejects used wrong-type and expired challenges`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val service = RestoreCredentialService(restoreCredentialRepository = repository, strictVerificationEnabled = false)
            val authResponse =
                PasskeyAuthenticationResponse(
                    id = "cred",
                    rawId = "cred",
                    response = AuthenticatorAssertionResponse("cd", "ad", "sig", null),
                )

            // Used challenge
            putChallenge(service, "used-auth", challenge(Uuid.random(), "restore-authentication", isUsed = true))
            assertFalse(service.verifyAuthentication("used-auth", authResponse).success)

            // Wrong type (registration type for authentication)
            putChallenge(service, "wrong-type-auth", challenge(Uuid.random(), "restore-registration"))
            assertFalse(service.verifyAuthentication("wrong-type-auth", authResponse).success)

            // Expired
            putChallenge(
                service,
                "expired-auth",
                PasskeyChallenge(
                    challenge = "expired-auth",
                    userId = Uuid.random(),
                    type = "restore-authentication",
                    expiresAt = (Clock.System.now() - 1.minutes).toString(),
                    isUsed = false,
                ),
            )
            assertFalse(service.verifyAuthentication("expired-auth", authResponse).success)

            // Missing entirely
            assertFalse(service.verifyAuthentication("no-such-challenge", authResponse).success)
        }

    // -----------------------------------------------------------------------
    // Registration options — generate options shape
    // -----------------------------------------------------------------------

    @Test
    fun `restore registration options advertise ES256 so recovery credentials can be prepared`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()
            val service = RestoreCredentialService(restoreCredentialRepository = repository)

            val options = service.generateRegistrationOptions(Uuid.random(), "recovery_user", "Recovery User")
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
                "Restore registration options must advertise the ES256 algorithm",
            )
        }

    @Test
    fun `generateRegistrationOptions returns expected shape`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            coEvery { repository.getCredentialIdsForUser(any()) } returns emptyList()

            val service =
                RestoreCredentialService(
                    restoreCredentialRepository = repository,
                    relyingPartyId = "example.com",
                    relyingPartyName = "Example",
                )
            val userId = Uuid.random()
            val opts = service.generateRegistrationOptions(userId, "bob", "Bob")

            assertEquals("example.com", opts.rpId)
            assertEquals("Example", opts.rpName)
            assertTrue(opts.challenge.isNotEmpty())
            assertTrue(opts.excludeCredentials.isEmpty())
        }

    @Test
    fun `generateAuthOptions returns empty allowCredentials`() =
        runBlocking<Unit> {
            val repository = mockk<RestoreCredentialRepository>()
            val service = RestoreCredentialService(restoreCredentialRepository = repository)
            val opts = service.generateAuthOptions()

            assertTrue(opts.challenge.isNotEmpty())
            assertTrue(opts.allowCredentials.isEmpty())
            assertEquals("logdate.app", opts.rpId)
        }
}
