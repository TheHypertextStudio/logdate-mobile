package app.logdate.server.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import studio.hypertext.atproto.oauth.client.JvmOAuthHttp
import studio.hypertext.atproto.oauth.client.MAX_METADATA_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pins how the server fetches documents that an OAuth client names: its `client_id` metadata
 * document and its `jwks_uri`. Anyone can start a pushed authorization request, so these fetches
 * must stay on the public internet, must not follow redirects, and must read a bounded body.
 */
class OAuthClientDocumentFetchTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `loopback http client ids are refused unless local development allows them`() =
        kotlinx.coroutines.test.runTest {
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    respond(clientMetadataJson(request.url.toString(), "http://localhost:3000/callback"), HttpStatusCode.OK, jsonHeaders)
                }

            val production = OAuthClientMetadataResolver(httpClient = HttpClient(engine))
            val refused = production.resolve("http://localhost:3000/client.json").exceptionOrNull()
            assertIs<OAuthInvalidClientException>(refused)
            assertEquals(0, requests)

            val development = OAuthClientMetadataResolver(httpClient = HttpClient(engine), allowLoopbackClients = true)
            development.resolve("http://localhost:3000/client.json").getOrThrow()
            assertEquals(1, requests)
        }

    @Test
    fun `a client document that redirects is refused and the redirect is not followed`() =
        kotlinx.coroutines.test.runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += request.url.toString()
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://elsewhere.example.com/client.json"),
                    )
                }
            val resolver = OAuthClientMetadataResolver(httpClient = HttpClient(engine))

            val error = resolver.resolve("https://viewer.example.com/client.json").exceptionOrNull()

            assertIs<OAuthInvalidClientException>(error)
            assertEquals(listOf("https://viewer.example.com/client.json"), requested)
        }

    @Test
    fun `an oversized client document is refused`() =
        kotlinx.coroutines.test.runTest {
            val clientId = "https://viewer.example.com/client.json"
            val padding = " ".repeat(MAX_METADATA_BYTES.toInt())
            val engine =
                MockEngine {
                    respond(clientMetadataJson(clientId, "https://viewer.example.com/callback") + padding, HttpStatusCode.OK, jsonHeaders)
                }
            val resolver = OAuthClientMetadataResolver(httpClient = HttpClient(engine))

            val error = resolver.resolve(clientId).exceptionOrNull()

            assertIs<OAuthInvalidClientException>(error)
            assertTrue(error.message.orEmpty().contains("larger than"), error.message)
        }

    @Test
    fun `an oversized jwks document is refused`() =
        kotlinx.coroutines.test.runTest {
            val clientId = "https://viewer.example.com/client.json"
            val jwksUri = "https://viewer.example.com/jwks.json"
            val engine =
                MockEngine { request ->
                    if (request.url.toString() == jwksUri) {
                        respond("""{"keys":[]}""" + " ".repeat(MAX_METADATA_BYTES.toInt()), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            clientMetadataJson(
                                clientId = clientId,
                                redirectUri = "https://viewer.example.com/callback",
                                tokenEndpointAuthMethod = "private_key_jwt",
                                tokenEndpointAuthSigningAlg = "ES256",
                                jwksUri = jwksUri,
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            val resolver = OAuthClientMetadataResolver(httpClient = HttpClient(engine))

            assertIs<OAuthInvalidClientException>(resolver.resolve(clientId).exceptionOrNull())
        }

    @Test
    fun `client documents on non-public addresses are refused as invalid_client`() =
        kotlinx.coroutines.test.runTest {
            val resolver = OAuthClientMetadataResolver(httpClient = JvmOAuthHttp.publicInternetClient())

            listOf(
                "https://127.0.0.1:1/client.json",
                "https://169.254.169.254/client.json",
                "https://10.0.0.1/client.json",
            ).forEach { clientId ->
                val error = resolver.resolve(clientId).exceptionOrNull()
                assertIs<OAuthInvalidClientException>(error, "$clientId should be refused, got $error")
            }
        }

    @Test
    fun `the client metadata cache keeps a bounded number of entries and drops expired ones first`() =
        kotlinx.coroutines.test.runTest {
            val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    respond(
                        clientMetadataJson(request.url.toString(), "https://viewer.example.com/callback"),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            val resolver =
                OAuthClientMetadataResolver(
                    httpClient = HttpClient(engine),
                    clock = clock,
                    cacheTtl = 10.minutes,
                    maxCachedClients = 2,
                )

            resolver.resolve("https://one.example.com/client.json").getOrThrow()
            clock.nowValue = clock.nowValue + 6.minutes
            resolver.resolve("https://two.example.com/client.json").getOrThrow()
            resolver.resolve("https://three.example.com/client.json").getOrThrow()
            assertEquals(2, resolver.cachedClientCount())

            resolver.resolve("https://three.example.com/client.json").getOrThrow()
            resolver.resolve("https://two.example.com/client.json").getOrThrow()
            assertEquals(3, requests, "the two newest clients stay cached")

            clock.nowValue = clock.nowValue + 11.minutes
            resolver.resolve("https://four.example.com/client.json").getOrThrow()
            assertEquals(1, resolver.cachedClientCount(), "expired entries are dropped before a new one is added")
        }

    @Test
    fun `a pushed authorization request without a valid DPoP proof fetches nothing`() =
        kotlinx.coroutines.test.runTest {
            val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    respond(
                        clientMetadataJson(request.url.toString(), "https://viewer.example.com/callback"),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            val keyService = OAuthKeyService()
            val service =
                OAuthAuthorizationService(
                    clientMetadataResolver = OAuthClientMetadataResolver(httpClient = HttpClient(engine), clock = clock),
                    dpopVerifier = OAuthDpopVerifier(clock = clock),
                    accessTokenService = OAuthAccessTokenService(OAuthConfig(issuer = "https://logdate.app"), keyService, clock),
                    nonceService = OAuthNonceService(clock = clock),
                    authorizationServerIssuer = "https://logdate.app",
                    clock = clock,
                )

            val error =
                runCatching {
                    service.createPushedAuthorizationRequest(
                        clientId = "https://viewer.example.com/client.json",
                        redirectUri = "https://viewer.example.com/callback",
                        scope = "atproto",
                        responseType = "code",
                        codeChallenge = "challenge",
                        codeChallengeMethod = "S256",
                        state = null,
                        loginHint = null,
                        dpopProof = "not-a-proof",
                        htu = "https://logdate.app/oauth/par",
                    )
                }.exceptionOrNull()

            assertIs<OAuthInvalidDpopProofException>(error)
            assertEquals(0, requests)
        }
}
