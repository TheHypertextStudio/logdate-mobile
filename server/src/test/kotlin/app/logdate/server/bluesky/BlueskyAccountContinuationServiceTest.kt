package app.logdate.server.bluesky

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class BlueskyAccountContinuationServiceTest {
    @Test
    fun `new verified DIDs get only a bounded account creation continuation`() =
        runBlocking {
            coEvery { signIn.exchange("attempt", "verifier") } returns VerifiedBlueskySignIn(did, null)
            val result = requireNotNull(service.exchange("attempt", "verifier"))
            assertEquals(BlueskyAccountAction.CREATE_ACCOUNT, result.action)
            assertEquals("opaque-continuation", result.token)
            assertEquals(BlueskyAccountHandoff.Identity(did, null), handoffs.value)
            assertEquals(clock.now() + 5.minutes, handoffs.expiry)
            assertTrue(accounts.getAllAccounts().isEmpty())
            assertFalse(result.toString().contains(result.token))
        }

    @Test
    fun `only the verified DID chooses an existing account`() =
        runBlocking {
            val account = external()
            accounts.create(account)
            coEvery { signIn.exchange(any(), any()) } returns VerifiedBlueskySignIn(did, null)
            assertEquals(BlueskyAccountAction.CONFIRM_PASSKEY, service.exchange("attempt", "verifier")?.action)
            assertEquals(BlueskyAccountHandoff.Identity(did, account.id.toString()), handoffs.value)
            assertEquals(account, accounts.findById(account.id))
        }

    @Test
    fun `a matching handle or email never attaches another DID`() =
        runBlocking {
            accounts.create(external().copy(did = "did:web:other.example.com", email = "alice@example.com", handle = "alice.example.com"))
            coEvery { signIn.exchange(any(), any()) } returns VerifiedBlueskySignIn(did, null)
            assertEquals(BlueskyAccountAction.CREATE_ACCOUNT, service.exchange("attempt", "verifier")?.action)
            assertEquals(BlueskyAccountHandoff.Identity(did, null), handoffs.value)
        }

    @Test
    fun `missing proof and account-bound linking proofs cannot start ordinary enrollment`() =
        runBlocking {
            coEvery { signIn.exchange(any(), any()) } returns null
            assertNull(service.exchange("attempt", "wrong-verifier"))
            coEvery { signIn.exchange(any(), any()) } returns VerifiedBlueskySignIn(did, Uuid.random().toString())
            assertNull(service.exchange("attempt", "verifier"))
            assertNull(handoffs.value)
        }

    @Test
    fun `inactive moderated and unknown accounts cannot use the identity continuation`() =
        runBlocking {
            for (account in listOf(
                external().copy(isActive = false),
                external().copy(moderation = app.logdate.server.auth.AccountModeration.SUSPENDED),
                external().copy(identityKind = AccountIdentityKind.UNRECOGNIZED),
            )) {
                val repository =
                    object : AccountRepository by InMemoryAccountRepository() {
                        override suspend fun findByDid(did: String): Account = account
                    }
                coEvery { signIn.exchange(any(), any()) } returns VerifiedBlueskySignIn(did, null)
                assertNull(BlueskyAccountContinuationService(signIn, repository, handoffs, clock).exchange("attempt", "verifier"))
                assertNull(handoffs.value)
            }
        }

    @Test
    fun `storage failures are bounded and cancellation propagates`() =
        runBlocking<Unit> {
            coEvery { signIn.exchange(any(), any()) } throws IllegalArgumentException("private-input-and-token")
            val error = assertFailsWith<BlueskySignInFailure> { service.exchange("attempt", "verifier") }
            assertFalse(error.toString().contains("private-input"))
            assertNull(error.cause)
            coEvery { signIn.exchange(any(), any()) } throws CancellationException("canceled")
            assertFailsWith<CancellationException> { service.exchange("attempt", "verifier") }
        }

    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-25T00:00:00Z")
        }
    private val did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    private val signIn = mockk<BlueskySignInService>()
    private val accounts = InMemoryAccountRepository()
    private val handoffs = Handoffs()
    private val service = BlueskyAccountContinuationService(signIn, accounts, handoffs, clock)

    private fun external() =
        Account(
            id = Uuid.random(),
            username = "alice",
            displayName = "Alice",
            createdAt = clock.now(),
            did = did.toString(),
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
            identityKind = AccountIdentityKind.EXTERNAL,
        )

    private class Handoffs : BlueskyAccountHandoffStore {
        var value: BlueskyAccountHandoff? = null
        var expiry: Instant? = null

        override suspend fun issue(
            value: BlueskyAccountHandoff,
            expiresAt: Instant,
        ): String {
            this.value = value
            expiry = expiresAt
            return "opaque-continuation"
        }

        override suspend fun take(token: String): BlueskyAccountHandoff? = null
    }
}
