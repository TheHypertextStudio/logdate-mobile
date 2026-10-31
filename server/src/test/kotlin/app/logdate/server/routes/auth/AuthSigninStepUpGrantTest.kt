package app.logdate.server.routes.auth

import app.logdate.server.auth.Account
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.HostedAccountDidMethod
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.support.signinPasskeyCompleteBody
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A passkey sign-in hands the app a step-up grant only where it can be spent: on a server that
 * supports recovery keys, so the app can register the first one without a second passkey prompt.
 */
@OptIn(ExperimentalUuidApi::class)
class AuthSigninStepUpGrantTest {
    @Test
    fun `a passkey sign-in on a server with recovery keys returns a step-up grant for the account`() =
        testApplication {
            val accountId = Uuid.random()
            val passkeys = signedInPasskeys(accountId)
            coEvery { passkeys.issueStepUpGrant(accountId) } returns "grant-for-account"
            val env = configureAuthV1TestApp(webAuthnPasskeyService = passkeys, atprotoIdentityConfig = identityConfig(recoveryKeys = true))
            runBlocking { env.accountRepository.save(account(accountId)) }

            val complete = signIn()

            assertEquals(HttpStatusCode.OK, complete.first)
            assertEquals("grant-for-account", complete.second)
            coVerify(exactly = 1) { passkeys.issueStepUpGrant(accountId) }
        }

    @Test
    fun `a grant that cannot be stored leaves the sign-in working without one`() =
        testApplication {
            val accountId = Uuid.random()
            val passkeys = signedInPasskeys(accountId)
            coEvery { passkeys.issueStepUpGrant(accountId) } throws IllegalStateException("challenge store unavailable")
            val env = configureAuthV1TestApp(webAuthnPasskeyService = passkeys, atprotoIdentityConfig = identityConfig(recoveryKeys = true))
            runBlocking { env.accountRepository.save(account(accountId)) }

            val complete = signIn()

            assertEquals(HttpStatusCode.OK, complete.first)
            assertNull(complete.second)
        }

    @Test
    fun `a passkey sign-in on a server without recovery keys issues no grant`() =
        testApplication {
            val accountId = Uuid.random()
            val passkeys = signedInPasskeys(accountId)
            val env =
                configureAuthV1TestApp(webAuthnPasskeyService = passkeys, atprotoIdentityConfig = identityConfig(recoveryKeys = false))
            runBlocking { env.accountRepository.save(account(accountId)) }

            val complete = signIn()

            assertEquals(HttpStatusCode.OK, complete.first)
            assertNull(complete.second)
            coVerify(exactly = 0) { passkeys.issueStepUpGrant(any()) }
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signIn(): Pair<HttpStatusCode, String?> {
        val response =
            client.post("/api/v1/auth/signin/passkey/complete") {
                contentType(ContentType.Application.Json)
                setBody(signinPasskeyCompleteBody(challenge = "challenge-ok", credentialId = "cred-signin"))
            }
        val data =
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["data"]
                ?.jsonObject
        return response.status to data?.get("stepUpGrant")?.jsonPrimitive?.content
    }

    private fun signedInPasskeys(accountId: Uuid): WebAuthnPasskeyService =
        mockk<WebAuthnPasskeyService>(relaxed = true).also { passkeys ->
            coEvery { passkeys.verifyAuthentication(any(), any()) } returns
                WebAuthnPasskeyService.AuthenticationResult(success = true, userId = accountId, credentialId = "cred-signin")
            coEvery { passkeys.getPasskeysForUser(any()) } returns emptyList()
        }

    private fun identityConfig(recoveryKeys: Boolean) =
        AtprotoIdentityConfig(
            handleDomain = "logdate.app",
            pdsServiceEndpoint = "https://logdate.app",
            hostedAccountDidMethod = HostedAccountDidMethod.PLC,
            recoveryKeysEnabled = recoveryKeys,
        )

    private fun account(id: Uuid) =
        Account(id = id, username = "grant_${id.toHexString().take(6)}", displayName = "Grant", createdAt = Clock.System.now())
}
