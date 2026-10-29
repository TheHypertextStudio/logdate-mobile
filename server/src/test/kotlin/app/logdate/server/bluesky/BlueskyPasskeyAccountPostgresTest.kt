package app.logdate.server.bluesky

import app.logdate.server.auth.Account
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.database.PostgreSQLAccountIdentityRepository
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.PostgreSQLBlueskyAccountEnrollmentRepository
import app.logdate.server.database.PostgreSQLBlueskyAccountHandoffStore
import app.logdate.server.database.PostgreSQLPasskeyChallengeRepository
import app.logdate.server.database.PostgreSQLPasskeyRepository
import app.logdate.server.database.withBlueskyOAuthDatabase
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import com.zaxxer.hikari.HikariDataSource
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class BlueskyPasskeyAccountPostgresTest {
    @Test
    fun `claiming an external account holds the same DID lock as cleanup`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            var held = false
            var insertWasLocked = false
            val delegate = PostgreSQLBlueskyAccountEnrollmentRepository(source)
            val service =
                BlueskyPasskeyAccountService(
                    h.accounts,
                    h.handoffs,
                    BlueskyAccountEnrollmentRepository { account, passkey ->
                        insertWasLocked = held
                        delegate.create(account, passkey)
                    },
                    h.passkeys,
                    claimsLock =
                        object : OAuthSessionLock {
                            override suspend fun <T> withLock(
                                did: AtprotoDid,
                                block: suspend () -> T,
                            ): T {
                                assertEquals(BlueskyPasskeyAccountPostgresTest.did, did)
                                held = true
                                try {
                                    return block()
                                } finally {
                                    held = false
                                }
                            }
                        },
                )
            val begin = service.beginRegistration(h.identity(), profile(Uuid.random()))
            service.completeRegistration(begin.token, PasskeyTestAuthenticator().register(begin.options.challenge), null)
            assertTrue(insertWasLocked, "Ownership must not appear between cleanup's locked check and removal")
        }

    @Test
    fun `real passkey enrollment keeps the verified DID and durable owner without provisioning another identity`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            val owner = Uuid.random()
            val proof = h.identity()
            val begin = h.service.beginRegistration(proof, profile(owner))
            assertTrue(h.accounts.getAllAccounts().isEmpty(), "a provider proof and registration challenge are not an account")
            val authenticator = PasskeyTestAuthenticator()
            val account =
                Harness(
                    source,
                ).service.completeRegistration(begin.token, authenticator.register(begin.options.challenge), "My laptop")
            assertEquals(owner, account.id)
            assertEquals(did.toString(), account.did)
            assertEquals(AccountIdentityKind.EXTERNAL, account.identityKind)
            assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, account.storageMode)
            assertNull(account.signingKeyPublic)
            assertNull(account.publicIdentityDid)
            assertEquals(owner, h.passkeyRepository.getPasskeyByCredentialId(authenticator.credentialIdB64)?.first)
            assertEquals(
                "My laptop",
                h.passkeyRepository
                    .getPasskeysForUser(owner)
                    .single()
                    .nickname,
            )
            assertEquals(
                owner,
                PostgreSQLAccountIdentityRepository()
                    .findByProviderSubject(
                        IdentityProvider.PASSKEY,
                        authenticator.credentialIdB64,
                    )?.accountId,
            )
            assertFailsWith<BlueskyAccountFailure> { h.service.beginRegistration(proof, profile(Uuid.random())) }
            assertFailsWith<BlueskyAccountFailure> {
                h.service.completeRegistration(begin.token, authenticator.register(begin.options.challenge), null)
            }
            assertEquals(1, h.accounts.getAllAccounts().size)
        }

    @Test
    fun `invalid attestation creates no account and cannot be replayed`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            val begin = h.service.beginRegistration(h.identity(), profile(Uuid.random()))
            val authenticator = PasskeyTestAuthenticator(origin = "https://wrong.example.com")
            val failure =
                assertFailsWith<BlueskyAccountFailure> {
                    h.service.completeRegistration(begin.token, authenticator.register(begin.options.challenge), null)
                }
            assertEquals(BlueskyAccountFailureKind.PASSKEY_FAILED, failure.kind)
            assertTrue(h.accounts.getAllAccounts().isEmpty())
            assertNull(h.handoffs.take(begin.token))
        }

    @Test
    fun `claiming a DID during enrollment never attaches the existing account or overwrites its passkeys`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            val begin = h.service.beginRegistration(h.identity(), profile(Uuid.random()))
            val original = h.account("original")
            h.accounts.create(original)
            val authenticator = PasskeyTestAuthenticator()
            val failure =
                assertFailsWith<BlueskyAccountFailure> {
                    h.service.completeRegistration(begin.token, authenticator.register(begin.options.challenge), null)
                }
            assertEquals(BlueskyAccountFailureKind.CONFLICT, failure.kind)
            assertEquals(listOf(original.id), h.accounts.getAllAccounts().map { it.id })
            assertNull(h.passkeyRepository.getPasskeyByCredentialId(authenticator.credentialIdB64))
        }

    @Test
    fun `existing accounts require their own real passkey and reject another account's passkey`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            val original = h.account("original")
            val other = h.account("other").copy(did = "did:web:other.example.com")
            h.accounts.create(original)
            h.accounts.create(other)
            val correct = PasskeyTestAuthenticator().also { it.registerWith(h.passkeys, original.id, original.username) }
            val wrong = PasskeyTestAuthenticator().also { it.registerWith(h.passkeys, other.id, other.username) }
            val first = h.service.beginAuthentication(h.identity(original.id))
            val failure =
                assertFailsWith<BlueskyAccountFailure> {
                    h.service.completeAuthentication(first.token, assertion(wrong, first.options.challenge))
                }
            assertEquals(BlueskyAccountFailureKind.PASSKEY_FAILED, failure.kind)
            val second = h.service.beginAuthentication(h.identity(original.id))
            assertFalse(h.passkeys.verifyAuthentication(second.options.challenge, assertion(correct, second.options.challenge)).success)
            assertFalse(
                h.passkeys.verifyStepUp(original.id, second.options.challenge, assertion(correct, second.options.challenge)).success,
            )
            val confirmed = Harness(source).service.completeAuthentication(second.token, assertion(correct, second.options.challenge))
            assertEquals(original.id, confirmed.id)
            assertEquals(did.toString(), confirmed.did)
            assertNull(h.handoffs.take(second.token))
        }

    @Test
    fun `an identity changed after browser confirmation cannot authenticate or register another account`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            val original = h.account("original").copy(did = "did:web:changed.example.com")
            h.accounts.create(original)
            val token = h.identity(original.id)
            assertEquals(
                BlueskyAccountFailureKind.CONFLICT,
                assertFailsWith<BlueskyAccountFailure> { h.service.beginAuthentication(token) }.kind,
            )
            assertFailsWith<BlueskyAccountFailure> { h.service.beginRegistration(h.identity(original.id), profile(Uuid.random())) }
            assertEquals(1, h.accounts.getAllAccounts().size)
        }

    @Test
    fun `invalid profile does not spend the provider continuation and stages cannot be interchanged`() =
        withBlueskyOAuthDatabase { source ->
            val h = Harness(source)
            val token = h.identity()
            val invalid = profile(Uuid.random()).copy(username = "bad name")
            assertEquals(
                BlueskyAccountFailureKind.INVALID_PROFILE,
                assertFailsWith<BlueskyAccountFailure> {
                    h.service.beginRegistration(token, invalid)
                }.kind,
            )
            val begin = h.service.beginRegistration(token, profile(Uuid.random()))
            assertFailsWith<BlueskyAccountFailure> { h.service.beginAuthentication(begin.token) }
            assertTrue(h.accounts.getAllAccounts().isEmpty())
        }

    private fun profile(owner: Uuid) = BlueskyAccountProfile(owner.toString(), "alice", "Alice")

    private fun assertion(
        authenticator: PasskeyTestAuthenticator,
        challenge: String,
    ): PasskeyAuthenticationResponse {
        val signed = authenticator.assert(challenge)
        return PasskeyAuthenticationResponse(
            signed.id,
            signed.rawId,
            AuthenticatorAssertionResponse(
                signed.response.clientDataJSON,
                signed.response.authenticatorData,
                signed.response.signature,
                null,
            ),
        )
    }

    private class Harness(
        source: HikariDataSource,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val handoffs = PostgreSQLBlueskyAccountHandoffStore(source, AtRestKeyCipher("passkey-handoff-test"))
        val passkeyRepository = PostgreSQLPasskeyRepository()
        val passkeys =
            WebAuthnPasskeyService(
                passkeyRepository,
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
                challengeRepository = PostgreSQLPasskeyChallengeRepository(),
            )
        val service = BlueskyPasskeyAccountService(accounts, handoffs, PostgreSQLBlueskyAccountEnrollmentRepository(source), passkeys)

        suspend fun identity(accountId: Uuid? = null): String =
            handoffs.issue(BlueskyAccountHandoff.Identity(did, accountId?.toString()), Clock.System.now() + 5.minutes)

        fun account(name: String) =
            Account(
                Uuid.random(),
                name,
                name,
                did = did.toString(),
                createdAt = Clock.System.now(),
                storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                identityKind = AccountIdentityKind.EXTERNAL,
            )
    }

    companion object {
        private val did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    }
}
