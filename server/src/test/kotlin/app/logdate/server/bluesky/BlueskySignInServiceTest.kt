package app.logdate.server.bluesky

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BlueskySignInServiceTest {
    @Test
    fun `start binds a fresh app attempt before sending the person to the provider`() =
        runBlocking<Unit> {
            val one = service.start(handle, challenge)
            val two = service.start(handle, challenge)
            assertTrue(one.attemptId.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertNotEquals(one.attemptId, two.attemptId)
            assertEquals("https://provider.example.com/authorize", one.authorizationUrl)
            assertEquals(listOf("create", "authorize", "create", "authorize"), operations)
            assertEquals(challenge, attempts.lastChallenge)
            assertEquals(clock.now() + 5.minutes, attempts.lastExpiry)
            assertNull(attempts.accountId, "unauthenticated starts cannot associate another LogDate account")
            assertEquals(handle, provider.lastHandle)
            assertEquals(two.attemptId, provider.lastAttempt)
            assertFalse(one.toString().contains(one.authorizationUrl))
        }

    @Test
    fun `invalid proof challenges never start provider authentication`() =
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> { service.start(handle, "short") }
            assertEquals(emptyList(), operations)
        }

    @Test
    fun `callback records only the verified provider identity and returns the bound attempt`() =
        runBlocking<Unit> {
            provider.result = VerifiedBlueskyCallback(did, "a".repeat(43))
            assertEquals("a".repeat(43), service.callback(mapOf("state" to "provider-state")))
            assertEquals(did, attempts.completedDid)
            assertEquals("a".repeat(43), attempts.completedAttempt)
            assertEquals(mapOf("state" to "provider-state"), provider.lastParameters)
        }

    @Test
    fun `callbacks without a live originating app request cannot produce a handoff`() =
        runBlocking<Unit> {
            provider.result = VerifiedBlueskyCallback(did, null)
            assertEquals(BlueskySignInFailureKind.EXPIRED, assertFailsWith<BlueskySignInFailure> { service.callback(emptyMap()) }.kind)
            provider.result = VerifiedBlueskyCallback(did, "a".repeat(43))
            attempts.canComplete = false
            assertEquals(BlueskySignInFailureKind.EXPIRED, assertFailsWith<BlueskySignInFailure> { service.callback(emptyMap()) }.kind)
        }

    @Test
    fun `provider errors never expose their secrets and cancellation is preserved`() =
        runBlocking<Unit> {
            provider.failure = IllegalArgumentException("secret-provider-token")
            for (action in listOf<suspend () -> Unit>({ service.start(handle, challenge) }, { service.callback(emptyMap()) })) {
                val error = assertFailsWith<BlueskySignInFailure> { action() }
                assertEquals(BlueskySignInFailureKind.UNAVAILABLE, error.kind)
                assertFalse(error.toString().contains("secret-provider-token"))
                assertNull(error.cause)
            }
            provider.failure =
                BlueskySignInFailure(BlueskySignInFailureKind.DECLINED).apply {
                    addSuppressed(IllegalArgumentException("secret-provider-token"))
                }
            val declined = assertFailsWith<BlueskySignInFailure> { service.callback(emptyMap()) }
            assertEquals(BlueskySignInFailureKind.DECLINED, declined.kind)
            assertTrue(declined.suppressedExceptions.isEmpty())
            provider.failure = CancellationException("cancel")
            assertFailsWith<CancellationException> { service.callback(emptyMap()) }
        }

    @Test
    fun `exchange hands the original verifier to the one-use proof store`() =
        runBlocking<Unit> {
            val expected = VerifiedBlueskySignIn(did, null)
            attempts.exchange = expected
            assertEquals(expected, service.exchange("attempt", "original-verifier"))
            assertEquals("attempt" to "original-verifier", attempts.lastExchange)
        }

    private val operations = mutableListOf<String>()
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-25T00:00:00Z")
        }
    private val did = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx")
    private val handle = Handle.require("alice.example.com")
    private val challenge = "a".repeat(43)
    private val attempts = FakeAttempts()
    private val provider = FakeProvider()
    private val service = BlueskySignInService(provider, attempts, clock)

    private inner class FakeProvider : BlueskySignInProvider {
        var lastHandle: Handle? = null
        var lastAttempt: String? = null
        var lastParameters: Map<String, String>? = null
        var failure: Exception? = null
        var result = VerifiedBlueskyCallback(did, null)

        override suspend fun authorize(
            handle: Handle,
            attemptId: String,
        ): String {
            failure?.let { throw it }
            operations += "authorize"
            lastHandle = handle
            lastAttempt = attemptId
            return "https://provider.example.com/authorize"
        }

        override suspend fun callback(
            parameters: Map<String, String>,
            acceptIdentity: suspend (VerifiedBlueskyCallback) -> Unit,
        ) {
            failure?.let { throw it }
            lastParameters = parameters
            acceptIdentity(result)
        }
    }

    private inner class FakeAttempts : BlueskySignInAttemptStore {
        var lastChallenge: String? = null
        var lastExpiry: Instant? = null
        var accountId: String? = null
        var completedAttempt: String? = null
        var completedDid: AtprotoDid? = null
        var canComplete = true
        var exchange: VerifiedBlueskySignIn? = null
        var lastExchange: Pair<String, String>? = null

        override suspend fun create(
            id: String,
            challenge: String,
            expiresAt: Instant,
            accountId: String?,
        ) {
            operations += "create"
            lastChallenge = challenge
            lastExpiry = expiresAt
            this.accountId = accountId
        }

        override suspend fun complete(
            id: String,
            did: AtprotoDid,
        ): Boolean {
            completedAttempt = id
            completedDid = did
            return canComplete
        }

        override suspend fun fail(
            id: String,
            kind: BlueskySignInFailureKind,
        ): Boolean = false

        override suspend fun consume(
            id: String,
            verifier: String,
        ): VerifiedBlueskySignIn? {
            lastExchange = id to verifier
            return exchange
        }
    }
}
