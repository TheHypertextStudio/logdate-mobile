package app.logdate.server.database

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BlueskyOAuthSchemaTest {
    @Test
    fun `connection enablement requires the dedicated action proof purpose`() =
        withBlueskyOAuthDatabase { source ->
            source.blueskyTransaction {
                createStatement().use {
                    it.execute("ALTER TABLE webauthn_challenges DROP CONSTRAINT challenge_type_valid")
                    it.execute(
                        "ALTER TABLE webauthn_challenges ADD CONSTRAINT challenge_type_valid CHECK (challenge_type IN ('registration', 'authentication', 'oauth_authorize', 'step_up', 'step_up_grant', 'bluesky_signin'))",
                    )
                }
            }
            assertFailsWith<IllegalStateException> { requireAtprotoConnectionSchema(source) }
        }

    @Test
    fun `connection enablement refuses missing proof constraint`() =
        withBlueskyOAuthDatabase { source ->
            source.blueskyTransaction {
                createStatement().use { it.execute("ALTER TABLE webauthn_challenges DROP CONSTRAINT challenge_type_valid") }
            }
            assertFailsWith<IllegalStateException> { requireAtprotoConnectionSchema(source) }
        }

    @Test
    fun `upgraded connection schema can be checked without mutation`() =
        withBlueskyOAuthDatabase { source -> requireAtprotoConnectionSchema(source) }

    @Test
    fun `missing connection generation storage stops startup before token writes`() =
        withBlueskyOAuthDatabase { source ->
            source.blueskyTransaction { createStatement().use { it.execute("DROP TABLE atproto_connections") } }
            assertFailsWith<IllegalStateException> { requireBlueskyOAuthSchema(source) }
        }

    @Test
    fun `missing disconnect receipt fields stop startup`() =
        withBlueskyOAuthDatabase { source ->
            source.blueskyTransaction {
                createStatement().use { it.execute("ALTER TABLE atproto_disconnect_decisions DROP COLUMN remote_confirmed CASCADE") }
            }
            assertFailsWith<IllegalStateException> { requireBlueskyOAuthSchema(source) }
        }

    @Test
    fun `an upgraded database has the OAuth columns required at startup`() =
        withBlueskyOAuthDatabase { source ->
            requireBlueskyOAuthSchema(source)
        }

    @Test
    fun `missing OAuth storage stops startup`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE bluesky_oauth_sessions") }
                connection.commit()
            }
            assertFailsWith<IllegalStateException> { requireBlueskyOAuthSchema(source) }
        }

    @Test
    fun `missing proof storage columns stop startup`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER TABLE bluesky_sign_in_attempts DROP COLUMN encrypted_payload") }
                connection.commit()
            }
            assertFailsWith<IllegalStateException> { requireBlueskyOAuthSchema(source) }
        }

    @Test
    fun `missing account identity kind stops startup`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER TABLE accounts DROP COLUMN identity_kind") }
                connection.commit()
            }
            assertFailsWith<IllegalStateException> { requireBlueskyOAuthSchema(source) }
        }

    @Test
    fun `missing account handoff storage stops startup`() =
        withBlueskyOAuthDatabase { source ->
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE bluesky_account_handoffs") }
                connection.commit()
            }
            assertFailsWith<IllegalStateException> { requireBlueskyOAuthSchema(source) }
        }
}
