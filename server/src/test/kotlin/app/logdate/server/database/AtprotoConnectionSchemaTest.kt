package app.logdate.server.database

import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtprotoConnectionSchemaTest {
    @Test
    fun `lifecycle and disconnect decisions have independent durable storage`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                for (table in listOf("atproto_connections", "atproto_disconnect_decisions")) {
                    connection.prepareStatement("SELECT to_regclass(?)").use { statement ->
                        statement.setString(1, table)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertNotNull(rows.getString(1), "Connection lifecycle storage is missing: $table")
                        }
                    }
                }
            }
        }

    @Test
    fun `lifecycle states are bounded and persisted generations start above zero`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                assertTrue(connection.columns("atproto_connections").containsAll(setOf("did", "state", "revision", "observed_at")))
                for (state in listOf("connected", "expired", "revoked", "reconnect_required", "disconnected")) {
                    val did = "did:plc:" + state.replace("_", "").padEnd(24, 'a').take(24)
                    connection.insertState(did, state, 1)
                }
                connection.commit()

                val invalidState =
                    assertFailsWith<SQLException> {
                        connection.insertState("did:plc:" + "b".repeat(24), "provider_raw_error", 1)
                    }
                assertEquals("23514", invalidState.sqlState, "The state must fail a CHECK constraint")
                connection.rollback()

                val invalidRevision =
                    assertFailsWith<SQLException> {
                        connection.insertState("did:plc:" + "b".repeat(24), "connected", 0)
                    }
                assertEquals("23514", invalidRevision.sqlState, "Zero denotes missing history, never a persisted generation")
                connection.rollback()
            }
        }

    @Test
    fun `disconnect records bind an account revision and challenge without storing credentials`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                val columns = connection.columns("atproto_disconnect_decisions")
                assertTrue(
                    columns.containsAll(
                        setOf(
                            "decision_key",
                            "account_id",
                            "did",
                            "expected_revision",
                            "challenge_hash",
                            "created_at",
                            "expires_at",
                            "authorized_at",
                            "completed_at",
                            "disconnected_revision",
                            "remote_confirmed",
                        ),
                    ),
                    "Disconnect decisions must survive restart with their authority and completion boundary",
                )
                assertFalse(columns.any { it.contains("token") || it.contains("assertion") || it.contains("provider_error") })
                connection
                    .prepareStatement(
                        "SELECT is_nullable FROM information_schema.columns " +
                            "WHERE table_schema = 'public' AND table_name = 'atproto_disconnect_decisions' AND column_name = 'account_id'",
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals("NO", rows.getString(1))
                        }
                    }
            }
        }

    private fun Connection.columns(table: String): Set<String> =
        prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?").use {
            it.setString(1, table)
            it.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }

    private fun Connection.insertState(
        did: String,
        state: String,
        revision: Long,
    ) {
        prepareStatement(
            "INSERT INTO atproto_connections(did, state, revision, observed_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
        ).use {
            it.setString(1, did)
            it.setString(2, state)
            it.setLong(3, revision)
            assertEquals(1, it.executeUpdate())
        }
    }
}
