package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withOwnPostgresDatabase
import app.logdate.server.identity.AtRestKeyCipher
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthAuthorizationState
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PostgreSQLBlueskyOAuthStoresTest {
    @Test
    fun `pending authorization survives a new instance and only one callback can consume it`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val one = PostgreSQLBlueskyOAuthStateStore(source, cipher(), clock)
            val two = PostgreSQLBlueskyOAuthStateStore(source, cipher(), clock)
            val state = state(clock)
            one.save(state, clock.now() + 5.minutes)
            val results = coroutineScope { listOf(one, two).map { async(Dispatchers.IO) { it.take(state.state) } }.awaitAll() }
            assertEquals(listOf(state), results.filterNotNull())
            assertNull(one.take(state.state))
        }

    @Test
    fun `expired pending authorization is consumed without exposing its private data`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val store = PostgreSQLBlueskyOAuthStateStore(source, cipher(), clock)
            val expired = state(clock)
            val valid = expired.copy(state = "another-authorization-state")
            store.save(expired, clock.now() + 1.minutes)
            store.save(valid, clock.now() + 5.minutes)
            clock.time += 2.minutes
            assertNull(store.take(expired.state))
            assertEquals(valid, store.take(valid.state))
            assertNull(store.take(expired.state))
        }

    @Test
    fun `sessions survive another instance replace atomically and can be deleted`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLBlueskyOAuthSessionStore(source, cipher())
            val two = PostgreSQLBlueskyOAuthSessionStore(source, cipher())
            val session = session()
            one.save(session)
            assertEquals(session, two.get(session.did))
            val refreshed = session.copy(accessToken = "refreshed-access", refreshToken = "refreshed-refresh")
            two.save(refreshed)
            assertEquals(refreshed, one.get(session.did))
            one.delete(session.did)
            assertNull(two.get(session.did))
        }

    @Test
    fun `stored sessions are encrypted and cannot be moved to another identity row`() =
        withBlueskyOAuthDatabase { source ->
            val store = PostgreSQLBlueskyOAuthSessionStore(source, cipher())
            val first = session()
            val second = first.copy(did = AtprotoDid.require("did:web:other.example.com"))
            store.save(first)
            store.save(second)
            assertNotNull(store.get(first.did))
            val encrypted = payload(source, first.did)
            assertFalse(encrypted.contains(first.accessToken))
            assertFalse(encrypted.contains(requireNotNull(first.refreshToken)))
            assertFalse(encrypted.contains(requireNotNull(first.dpopKey.d)))
            source.connection.use { connection ->
                connection.prepareStatement("UPDATE bluesky_oauth_sessions SET encrypted_payload = ? WHERE did = ?").use {
                    it.setString(1, encrypted)
                    it.setString(2, second.did.toString())
                    it.executeUpdate()
                }
                connection.commit()
            }
            assertFailsWith<IllegalArgumentException> { store.get(second.did) }
            assertEquals(first, store.get(first.did))
        }

    @Test
    fun `encryption key rotation reads the old key and writes with the current key`() =
        withBlueskyOAuthDatabase { source ->
            val session = session()
            PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("old-test-key")).save(session)
            val rotated = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("new-test-key", listOf("old-test-key")))
            assertEquals(session, rotated.get(session.did))
            rotated.save(session)
            assertEquals(session, PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("new-test-key")).get(session.did))
        }

    private fun payload(
        source: HikariDataSource,
        did: AtprotoDid,
    ): String =
        source.connection.use { connection ->
            connection.prepareStatement("SELECT encrypted_payload FROM bluesky_oauth_sessions WHERE did = ?").use {
                it.setString(1, did.toString())
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun state(clock: Clock): OAuthAuthorizationState =
        OAuthAuthorizationState(
            state = "single-use-authorization-state",
            issuer = "https://provider.example.com",
            dpopKey = JvmOAuthCrypto.generateEs256Key(),
            clientKeyId = "fixture-client-key",
            pkceVerifier = "fixture-pkce-secret",
            redirectUri = "https://cloud.example.com/oauth/bluesky/callback",
            expectedDid = DID,
            appState = "private-attempt-binding",
            createdAt = clock.now(),
        )

    private fun session(): OAuthStoredSession =
        OAuthStoredSession(
            did = DID,
            issuer = "https://provider.example.com",
            pdsUrl = "https://pds.example.com",
            scope = "atproto",
            accessToken = "fixture-access-secret",
            refreshToken = "fixture-refresh-secret",
            expiresAt = TestClock().now() + 5.minutes,
            dpopKey = JvmOAuthCrypto.generateEs256Key(),
            clientKeyId = "fixture-client-key",
        )

    private fun cipher(): AtRestKeyCipher = AtRestKeyCipher("test-oauth-encryption-key")

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-25T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }

    private companion object {
        val DID = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    }
}

internal fun withBlueskyOAuthDatabase(block: suspend (HikariDataSource) -> Unit) =
    withOwnPostgresDatabase("bluesky_oauth_") { url ->
        localTestPostgresDataSource(url).use { source ->
            val previous = TransactionManager.defaultDatabase
            try {
                TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                runBlocking { block(source) }
            } finally {
                TransactionManager.defaultDatabase = previous
            }
        }
    }
