package app.logdate.server.bluesky

import app.logdate.server.database.withBlueskyOAuthDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthSessionEndedException
import studio.hypertext.atproto.syntax.Handle
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BlueskyProviderPostgresTest {
    @Test
    fun `a confidential sign-in survives restarts and only the originating app exchanges it`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val first = fixture.newClient()
                val start = fixture.service(first).start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                fixture.stop(first)
                val callback = fixture.atmosphere.approve(start.authorizationUrl)
                val second = fixture.newClient()
                assertEquals(start.attemptId, fixture.service(second).callback(callback))
                fixture.stop(second)
                val third = fixture.service()
                assertNull(third.exchange(start.attemptId, "wrong-verifier-which-has-the-right-minimum-length-12345"))
                val verified = assertNotNull(third.exchange(start.attemptId, verifier))
                assertEquals(fixture.atmosphere.alice.did, verified.did.toString())
                assertNull(verified.accountId)
                assertNull(third.exchange(start.attemptId, verifier))
                val grant = fixture.atmosphere.grantsFor(verified.did.toString()).single()
                assertEquals("atproto", grant.scope)
                assertTrue(fixture.atmosphere.requestLog.none { "/xrpc/" in it }, "sign-in never writes repository records")
                assertFalse(
                    fixture.config
                        .toClientMetadata()
                        .jwks!!
                        .keys
                        .any { it.isPrivate },
                )
                assertEquals(BlueskySignInFailureKind.EXPIRED, assertFailsWith<BlueskySignInFailure> { third.callback(callback) }.kind)
            }
        }

    @Test
    fun `provider errors return only the verified originating app binding`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val service = fixture.service()
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                val parameters =
                    fixture.atmosphere.deny(start.authorizationUrl) +
                        mapOf(
                            "error" to "server_error",
                            "error_description" to "private-provider-diagnostic",
                        )
                val failure = assertFailsWith<BlueskySignInFailure> { service.callback(parameters) }
                assertEquals(BlueskySignInFailureKind.UNAVAILABLE, failure.kind)
                assertEquals(start.attemptId, failure.verifiedAttemptId)
                assertFalse(failure.toString().contains("private-provider-diagnostic"))
                assertEquals(
                    BlueskySignInFailureKind.UNAVAILABLE,
                    assertFailsWith<BlueskySignInFailure> {
                        service.exchange(start.attemptId, verifier)
                    }.kind,
                )
                assertNull(service.exchange(start.attemptId, verifier))
            }
        }

    @Test
    fun `declined authorization creates no session or authenticated app result`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val service = fixture.service()
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                val denied = fixture.atmosphere.deny(start.authorizationUrl).plus("error_description" to "secret-provider-marker")
                val error = assertFailsWith<BlueskySignInFailure> { service.callback(denied) }
                assertEquals(BlueskySignInFailureKind.DECLINED, error.kind)
                assertEquals(start.attemptId, error.verifiedAttemptId)
                assertFalse(error.toString().contains("secret-provider-marker"))
                assertNull(error.cause)
                assertNull(fixture.sessions.get(AtprotoDid.require(fixture.atmosphere.alice.did)))
                assertEquals(
                    BlueskySignInFailureKind.DECLINED,
                    assertFailsWith<BlueskySignInFailure> {
                        service.exchange(start.attemptId, verifier)
                    }.kind,
                )
                assertNull(service.exchange(start.attemptId, verifier))
            }
        }

    @Test
    fun `issuer substitution or provider failure cannot authenticate an app`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val service = fixture.service()
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                val callback = fixture.atmosphere.approve(start.authorizationUrl).plus("iss" to "https://other.example.net")
                assertEquals(
                    BlueskySignInFailureKind.UNAVAILABLE,
                    assertFailsWith<BlueskySignInFailure> { service.callback(callback) }.kind,
                )
                assertNull(service.exchange(start.attemptId, verifier))
                assertNull(fixture.sessions.get(AtprotoDid.require(fixture.atmosphere.alice.did)))
                fixture.atmosphere.parError = "invalid_scope"
                assertEquals(
                    BlueskySignInFailureKind.UNAVAILABLE,
                    assertFailsWith<BlueskySignInFailure> {
                        service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                    }.kind,
                )
                assertTrue(fixture.atmosphere.grantsFor(fixture.atmosphere.alice.did).isEmpty())
            }
        }

    @Test
    fun `an expired browser request cannot create a session`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val service = fixture.service()
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                val callback = fixture.atmosphere.approve(start.authorizationUrl)
                fixture.atmosphere.clock.advance(6.minutes)
                assertEquals(BlueskySignInFailureKind.EXPIRED, assertFailsWith<BlueskySignInFailure> { service.callback(callback) }.kind)
                assertNull(fixture.sessions.get(AtprotoDid.require(fixture.atmosphere.alice.did)))
                assertNull(service.exchange(start.attemptId, verifier))
            }
        }

    @Test
    fun `production refresh locks prevent reuse across instances and revocation remains visible`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val clientOne = fixture.newClient()
                val service = fixture.service(clientOne)
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                service.callback(fixture.atmosphere.approve(start.authorizationUrl))
                val did = assertNotNull(service.exchange(start.attemptId, verifier)).did
                val clientTwo = fixture.newClient()
                fixture.atmosphere.clock.advance(6.minutes)
                val refreshed =
                    coroutineScope {
                        listOf(clientOne, clientTwo, clientOne, clientTwo)
                            .map { client ->
                                async { client.restore(did).tokenInfo() }
                            }.awaitAll()
                    }
                assertTrue(refreshed.all { it.did == did && it.scope == "atproto" })
                val grant = fixture.atmosphere.grantsFor(did.toString()).single()
                assertEquals(1, grant.usedRefreshTokens.size)
                assertFalse(grant.revoked)
                fixture.atmosphere.revokeAllSessions(did.toString())
                fixture.atmosphere.clock.advance(6.minutes)
                assertFailsWith<OAuthSessionEndedException> { clientTwo.restore(did) }
                assertNull(fixture.sessions.get(did))
            }
        }

    @Test
    fun `a provider cannot expand sign-in permissions before app enrollment`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val service = fixture.service()
                val start = service.start(Handle.require(fixture.atmosphere.alice.handle), challenge)
                fixture.atmosphere.grantedScopeOverride = "atproto transition:generic"
                assertEquals(
                    BlueskySignInFailureKind.UNAVAILABLE,
                    assertFailsWith<BlueskySignInFailure> {
                        service.callback(fixture.atmosphere.approve(start.authorizationUrl))
                    }.kind,
                )
                assertNull(service.exchange(start.attemptId, verifier))
                assertNull(fixture.sessions.get(AtprotoDid.require(fixture.atmosphere.alice.did)))
                assertTrue(
                    fixture.atmosphere
                        .grantsFor(fixture.atmosphere.alice.did)
                        .single()
                        .revoked,
                )
            }
        }

    @Test
    fun `expiry during provider verification preserves the old session and revokes only the new grant`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val service = fixture.service()
                val handle = Handle.require(fixture.atmosphere.alice.handle)
                val originalStart = service.start(handle, challenge)
                service.callback(fixture.atmosphere.approve(originalStart.authorizationUrl))
                val did = AtprotoDid.require(fixture.atmosphere.alice.did)
                val original = assertNotNull(fixture.sessions.get(did))
                val next = service.start(handle, challenge)
                var advanced = false
                fixture.atmosphere.beforeRequest = { request ->
                    if (!advanced && request.url.host == "plc.directory") {
                        advanced = true
                        fixture.atmosphere.clock.advance(6.minutes)
                    }
                }
                assertEquals(
                    BlueskySignInFailureKind.EXPIRED,
                    assertFailsWith<BlueskySignInFailure> {
                        service.callback(fixture.atmosphere.approve(next.authorizationUrl))
                    }.kind,
                )
                assertTrue(advanced, "the deadline is crossed after tokens were issued, during identity verification")
                assertEquals(original, fixture.sessions.get(did))
                assertEquals(listOf(false, true), fixture.atmosphere.grantsFor(did.toString()).map { it.revoked })
                assertNull(service.exchange(next.attemptId, verifier))
            }
        }

    @Test
    fun `the sign-in adapter refuses a client with broader published permissions`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { fixture ->
                val client = fixture.newClient(fixture.config.copy(scope = "atproto transition:generic"))
                assertFailsWith<IllegalArgumentException> { AtprotoBlueskySignInProvider(client) }
            }
        }

    private val verifier = "originating-app-verifier-with-sufficient-entropy-for-a-fixture-123456"
    private val challenge =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
        )
}
