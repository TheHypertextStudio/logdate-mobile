package app.logdate.server.database

import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.shared.model.AtprotoConnectionState
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthSessionEndReason
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PostgreSQLAtprotoConnectionRepositoryTest {
    @Test
    fun `saving a grant advances a durable generation visible to another instance`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val one = PostgreSQLAtprotoConnectionRepository(source, clock)
            val two = PostgreSQLAtprotoConnectionRepository(source, clock)
            val original = session(clock)
            tokens.save(original)

            val first = assertNotNull(one.snapshot(original.did))
            assertEquals(AtprotoConnectionState.CONNECTED, first.state)
            assertEquals(1L, first.revision)
            assertEquals(clock.now(), first.observedAt)
            assertEquals(first, two.snapshot(original.did))

            clock.time += 1.minutes
            val renewed = original.copy(accessToken = "renewed-access", refreshToken = "renewed-refresh")
            tokens.save(renewed)

            val second = assertNotNull(two.snapshot(original.did))
            assertEquals(2L, second.revision)
            assertEquals(clock.now(), second.observedAt)
            assertEquals(renewed, tokens.get(original.did))
        }

    @Test
    fun `known termination reasons survive token deletion without guessing`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val connections = PostgreSQLAtprotoConnectionRepository(source, clock)
            val cases =
                listOf(
                    OAuthSessionEndReason.EXPIRED to AtprotoConnectionState.EXPIRED,
                    OAuthSessionEndReason.REVOKED to AtprotoConnectionState.REVOKED,
                    OAuthSessionEndReason.REJECTED to AtprotoConnectionState.RECONNECT_REQUIRED,
                    OAuthSessionEndReason.AUTHORITY_CHANGED to AtprotoConnectionState.RECONNECT_REQUIRED,
                )
            for ((index, entry) in cases.withIndex()) {
                val did = AtprotoDid.require("did:plc:" + ('a' + index).toString().repeat(24))
                tokens.save(session(clock).copy(did = did))
                clock.time += 1.minutes

                tokens.delete(did, entry.first)

                val state = assertNotNull(connections.snapshot(did))
                assertEquals(entry.second, state.state)
                assertEquals(2L, state.revision)
                assertEquals(clock.now(), state.observedAt)
                assertNull(tokens.get(did))
            }
        }

    @Test
    fun `unclassified token deletion records reconnect required`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val stored = session(clock)
            tokens.save(stored)

            tokens.delete(stored.did)

            val state = assertNotNull(PostgreSQLAtprotoConnectionRepository(source, clock).snapshot(stored.did))
            assertEquals(AtprotoConnectionState.RECONNECT_REQUIRED, state.state)
            assertEquals(2L, state.revision)
            assertNull(tokens.get(stored.did))
        }

    @Test
    fun `deleting an absent token does not invent lifecycle history`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val did = session(clock).did

            tokens(source, clock).delete(did)

            assertNull(PostgreSQLAtprotoConnectionRepository(source, clock).snapshot(did))
        }

    @Test
    fun `existing encrypted grants without lifecycle history remain unclassified`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val stored = session(clock)
            tokens.save(stored)
            source.connection.use { connection ->
                connection.prepareStatement("DELETE FROM atproto_connections WHERE did = ?").use {
                    it.setString(1, stored.did.toString())
                    it.executeUpdate()
                }
                connection.commit()
            }

            assertNull(PostgreSQLAtprotoConnectionRepository(source, clock).snapshot(stored.did))
            assertEquals(stored, tokens.get(stored.did))
        }

    @Test
    fun `a failed lifecycle write rolls back the token replacement`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val stored = session(clock)
            val connections = PostgreSQLAtprotoConnectionRepository(source, clock)
            tokens.save(stored)
            val before = assertNotNull(connections.snapshot(stored.did))
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE atproto_connections ADD CONSTRAINT test_no_advance CHECK (revision < 2)")
                }
                connection.commit()
            }

            assertFailsWith<SQLException> { tokens.save(stored.copy(accessToken = "replacement-access")) }

            assertEquals(stored, tokens.get(stored.did))
            assertEquals(before, connections.snapshot(stored.did))
        }

    @Test
    fun `a failed token write rolls back the lifecycle generation`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val stored = session(clock)
            val connections = PostgreSQLAtprotoConnectionRepository(source, clock)
            tokens.save(stored)
            val before = assertNotNull(connections.snapshot(stored.did))
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "CREATE FUNCTION reject_test_token_write() RETURNS trigger LANGUAGE plpgsql " +
                            "AS 'BEGIN RAISE EXCEPTION ''blocked token write''; END'",
                    )
                    it.execute(
                        "CREATE TRIGGER reject_test_token_write BEFORE INSERT OR UPDATE ON bluesky_oauth_sessions " +
                            "FOR EACH ROW EXECUTE FUNCTION reject_test_token_write()",
                    )
                }
                connection.commit()
            }

            assertFailsWith<SQLException> { tokens.save(stored.copy(accessToken = "replacement-access")) }

            assertEquals(stored, tokens.get(stored.did))
            assertEquals(before, connections.snapshot(stored.did))
        }

    @Test
    fun `concurrent grant replacements each advance the committed generation`() =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val tokens = tokens(source, clock)
            val original = session(clock)

            coroutineScope {
                (1..8)
                    .map { index ->
                        async(Dispatchers.IO) { tokens.save(original.copy(accessToken = "access-$index")) }
                    }.awaitAll()
            }

            val snapshot = assertNotNull(PostgreSQLAtprotoConnectionRepository(source, clock).snapshot(original.did))
            assertEquals(8L, snapshot.revision)
            assertEquals(AtprotoConnectionState.CONNECTED, snapshot.state)
            assertNotNull(tokens.get(original.did))
        }

    private fun tokens(
        source: HikariDataSource,
        clock: Clock,
    ) = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("test-atproto-connection-key"), clock)

    private fun session(clock: Clock) =
        OAuthStoredSession(
            did = AtprotoDid.require("did:plc:" + "a".repeat(24)),
            issuer = "https://issuer.example.com",
            pdsUrl = "https://pds.example.com",
            scope = "atproto",
            accessToken = "original-access",
            refreshToken = "original-refresh",
            expiresAt = clock.now() + 5.minutes,
            dpopKey = JvmOAuthCrypto.generateEs256Key(),
            clientKeyId = "test-client-key",
        )

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-26T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }
}
