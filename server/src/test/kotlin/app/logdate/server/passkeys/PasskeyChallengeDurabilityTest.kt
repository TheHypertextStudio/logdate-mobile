@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.passkeys

import app.logdate.server.database.PostgreSQLPasskeyChallengeRepository
import app.logdate.server.database.WebAuthnChallengesTable
import app.logdate.server.database.support.withH2Database
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PasskeyChallengeDurabilityTest {
    @Test
    fun `registration and sign in survive a change of server instance`() =
        withH2Database(WebAuthnChallengesTable) {
            runBlocking {
                val passkeys = InMemoryPasskeyRepository()

                fun server() =
                    WebAuthnPasskeyService(
                        passkeyRepository = passkeys,
                        strictVerificationEnabled = false,
                        challengeRepository = PostgreSQLPasskeyChallengeRepository(),
                    )
                val owner = Uuid.random()
                val registration = server().generateRegistrationOptions(owner, "test-user", "Test user")
                val registered = server().verifyRegistration(owner, registration.challenge, registrationResponse())
                assertTrue(registered.success, registered.error)
                val authentication = server().generateAuthenticationOptions()
                val response = authenticationResponse(registered.credentialId!!)
                val signedIn = server().verifyAuthentication(authentication.challenge, response)
                assertTrue(signedIn.success, signedIn.error)
                assertEquals(owner, signedIn.userId)
                assertFalse(server().verifyAuthentication(authentication.challenge, response).success)
            }
        }

    @Test
    fun `restore credentials survive a change of server instance`() =
        withH2Database(WebAuthnChallengesTable) {
            runBlocking {
                val credentials = InMemoryRestoreCredentialRepository()

                fun server() =
                    RestoreCredentialService(
                        restoreCredentialRepository = credentials,
                        strictVerificationEnabled = false,
                        challengeRepository = PostgreSQLPasskeyChallengeRepository(),
                    )
                val owner = Uuid.random()
                val registration = server().generateRegistrationOptions(owner, "test-user", "Test user")
                val registered = server().verifyRegistration(owner, registration.challenge, registrationResponse())
                assertTrue(registered.success, registered.error)
                val authentication = server().generateAuthOptions()
                val signedIn = server().verifyAuthentication(authentication.challenge, authenticationResponse(registered.credentialId!!))
                assertTrue(signedIn.success, signedIn.error)
                assertEquals(owner, signedIn.userId)
            }
        }

    @Test
    fun `strict verification still rejects invalid authenticator data after changing instances`() =
        withH2Database(WebAuthnChallengesTable) {
            runBlocking {
                fun server() =
                    WebAuthnPasskeyService(
                        strictVerificationEnabled = true,
                        challengeRepository = PostgreSQLPasskeyChallengeRepository(),
                    )
                val owner = Uuid.random()
                val options = server().generateRegistrationOptions(owner, "test-user", "Test user")
                val response = registrationResponse().copy(response = AuthenticatorAttestationResponse("!", "!"))
                val result = server().verifyRegistration(owner, options.challenge, response)
                assertFalse(result.success)
                assertEquals("Attestation object is not valid base64url", result.error)
                assertEquals("Invalid challenge", server().verifyRegistration(owner, options.challenge, response).error)
            }
        }

    private fun registrationResponse() =
        PasskeyRegistrationResponse(
            id = "Y3JlZGVudGlhbA",
            rawId = "Y3JlZGVudGlhbA",
            response = AuthenticatorAttestationResponse("fixture", "fixture"),
        )

    private fun authenticationResponse(credentialId: String) =
        PasskeyAuthenticationResponse(
            id = credentialId,
            rawId = credentialId,
            response = AuthenticatorAssertionResponse("fixture", "fixture", "fixture", null),
        )
}
