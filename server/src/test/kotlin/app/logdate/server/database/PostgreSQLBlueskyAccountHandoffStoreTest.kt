package app.logdate.server.database

import app.logdate.server.bluesky.BlueskyAccountHandoff
import app.logdate.server.identity.AtRestKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.hypertext.atproto.identity.AtprotoDid
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

class PostgreSQLBlueskyAccountHandoffStoreTest {
    @Test
    fun `handoffs survive restart and only one instance can spend them`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLBlueskyAccountHandoffStore(source, cipher, clock)
            val token = one.issue(identity, clock.now() + 5.minutes)
            assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            val two = PostgreSQLBlueskyAccountHandoffStore(source, cipher, clock)
            val results =
                coroutineScope {
                    listOf(one, two).map { store -> async(Dispatchers.IO) { store.take(token) } }.awaitAll()
                }
            assertEquals(1, results.count { it == identity })
            assertEquals(1, results.count { it == null })
            assertNull(one.take(token))
        }

    @Test
    fun `all stages are encrypted and keys do not contain the bearer token`() =
        withBlueskyOAuthDatabase { source ->
            val store = PostgreSQLBlueskyAccountHandoffStore(source, cipher, clock)
            val values =
                listOf(
                    identity,
                    BlueskyAccountHandoff.Registration(
                        did,
                        "owner-id",
                        "private-name",
                        "Private name",
                        "private bio",
                        null,
                        "passkey-challenge",
                    ),
                    BlueskyAccountHandoff.Authentication(did, "account-id", "bound-challenge"),
                )
            for (value in values) {
                val token = store.issue(value, clock.now() + 5.minutes)
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT handoff_key, encrypted_payload FROM bluesky_account_handoffs").use { rows ->
                            assertTrue(rows.next())
                            assertNotEquals(token, rows.getString(1))
                            for (privateValue in listOf(did.toString(), "private-name", "private bio", "passkey-challenge", token)) {
                                assertFalse(rows.getString(2).contains(privateValue))
                            }
                        }
                    }
                }
                assertEquals(value, store.take(token))
            }
        }

    @Test
    fun `encrypted expiry cannot be extended by changing the index column`() =
        withBlueskyOAuthDatabase { source ->
            val store = PostgreSQLBlueskyAccountHandoffStore(source, cipher, clock)
            val token = store.issue(identity, clock.now() - 1.minutes)
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "UPDATE bluesky_account_handoffs SET expires_at = NOW() + INTERVAL '1 year'",
                    )
                }
                connection.commit()
            }
            assertNull(store.take(token))
            assertNull(store.take(token))
            assertNull(store.take("invalid"))
        }

    @Test
    fun `swapping encrypted handoffs is rejected and both values are spent`() =
        withBlueskyOAuthDatabase { source ->
            val store = PostgreSQLBlueskyAccountHandoffStore(source, cipher, clock)
            val one = store.issue(identity, clock.now() + 5.minutes)
            val two = store.issue(identity.copy(accountId = "other-account"), clock.now() + 5.minutes)
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "UPDATE bluesky_account_handoffs a SET encrypted_payload = b.encrypted_payload " +
                            "FROM bluesky_account_handoffs b WHERE a.handoff_key <> b.handoff_key",
                    )
                }
                connection.commit()
            }
            assertFailsWith<IllegalArgumentException> { store.take(one) }
            assertFailsWith<IllegalArgumentException> { store.take(two) }
            assertNull(store.take(one))
            assertNull(store.take(two))
        }

    @Test
    fun `old encryption key remains readable after rotation and new handoffs use the active key`() =
        withBlueskyOAuthDatabase { source ->
            val old = PostgreSQLBlueskyAccountHandoffStore(source, cipher, clock)
            val oldToken = old.issue(identity, clock.now() + 5.minutes)
            val rotated = PostgreSQLBlueskyAccountHandoffStore(source, AtRestKeyCipher("new-handoff-key", listOf("old-handoff-key")), clock)
            assertEquals(identity, rotated.take(oldToken))
            val token = rotated.issue(identity, clock.now() + 5.minutes)
            assertFailsWith<IllegalArgumentException> { old.take(token) }
            assertNull(rotated.take(token), "unreadable handoffs are consumed, never replayed")
        }

    private val did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    private val identity = BlueskyAccountHandoff.Identity(did, null)
    private val cipher = AtRestKeyCipher("old-handoff-key")
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-25T00:00:00Z")
        }
}
