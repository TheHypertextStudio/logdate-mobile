package app.logdate.server.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pins that pushed authorization requests and the grants they lead to don't accumulate: expired
 * state is removed as new requests arrive, at most once per interval.
 */
class OAuthRuntimeStatePurgeTest {
    private val clientId = "https://viewer.example.com/client.json"
    private val redirectUri = "https://viewer.example.com/callback"

    @Test
    fun `new pushed authorization requests remove expired ones at most once per interval`() =
        kotlinx.coroutines.test.runTest {
            val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
            val purges = mutableListOf<Instant>()
            val store = InMemoryOAuthRuntimeStateRepository()
            val repository =
                object : OAuthRuntimeStateRepository by store {
                    override suspend fun purgeExpired(now: Instant): Int {
                        purges += now
                        return store.purgeExpired(now)
                    }
                }
            val service = service(clock, repository)
            val keyPair = generateP256KeyPair()
            var verifier = 0

            suspend fun push() =
                service.createPushedAuthorizationRequest(
                    clientId = clientId,
                    redirectUri = redirectUri,
                    scope = "atproto",
                    responseType = "code",
                    codeChallenge = "challenge-${verifier++}",
                    codeChallengeMethod = "S256",
                    state = null,
                    loginHint = null,
                    dpopProof = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/par", iat = clock.now().epochSeconds),
                    htu = "https://logdate.app/oauth/par",
                )

            val first = push()
            assertEquals(1, purges.size)

            clock.nowValue = clock.nowValue + 6.minutes
            push()
            assertEquals(1, purges.size, "a second purge waits for the interval")
            assertNotNull(repository.findAuthorizationRequest(first.requestUri))

            clock.nowValue = clock.nowValue + 5.minutes
            val latest = push()
            assertEquals(2, purges.size)
            assertNull(repository.findAuthorizationRequest(first.requestUri), "the expired request is gone")
            assertNotNull(repository.findAuthorizationRequest(latest.requestUri))
        }

    @Test
    fun `a failed purge doesn't fail the request`() =
        kotlinx.coroutines.test.runTest {
            val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
            val repository =
                object : OAuthRuntimeStateRepository by InMemoryOAuthRuntimeStateRepository() {
                    override suspend fun purgeExpired(now: Instant): Int = error("database unavailable")
                }
            val pushed =
                service(clock, repository).createPushedAuthorizationRequest(
                    clientId = clientId,
                    redirectUri = redirectUri,
                    scope = "atproto",
                    responseType = "code",
                    codeChallenge = "challenge",
                    codeChallengeMethod = "S256",
                    state = null,
                    loginHint = null,
                    dpopProof =
                        createDpopProof(generateP256KeyPair(), "POST", "https://logdate.app/oauth/par", iat = clock.now().epochSeconds),
                    htu = "https://logdate.app/oauth/par",
                )

            assertNotNull(pushed.requestUri)
        }

    private fun service(
        clock: MutableClock,
        repository: OAuthRuntimeStateRepository,
    ): OAuthAuthorizationService {
        val engine =
            MockEngine {
                respond(
                    clientMetadataJson(clientId, redirectUri),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        return OAuthAuthorizationService(
            clientMetadataResolver = OAuthClientMetadataResolver(httpClient = HttpClient(engine), clock = clock),
            dpopVerifier = OAuthDpopVerifier(clock = clock),
            accessTokenService = OAuthAccessTokenService(OAuthConfig(issuer = "https://logdate.app"), OAuthKeyService(), clock),
            nonceService = OAuthNonceService(clock = clock),
            authorizationServerIssuer = "https://logdate.app",
            runtimeStateRepository = repository,
            clock = clock,
        )
    }
}
