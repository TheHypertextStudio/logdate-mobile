@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky

import app.logdate.server.bluesky.connection.AtprotoConnectionFailure
import app.logdate.server.bluesky.connection.AtprotoReconnectService
import app.logdate.server.bluesky.connection.ConnectionFailureKind
import app.logdate.server.routes.withAtprotoConnectionHttp
import app.logdate.shared.model.AtprotoConnectionState
import studio.hypertext.atproto.oauth.client.OAuthSessionStore
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AtprotoReconnectServiceTest {
    @Test
    fun `reconnect restores the existing DID without issuing a LogDate session`() =
        withAtprotoConnectionHttp { f ->
            val attempts = f.provider.newAttempts()
            val reconnect =
                AtprotoReconnectService(
                    f.accounts,
                    attempts,
                    AtprotoBlueskySignInProvider(f.oauth),
                    f.connectionService,
                    f.provider.atmosphere.clock,
                )
            val start = reconnect.start(f.account.id, challenge)
            val callback = f.provider.service(f.oauth, attempts).callback(f.provider.atmosphere.approve(start.authorizationUrl))
            assertEquals(start.attemptId, callback)
            assertNull(f.provider.service(f.oauth, attempts).exchange(start.attemptId, verifier))
            val current = reconnect.complete(f.account.id, start.attemptId, verifier)
            assertEquals(AtprotoConnectionState.CONNECTED, current.state)
            assertTrue(current.revision > 1)
            assertEquals(f.account.id.toString(), f.tokens.validateAccessToken(f.token))
            assertEquals(f.oauthDid.toString(), f.accounts.findById(f.account.id)?.did)
            assertEquals(listOf(f.key.credentialIdB64), f.webAuthn.getUserCredentials(f.account.id))
            assertFailsWith<AtprotoConnectionFailure> { reconnect.complete(f.account.id, start.attemptId, verifier) }
        }

    @Test
    fun `hosted or removed accounts never authorize reconnect`() =
        withAtprotoConnectionHttp(hosted = true) { f ->
            val reconnect =
                AtprotoReconnectService(f.accounts, f.provider.newAttempts(), AtprotoBlueskySignInProvider(f.oauth), f.connectionService)
            val before = f.provider.atmosphere.requestLog.size
            assertEquals(
                ConnectionFailureKind.INELIGIBLE,
                assertFailsWith<AtprotoConnectionFailure> { reconnect.start(f.account.id, challenge) }.kind,
            )
            assertEquals(
                ConnectionFailureKind.INELIGIBLE,
                assertFailsWith<AtprotoConnectionFailure> { reconnect.start(Uuid.random(), challenge) }.kind,
            )
            assertEquals(before, f.provider.atmosphere.requestLog.size)
        }

    @Test
    fun `reconnect identity proof without retained provider access reports reconnect required`() =
        withAtprotoConnectionHttp { f ->
            val attempts = f.provider.newAttempts()
            val failing =
                f.provider.newClient(
                    sessionStore =
                        object : OAuthSessionStore by f.provider.sessions {
                            override suspend fun save(session: OAuthStoredSession) {
                                error("synthetic persistence failure")
                            }
                        },
                    requireAuthorizationAfterPdsChange = true,
                )
            val reconnect =
                AtprotoReconnectService(
                    f.accounts,
                    attempts,
                    AtprotoBlueskySignInProvider(failing),
                    f.connectionService,
                    f.provider.atmosphere.clock,
                )
            val start = reconnect.start(f.account.id, challenge)
            assertFailsWith<BlueskySignInFailure> {
                f.provider.service(failing, attempts).callback(f.provider.atmosphere.approve(start.authorizationUrl))
            }
            assertEquals(AtprotoConnectionState.RECONNECT_REQUIRED, reconnect.complete(f.account.id, start.attemptId, verifier).state)
            assertNotNull(f.accounts.findById(f.account.id))
            assertEquals(f.account.id.toString(), f.tokens.validateAccessToken(f.token))
        }

    private companion object {
        val verifier = "v".repeat(64)
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
            )
    }
}
