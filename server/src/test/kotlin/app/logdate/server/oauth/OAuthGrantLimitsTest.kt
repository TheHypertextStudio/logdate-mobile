package app.logdate.server.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Limits on what an OAuth grant can do and how long it lasts: only enforced scopes, one use per
 * PKCE challenge and per request, short access tokens, and grants that end when the account says so.
 */
class OAuthGrantLimitsTest {
    @Test
    fun `a grant only lists the scopes the server enforces`() =
        runTest {
            val harness = Harness(metadataScope = "atproto transition:generic transition:email")

            val requestUri = harness.push(scope = "atproto transition:email transition:generic").requestUri

            assertEquals("atproto transition:generic", harness.service.describeAuthorizationRequest(requestUri).scope)
        }

    @Test
    fun `a PKCE challenge can only start one request`() =
        runTest {
            val harness = Harness()
            harness.push(verifier = "example-verifier-reused")

            val reused = assertFailsWith<OAuthInvalidRequestException> { harness.push(verifier = "example-verifier-reused") }

            assertEquals("invalid_request", reused.error)
        }

    @Test
    fun `a request already decided elsewhere cannot be approved`() =
        runTest {
            val repository = ClaimLosingRepository()
            val harness = Harness(repository = repository)
            val requestUri = harness.push().requestUri
            repository.loseNextClaim = true

            assertFailsWith<OAuthInvalidRequestException> {
                harness.service.completeAuthorization(requestUri, "did:plc:alice123", "alice.logdate.app", approved = true)
            }
            assertEquals(0, repository.savedCodes)
        }

    @Test
    fun `access tokens last fifteen minutes`() =
        runTest {
            val harness = Harness()

            val tokens = harness.signIn("did:plc:alice123")

            assertEquals(900L, tokens.expires_in)
        }

    @Test
    fun `revoking an account's grants ends every app's refresh token`() =
        runTest {
            val harness = Harness()
            val alice = harness.signIn("did:plc:alice123")
            val bob = harness.signIn("did:plc:bob456")

            assertEquals(1, harness.service.revokeAllForSubject("did:plc:alice123"))

            assertFailsWith<OAuthInvalidGrantException> { harness.refresh(alice.refresh_token) }
            assertEquals("did:plc:bob456", harness.refresh(bob.refresh_token).sub)
        }

    @Test
    fun `an untrusted app's refresh token ends after two weeks`() =
        runTest {
            val harness = Harness()
            val tokens = harness.signIn("did:plc:alice123")

            harness.clock.nowValue = harness.clock.nowValue + 15.days

            assertFailsWith<OAuthInvalidGrantException> { harness.refresh(tokens.refresh_token) }
        }

    @Test
    fun `a trusted app's refresh token lasts longer`() =
        runTest {
            val harness = Harness(trusted = true)
            val tokens = harness.signIn("did:plc:alice123")

            harness.clock.nowValue = harness.clock.nowValue + 15.days

            assertEquals("did:plc:alice123", harness.refresh(tokens.refresh_token).sub)
        }

    @Test
    fun `a DPoP proof cannot be used twice`() =
        runBlocking<Unit> {
            val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
            val verifier = OAuthDpopVerifier(clock = clock)
            val proof = createDpopProof(generateP256KeyPair(), "POST", HTU_TOKEN, iat = clock.now().epochSeconds)

            verifier.verify(proof, "POST", HTU_TOKEN).getOrThrow()
            val replay = verifier.verify(proof, "POST", HTU_TOKEN).exceptionOrNull()

            assertEquals("DPoP proof has already been used", replay?.message)
        }

    @Test
    fun `the in-memory replay store expires each value on its own lifetime`() =
        runBlocking<Unit> {
            val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
            val store = InMemoryOAuthReplayStore(clock)
            assertTrue(store.firstUse("pkce:long", 60.minutes))
            assertTrue(store.firstUse("dpop:short", 1.minutes))

            clock.nowValue = clock.nowValue + 2.minutes

            assertTrue(store.firstUse("dpop:short", 1.minutes), "the short-lived value expired")
            assertFalse(store.firstUse("pkce:long", 60.minutes), "the long-lived value is still remembered")
        }

    @Test
    fun `a full in-memory replay store forgets the value that expires soonest`() =
        runBlocking<Unit> {
            val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
            val store = InMemoryOAuthReplayStore(clock, maxEntries = 2)
            store.firstUse("pkce:long", 60.minutes)
            store.firstUse("dpop:short", 1.minutes)

            store.firstUse("dpop:another", 1.minutes)

            assertFalse(store.firstUse("pkce:long", 60.minutes))
        }

    @Test
    fun `a client assertion used through one server instance is refused by another`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
            val sharedStore = InMemoryOAuthReplayStore(clock)
            val clientKey = generateP256KeyPair()
            val resolver = {
                OAuthClientMetadataResolver(
                    httpClient =
                        HttpClient(
                            MockEngine {
                                respond(
                                    content =
                                        clientMetadataJson(
                                            clientId = CLIENT_ID,
                                            redirectUri = REDIRECT_URI,
                                            tokenEndpointAuthMethod = "private_key_jwt",
                                            tokenEndpointAuthSigningAlg = "ES256",
                                            jwksJson = clientJwksJson(clientKey),
                                        ),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                                )
                            },
                        ),
                    clock = clock,
                    usedClientAssertions = sharedStore,
                )
            }
            val assertion = createClientAssertion(clientKey, CLIENT_ID, ISSUER, iat = clock.now().epochSeconds, jti = "shared-jti")
            val authenticate = { instance: OAuthClientMetadataResolver ->
                kotlinx.coroutines.runBlocking {
                    instance.authenticateClient(
                        clientId = CLIENT_ID,
                        clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                        clientAssertion = assertion,
                        authorizationServerIssuer = ISSUER,
                    )
                }
            }

            assertTrue(authenticate(resolver()).isSuccess)
            assertIs<OAuthInvalidClientException>(authenticate(resolver()).exceptionOrNull())
        }

    private class Harness(
        metadataScope: String = "atproto transition:generic",
        trusted: Boolean = false,
        val repository: OAuthRuntimeStateRepository = InMemoryOAuthRuntimeStateRepository(),
    ) {
        val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
        private val dpopKey: KeyPair = generateP256KeyPair()
        private var nextVerifier = 0
        val service =
            OAuthAuthorizationService(
                clientMetadataResolver =
                    OAuthClientMetadataResolver(
                        httpClient =
                            HttpClient(
                                MockEngine {
                                    respond(
                                        content =
                                            clientMetadataJson(
                                                clientId = CLIENT_ID,
                                                redirectUri = REDIRECT_URI,
                                                scope = metadataScope,
                                            ),
                                        status = HttpStatusCode.OK,
                                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                                    )
                                },
                            ),
                        clock = clock,
                    ),
                dpopVerifier = OAuthDpopVerifier(clock = clock),
                accessTokenService = OAuthAccessTokenService(OAuthConfig(issuer = ISSUER), OAuthKeyService(), clock),
                nonceService = OAuthNonceService(clock = clock),
                authorizationServerIssuer = ISSUER,
                runtimeStateRepository = repository,
                clock = clock,
                trustedClientIds = if (trusted) setOf(CLIENT_ID) else emptySet(),
            )

        suspend fun push(
            scope: String = "atproto",
            verifier: String = "example-verifier-${nextVerifier++}",
        ) = service.createPushedAuthorizationRequest(
            clientId = CLIENT_ID,
            redirectUri = REDIRECT_URI,
            scope = scope,
            responseType = "code",
            codeChallenge = challengeFor(verifier),
            codeChallengeMethod = "S256",
            state = null,
            loginHint = null,
            dpopProof = createDpopProof(dpopKey, "POST", "$ISSUER/oauth/par", iat = clock.now().epochSeconds),
            htu = "$ISSUER/oauth/par",
        )

        suspend fun signIn(did: String): studio.hypertext.atproto.pds.OAuthTokenResponse {
            val verifier = "example-verifier-${nextVerifier++}"
            val requestUri = push(verifier = verifier).requestUri
            val redirect = service.completeAuthorization(requestUri, did, "${did.substringAfterLast(':')}.logdate.app", approved = true)
            return service.exchangeAuthorizationCode(
                code = redirect.substringAfter("code=").substringBefore('&'),
                redirectUri = REDIRECT_URI,
                clientId = CLIENT_ID,
                codeVerifier = verifier,
                dpopProof = createDpopProof(dpopKey, "POST", HTU_TOKEN, nonce = service.nonce(), iat = clock.now().epochSeconds),
                htu = HTU_TOKEN,
            )
        }

        suspend fun refresh(refreshToken: String) =
            service.exchangeRefreshToken(
                refreshToken = refreshToken,
                clientId = CLIENT_ID,
                dpopProof = createDpopProof(dpopKey, "POST", HTU_TOKEN, nonce = service.nonce(), iat = clock.now().epochSeconds),
                htu = HTU_TOKEN,
            )
    }

    /** Behaves like the real store, except that a decision can lose the race to remove its request. */
    private class ClaimLosingRepository(
        private val delegate: InMemoryOAuthRuntimeStateRepository = InMemoryOAuthRuntimeStateRepository(),
    ) : OAuthRuntimeStateRepository by delegate {
        var loseNextClaim = false
        var savedCodes = 0

        override suspend fun deleteAuthorizationRequest(requestUri: String): Boolean {
            if (!loseNextClaim) return delegate.deleteAuthorizationRequest(requestUri)
            loseNextClaim = false
            return false
        }

        override suspend fun saveAuthorizationCode(code: StoredAuthorizationCode): StoredAuthorizationCode {
            savedCodes += 1
            return delegate.saveAuthorizationCode(code)
        }
    }

    private companion object {
        const val ISSUER = "https://logdate.app"
        const val CLIENT_ID = "https://viewer.example.com/client.json"
        const val REDIRECT_URI = "https://viewer.example.com/callback"
        const val HTU_TOKEN = "$ISSUER/oauth/token"

        fun challengeFor(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}
