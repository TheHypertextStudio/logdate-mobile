package app.logdate.server.routes.auth

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.support.signinPasskeyBeginBody
import app.logdate.server.routes.support.signinPasskeyCompleteBody
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyInfo
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Contract tests for the passkey-based sign-in flow.
 *
 * This class verifies the multi-step passkey authentication process, ensuring that
 * authentication options are correctly generated, credentials are verified against
 * the user's stored passkeys, and that the system handles both successful logins
 * and various server-side failures during the sign-in lifecycle.
 */
@OptIn(ExperimentalUuidApi::class)
class AuthSigninFlowContractsTest {
    @Test
    fun `passkey signin begin surfaces failures and completion creates missing identity`() =
        testApplication {
            val accountId = Uuid.random()
            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.generateAuthenticationOptions(any(), any()) } throws IllegalStateException("begin-boom")
            coEvery { passkeyService.verifyAuthentication(any(), any()) } returns
                WebAuthnPasskeyService.AuthenticationResult(
                    success = true,
                    userId = accountId,
                    credentialId = null,
                )
            coEvery { passkeyService.getPasskeysForUser(any()) } returns
                listOf(
                    PasskeyInfo(
                        id = Uuid.random(),
                        credentialId = "cred-created",
                        nickname = "Device",
                        deviceType = "platform",
                        createdAt = Clock.System.now(),
                        lastUsedAt = Clock.System.now(),
                        isActive = true,
                    ),
                )

            val env = configureAuthV1TestApp(webAuthnPasskeyService = passkeyService)
            runBlocking {
                env.accountRepository.save(
                    Account(
                        id = accountId,
                        username = "signin_create_identity",
                        displayName = "Signin Create Identity",
                        email = "signin@example.com",
                        emailVerified = true,
                        createdAt = Clock.System.now(),
                    ),
                )
            }

            val begin =
                client.post("/api/v1/auth/signin/passkey/begin") {
                    contentType(ContentType.Application.Json)
                    setBody(signinPasskeyBeginBody("signin_create_identity"))
                }
            assertEquals(HttpStatusCode.InternalServerError, begin.status)

            coEvery { passkeyService.generateAuthenticationOptions(any(), any()) } returns
                PasskeyAuthenticationOptions(challenge = "challenge-ok", rpId = "logdate.app")
            val complete =
                client.post("/api/v1/auth/signin/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signinPasskeyCompleteBody(challenge = "challenge-ok", credentialId = "cred-created"))
                }
            assertEquals(HttpStatusCode.OK, complete.status)
            assertTrue(complete.bodyAsText().contains("cred-created"))
        }

    @Test
    fun `passkey signin completion returns server error when account update fails`() =
        testApplication {
            val accountId = Uuid.random()
            val account =
                Account(
                    id = accountId,
                    username = "signin_catch_user",
                    displayName = "Signin Catch User",
                    email = "signin-catch@example.com",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )
            val accountRepository = mockk<AccountRepository>(relaxed = true)
            coEvery { accountRepository.findById(accountId) } returns account
            coEvery { accountRepository.updateLastSignIn(accountId) } throws IllegalStateException("update-signin-boom")

            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyAuthentication(any(), any()) } returns
                WebAuthnPasskeyService.AuthenticationResult(
                    success = true,
                    userId = accountId,
                    credentialId = "cred-catch-signin",
                )

            configureAuthV1TestApp(
                accountRepository = accountRepository,
                webAuthnPasskeyService = passkeyService,
            )

            val response =
                client.post("/api/v1/auth/signin/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signinPasskeyCompleteBody(challenge = "challenge-catch", credentialId = "cred-catch-signin"))
                }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
}
