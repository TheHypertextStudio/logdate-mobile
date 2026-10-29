package app.logdate.server.database

import app.logdate.server.bluesky.BlueskySignInFailure
import app.logdate.server.bluesky.BlueskySignInFailureKind
import app.logdate.server.bluesky.VerifiedBlueskySignIn
import app.logdate.server.identity.AtRestKeyCipher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PostgreSQLBlueskySignInAttemptStoreTest {
    @Test
    fun `a declined browser result is app-bound and spent once after restart`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val one = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            one.create(id, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(one.fail(id, BlueskySignInFailureKind.DECLINED))
            val two = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            assertNull(two.consume(id, "wrong-app-proof-that-is-long-enough-to-be-valid-123"))
            assertFalse(two.complete(id, did))
            assertFalse(two.fail(id, BlueskySignInFailureKind.UNAVAILABLE))
            assertEquals(BlueskySignInFailureKind.DECLINED, assertFailsWith<BlueskySignInFailure> { two.consume(id, verifier) }.kind)
            assertNull(one.consume(id, verifier))
        }

    @Test
    fun `an accepted identity cannot be overwritten by a later failure callback`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            store.create(id, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(store.complete(id, did))
            assertFalse(store.fail(id, BlueskySignInFailureKind.DECLINED))
            assertEquals(did, assertNotNull(store.consume(id, verifier)).did)
        }

    @Test
    fun `only the original app can consume a verified browser return across instances`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val one = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            val two = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            one.create(id, challenge(verifier), clock.now() + 5.minutes, "existing-account")
            assertNull(two.consume(id, verifier), "pending browser return is not authentication")
            assertTrue(two.complete(id, did))
            assertNull(one.consume(id, "wrong-app-proof-that-is-long-enough-to-be-valid-123"))
            assertEquals(VerifiedBlueskySignIn(did, "existing-account"), one.consume(id, verifier))
            assertNull(two.consume(id, verifier))
        }

    @Test
    fun `concurrent exchanges return the verified identity once`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val stores = List(4) { PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock) }
            stores.first().create(id, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(stores.first().complete(id, did))
            val outcomes = coroutineScope { stores.map { async { it.consume(id, verifier) } }.awaitAll() }
            assertEquals(listOf(VerifiedBlueskySignIn(did, null)), outcomes.filterNotNull())
        }

    @Test
    fun `a duplicate callback cannot replace the verified identity`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            store.create(id, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(store.complete(id, did))
            assertFalse(store.complete(id, AtprotoDid.require("did:web:other.example.com")))
            assertEquals(did, assertNotNull(store.consume(id, verifier)).did)
        }

    @Test
    fun `expired attempts cannot complete or exchange`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            store.create(id, challenge(verifier), clock.now() + 5.minutes)
            val other = "b".repeat(43)
            store.create(other, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(store.complete(id, did))
            clock.time += 6.minutes
            assertFalse(store.complete(other, did))
            assertNull(store.consume(id, verifier))
            assertNull(store.consume(other, verifier))
        }

    @Test
    fun `proof and account association are encrypted and payload swaps are refused`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            store.create(id, challenge(verifier), clock.now() + 5.minutes, "private-account-marker")
            val other = "b".repeat(43)
            store.create(other, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(store.complete(id, did))
            source.connection.use { connection ->
                val payload =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT encrypted_payload FROM bluesky_sign_in_attempts ORDER BY attempt_key").use { rows ->
                            val values = mutableListOf<String>()
                            while (rows.next()) values += rows.getString(1)
                            values
                        }
                    }
                assertEquals(2, payload.size)
                payload.forEach {
                    assertFalse(it.contains("private-account-marker"))
                    assertFalse(it.contains(challenge(verifier)))
                    assertFalse(it.contains(verifier))
                }
                connection
                    .prepareStatement(
                        "UPDATE bluesky_sign_in_attempts SET encrypted_payload = (SELECT encrypted_payload FROM bluesky_sign_in_attempts WHERE attempt_key = ?) WHERE attempt_key = ?",
                    ).use {
                        it.setString(1, lookupKey(id))
                        it.setString(2, lookupKey(other))
                        assertEquals(1, it.executeUpdate())
                    }
                connection.commit()
            }
            assertFailsWith<IllegalArgumentException> { store.consume(other, verifier) }
            assertEquals(VerifiedBlueskySignIn(did, "private-account-marker"), store.consume(id, verifier))
        }

    @Test
    fun `a verified handoff has its own bounded exchange window`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            store.create(id, challenge(verifier), clock.now() + 5.minutes)
            val other = "b".repeat(43)
            store.create(other, challenge(verifier), clock.now() + 5.minutes)
            clock.time += 4.minutes
            assertTrue(store.complete(id, did))
            assertTrue(store.complete(other, did))
            clock.time += 2.minutes
            assertEquals(VerifiedBlueskySignIn(did, null), store.consume(id, verifier))
            clock.time += 4.minutes
            assertNull(store.consume(other, verifier))
        }

    @Test
    fun `changing the database expiry cannot extend an app proof`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher(), clock)
            store.create(id, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(store.complete(id, did))
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate(
                        "UPDATE bluesky_sign_in_attempts SET expires_at = expires_at + INTERVAL '1 day'",
                    )
                }
                connection.commit()
            }
            clock.time += 6.minutes
            assertNull(store.consume(id, verifier))
        }

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-25T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }

    private fun cipher() = AtRestKeyCipher("test-only-attempt-encryption-key")

    private fun challenge(value: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))

    private fun lookupKey(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))

    private val id = "a".repeat(43)
    private val verifier = "app-proof-verifier-that-is-long-enough-for-pkce-123456789"
    private val did = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx")
}
