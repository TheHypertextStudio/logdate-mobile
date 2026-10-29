@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.FakeGoogleIdTokenVerifier
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.InMemoryAccountIdentityRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.WebAuthnPasskeyService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

class AccountCredentialRemovalTest {
    @Test
    fun `unconfigured Google cannot permit removal of the only usable passkey`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val identities = InMemoryAccountIdentityRepository()
            val passkeys = WebAuthnPasskeyService()
            val tokens = JwtTokenService("credential-removal-test")
            val account = Account(Uuid.random(), "account_owner", "Account Owner", createdAt = Clock.System.now())
            accounts.create(account)
            identities.save(
                AccountIdentity(Uuid.random(), account.id, IdentityProvider.GOOGLE, "synthetic-subject", createdAt = Clock.System.now()),
            )
            val key = PasskeyTestAuthenticator()
            key.registerWith(passkeys, account.id, account.username)
            configureAuthV1TestApp(
                accountRepository = accounts,
                identityRepository = identities,
                webAuthnPasskeyService = passkeys,
                tokenService = tokens,
                googleIdTokenVerifier = FakeGoogleIdTokenVerifier(emptyMap(), configured = false),
            )
            val response =
                client.delete("/api/v1/auth/me/passkeys/${key.credentialIdB64}") {
                    bearerAuth(tokens.generateAccessToken(account.id.toString(), account.did))
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(listOf(key.credentialIdB64), passkeys.getUserCredentials(account.id))
        }
}
