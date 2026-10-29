package app.logdate.server.bluesky

import app.logdate.server.database.withBlueskyOAuthDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthSessionStore
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import studio.hypertext.atproto.syntax.Handle
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueskyCallbackAcceptancePostgresTest {
    @Test
    fun `verified identity proof survives failure to retain a provider session`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val failing =
                    object : OAuthSessionStore by fixture.sessions {
                        override suspend fun save(session: OAuthStoredSession): Unit =
                            throw IllegalStateException("private provider-storage marker")
                    }
                val service = fixture.service(fixture.newClient(sessionStore = failing))
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                val error =
                    assertFailsWith<BlueskySignInFailure> {
                        service.callback(fixture.atmosphere.approve(start.authorizationUrl))
                    }
                assertEquals(BlueskySignInFailureKind.UNAVAILABLE, error.kind)
                assertNull(error.cause)
                val did = AtprotoDid.require(fixture.atmosphere.alice.did)
                assertNull(fixture.sessions.get(did))
                assertTrue(
                    fixture.atmosphere
                        .grantsFor(did.toString())
                        .single()
                        .revoked,
                )
                // This proves identity authentication only; no journal key or retained provider access.
                assertEquals(VerifiedBlueskySignIn(did, null), service.exchange(start.attemptId, verifier))
                assertNull(service.exchange(start.attemptId, verifier))
            }
        }

    @Test
    fun `cancellation after app proof completion revokes only the new grant and preserves identity proof`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val handle = Handle.require(fixture.atmosphere.alice.handle)
                val originalService = fixture.service()
                val originalStart = originalService.start(handle, challenge)
                originalService.callback(fixture.atmosphere.approve(originalStart.authorizationUrl))
                val did = AtprotoDid.require(fixture.atmosphere.alice.did)
                val original = assertNotNull(fixture.sessions.get(did))
                val underlying = fixture.newAttempts()
                val canceling =
                    object : BlueskySignInAttemptStore by underlying {
                        override suspend fun complete(
                            id: String,
                            did: AtprotoDid,
                        ): Boolean {
                            val accepted = underlying.complete(id, did)
                            check(accepted)
                            currentCoroutineContext().cancel(CancellationException("fixture cancellation after proof completion"))
                            currentCoroutineContext().ensureActive()
                            return accepted
                        }
                    }
                val service = fixture.service(attempts = canceling)
                val start = service.start(handle, challenge)
                coroutineScope {
                    val callback = launch { service.callback(fixture.atmosphere.approve(start.authorizationUrl)) }
                    callback.join()
                    assertTrue(callback.isCancelled)
                }
                assertEquals(original, fixture.sessions.get(did))
                assertEquals(listOf(false, true), fixture.atmosphere.grantsFor(did.toString()).map { it.revoked })
                assertEquals(VerifiedBlueskySignIn(did, null), service.exchange(start.attemptId, verifier))
            }
        }

    private val verifier = "original-app-proof-verifier-with-enough-fixture-entropy-1234567890"
    private val challenge =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
        )
}
