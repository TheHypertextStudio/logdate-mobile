package app.logdate.server.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Pins that the authorization server never stores a redeemable code or refresh token: each is kept
 * under the base64url SHA-256 of its value, and the grant still works end to end.
 */
class OAuthTokenStorageTest {
    private val clientId = "https://viewer.example.com/client.json"
    private val redirectUri = "https://viewer.example.com/callback"

    @Test
    fun `codes and refresh tokens are stored only as hashes and still redeem`() =
        kotlinx.coroutines.test.runTest {
            val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
            val savedCodes = mutableListOf<String>()
            val store = InMemoryOAuthRuntimeStateRepository()
            val repository =
                object : OAuthRuntimeStateRepository by store {
                    override suspend fun saveAuthorizationCode(code: StoredAuthorizationCode): StoredAuthorizationCode {
                        savedCodes += code.code
                        return store.saveAuthorizationCode(code)
                    }
                }
            val engine =
                MockEngine {
                    respond(
                        clientMetadataJson(clientId, redirectUri),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val service =
                OAuthAuthorizationService(
                    clientMetadataResolver = OAuthClientMetadataResolver(httpClient = HttpClient(engine), clock = clock),
                    dpopVerifier = OAuthDpopVerifier(clock = clock),
                    accessTokenService = OAuthAccessTokenService(OAuthConfig(issuer = "https://logdate.app"), OAuthKeyService(), clock),
                    nonceService = OAuthNonceService(clock = clock),
                    authorizationServerIssuer = "https://logdate.app",
                    runtimeStateRepository = repository,
                    clock = clock,
                )
            val key = generateP256KeyPair()

            fun proof(path: String) =
                createDpopProof(key, "POST", "https://logdate.app$path", nonce = service.nonce(), iat = clock.now().epochSeconds)

            val par =
                service.createPushedAuthorizationRequest(
                    clientId = clientId,
                    redirectUri = redirectUri,
                    scope = "atproto",
                    responseType = "code",
                    codeChallenge = challengeFor("storage-verifier"),
                    codeChallengeMethod = "S256",
                    state = null,
                    loginHint = null,
                    dpopProof = createDpopProof(key, "POST", "https://logdate.app/oauth/par", iat = clock.now().epochSeconds),
                    htu = "https://logdate.app/oauth/par",
                )
            val redirect =
                service.completeAuthorization(
                    requestUri = par.requestUri,
                    subjectDid = "did:plc:alice123",
                    subjectHandle = "alice.logdate.app",
                    approved = true,
                )
            val code = redirect.substringAfter("code=").substringBefore('&')
            assertEquals(listOf(sha256(code)), savedCodes, "the code is saved only as its hash")

            val tokens =
                service.exchangeAuthorizationCode(
                    code = code,
                    redirectUri = redirectUri,
                    clientId = clientId,
                    codeVerifier = "storage-verifier",
                    dpopProof = proof("/oauth/token"),
                    htu = "https://logdate.app/oauth/token",
                )
            assertNull(store.findRefreshToken(tokens.refresh_token), "the refresh token itself is not a key")
            assertNotNull(store.findRefreshToken(sha256(tokens.refresh_token)))

            val refreshed =
                service.exchangeRefreshToken(
                    refreshToken = tokens.refresh_token,
                    clientId = clientId,
                    dpopProof = proof("/oauth/token"),
                    htu = "https://logdate.app/oauth/token",
                )
            assertNull(store.findRefreshToken(sha256(tokens.refresh_token)), "rotation removes the old token")
            assertNull(store.findRefreshToken(refreshed.refresh_token))
            assertNotNull(store.findRefreshToken(sha256(refreshed.refresh_token)))

            service.revokeRefreshToken(
                refreshToken = refreshed.refresh_token,
                clientId = clientId,
                dpopProof = proof("/oauth/revoke"),
                htu = "https://logdate.app/oauth/revoke",
            )
            assertNotNull(store.findRefreshToken(sha256(refreshed.refresh_token))?.revokedAt)
        }

    private fun sha256(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun challengeFor(verifier: String): String = sha256(verifier)
}
