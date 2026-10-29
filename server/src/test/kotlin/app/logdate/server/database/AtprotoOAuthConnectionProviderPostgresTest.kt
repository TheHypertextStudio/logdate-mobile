package app.logdate.server.database

import app.logdate.server.bluesky.BlueskyProtocolHarness
import app.logdate.server.bluesky.connection.AtprotoOAuthConnectionProvider
import app.logdate.shared.model.AtprotoConnectionState
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation
import studio.hypertext.atproto.oauth.client.OAuthSessionEndReason
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AtprotoOAuthConnectionProviderPostgresTest {
    @Test
    fun `a current unexpired grant restores without spending another refresh token`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                val before = assertNotNull(h.sessions.get(did))
                assertTrue(AtprotoOAuthConnectionProvider(client, h.sessions).restore(did))
                assertEquals(before, h.sessions.get(did))
                assertEquals(1L, PostgreSQLAtprotoConnectionRepository(source).snapshot(did)?.revision)
            }
        }

    @Test
    fun `stale access is renewed through the existing confidential OAuth engine`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                val before = assertNotNull(h.sessions.get(did))
                h.atmosphere.clock.advance(10.minutes)
                assertTrue(AtprotoOAuthConnectionProvider(client, h.sessions).restore(did))
                assertFalse(before.accessToken == assertNotNull(h.sessions.get(did)).accessToken)
                assertEquals(2L, PostgreSQLAtprotoConnectionRepository(source).snapshot(did)?.revision)
            }
        }

    @Test
    fun `an unexpired grant requires new authorization after a PDS move under the same issuer`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                h.atmosphere.move(h.atmosphere.alice, h.atmosphere.secondPdsUrl)
                assertFalse(AtprotoOAuthConnectionProvider(client, h.sessions).restore(did))
                assertNull(h.sessions.get(did))
                assertEquals(AtprotoConnectionState.RECONNECT_REQUIRED, PostgreSQLAtprotoConnectionRepository(source).snapshot(did)?.state)
                assertTrue(h.atmosphere.requestLog.none { it.contains(h.atmosphere.secondPdsUrl + "/oauth/") })
            }
        }

    @Test
    fun `a verified issuer move never receives the previous issuers token`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                h.atmosphere.move(h.atmosphere.alice, h.atmosphere.otherPdsUrl)
                assertFalse(AtprotoOAuthConnectionProvider(client, h.sessions).restore(did))
                assertNull(h.sessions.get(did))
                assertEquals(AtprotoConnectionState.RECONNECT_REQUIRED, PostgreSQLAtprotoConnectionRepository(source).snapshot(did)?.state)
                assertTrue(h.atmosphere.requestLog.none { it.contains(h.atmosphere.otherIssuer + "/oauth/") })
            }
        }

    @Test
    fun `a resolution outage preserves the encrypted grant and durable state`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                val before = assertNotNull(h.sessions.get(did))
                h.atmosphere.beforeRequest = { throw IOException("synthetic resolution outage") }
                assertFailsWith<Exception> { AtprotoOAuthConnectionProvider(client, h.sessions).restore(did) }
                assertEquals(before, h.sessions.get(did))
                assertEquals(AtprotoConnectionState.CONNECTED, PostgreSQLAtprotoConnectionRepository(source).snapshot(did)?.state)
            }
        }

    @Test
    fun `conditional revocation confirms only the actual provider response and invokes local completion`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                val original = assertNotNull(h.sessions.get(did))
                val outcome =
                    AtprotoOAuthConnectionProvider(client, h.sessions).revokeIf(did, { true }, {
                        h.sessions.delete(did, OAuthSessionEndReason.REVOKED)
                    })
                assertEquals(OAuthConditionalRevocation.CONFIRMED, outcome)
                assertTrue(h.atmosphere.revokedTokens.contains(original.refreshToken))
                assertNull(h.sessions.get(did))
            }
        }

    @Test
    fun `a failed conditional guard contacts no revocation endpoint and preserves the grant`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { h ->
                val client = h.newClient(requireAuthorizationAfterPdsChange = true)
                val request = client.authorize(h.atmosphere.alice.handle)
                val did = client.callback(h.atmosphere.approve(request.url)).session.did
                val original = assertNotNull(h.sessions.get(did))
                val outcome =
                    AtprotoOAuthConnectionProvider(client, h.sessions).revokeIf(did, { false }, {
                        error("A refused guard cannot complete locally")
                    })
                assertEquals(OAuthConditionalRevocation.SKIPPED, outcome)
                assertEquals(original, h.sessions.get(did))
                assertTrue(h.atmosphere.revokedTokens.isEmpty())
            }
        }
}
