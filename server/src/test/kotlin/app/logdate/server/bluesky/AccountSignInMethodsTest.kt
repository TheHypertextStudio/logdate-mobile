@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky

import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.InMemoryAccountIdentityRepository
import app.logdate.server.bluesky.connection.AccountSignInMethods
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AccountSignInMethodsTest {
    @Test
    fun `a real registered passkey qualifies without a projection identity row`() =
        runBlocking {
            val fixture = Fixture()
            PasskeyTestAuthenticator().registerWith(fixture.passkeys, fixture.owner, "owner")
            assertTrue(fixture.methods.hasUsableMethod(fixture.owner))
        }

    @Test
    fun `an identity row cannot stand in for a missing passkey`() =
        runBlocking {
            val fixture = Fixture()
            fixture.link(IdentityProvider.PASSKEY, "missing-credential")
            assertFalse(fixture.methods.hasUsableMethod(fixture.owner))
        }

    @Test
    fun `a deactivated passkey and its retained identity row do not qualify`() =
        runBlocking {
            val fixture = Fixture()
            val key = PasskeyTestAuthenticator()
            key.registerWith(fixture.passkeys, fixture.owner, "owner")
            fixture.link(IdentityProvider.PASSKEY, key.credentialIdB64)
            assertTrue(fixture.passkeys.deletePasskey(key.credentialIdB64, fixture.owner))
            assertFalse(fixture.methods.hasUsableMethod(fixture.owner))
        }

    @Test
    fun `removing a sole passkey has no remaining usable method`() =
        runBlocking {
            val fixture = Fixture()
            val key = PasskeyTestAuthenticator()
            key.registerWith(fixture.passkeys, fixture.owner, "owner")
            assertFalse(fixture.methods.hasUsableMethod(fixture.owner, key.credentialIdB64))
        }

    @Test
    fun `a second registered passkey remains usable when the first is removed`() =
        runBlocking {
            val fixture = Fixture()
            val first = PasskeyTestAuthenticator()
            first.registerWith(fixture.passkeys, fixture.owner, "owner")
            PasskeyTestAuthenticator().registerWith(fixture.passkeys, fixture.owner, "owner")
            assertTrue(fixture.methods.hasUsableMethod(fixture.owner, first.credentialIdB64))
        }

    @Test
    fun `a Google identity does not qualify when server sign in is unconfigured`() =
        runBlocking {
            val fixture = Fixture(configuredGoogle = false)
            fixture.link(IdentityProvider.GOOGLE, "synthetic-google-subject")
            assertFalse(fixture.methods.hasUsableMethod(fixture.owner))
        }

    @Test
    fun `a configured Google sign in with a canonical subject binding qualifies`() =
        runBlocking {
            val fixture = Fixture(configuredGoogle = true)
            fixture.link(IdentityProvider.GOOGLE, "synthetic-google-subject")
            assertTrue(fixture.methods.hasUsableMethod(fixture.owner))
        }

    @Test
    fun `an empty Google subject never qualifies`() =
        runBlocking {
            val fixture = Fixture(configuredGoogle = true)
            fixture.link(IdentityProvider.GOOGLE, " ")
            assertFalse(fixture.methods.hasUsableMethod(fixture.owner))
        }

    @Test
    fun `a stale account projection cannot use a Google subject now bound to another account`() =
        runBlocking {
            val fixture = Fixture(configuredGoogle = true)
            fixture.link(IdentityProvider.GOOGLE, "synthetic-google-subject")
            fixture.link(IdentityProvider.GOOGLE, "synthetic-google-subject", Uuid.random())
            assertFalse(fixture.methods.hasUsableMethod(fixture.owner))
        }

    private class Fixture(
        configuredGoogle: Boolean = false,
    ) {
        val owner = Uuid.random()
        val identities = InMemoryAccountIdentityRepository()
        val passkeys =
            WebAuthnPasskeyService(
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
            )
        val methods =
            AccountSignInMethods(
                passkeys,
                identities,
                object : GoogleIdTokenVerifier {
                    override fun isConfigured(): Boolean = configuredGoogle

                    override suspend fun verify(
                        idToken: String,
                        nonce: String?,
                    ): GoogleIdTokenClaims? = error("No external Google verification belongs in a method check")
                },
            )

        suspend fun link(
            provider: IdentityProvider,
            subject: String,
            accountId: Uuid = owner,
        ) {
            identities.save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = accountId,
                    provider = provider,
                    providerSubject = subject,
                    createdAt = Instant.parse("2026-10-26T00:00:00Z"),
                ),
            )
        }
    }
}
