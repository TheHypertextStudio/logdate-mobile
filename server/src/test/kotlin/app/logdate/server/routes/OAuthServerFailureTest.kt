package app.logdate.server.routes

import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthAuthorizationService
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthPasskeySignInService
import app.logdate.server.oauth.OAuthReplayStore
import app.logdate.server.oauth.createDpopProof
import app.logdate.server.oauth.generateP256KeyPair
import app.logdate.server.oauth.publicJwk
import app.logdate.server.passkeys.WebAuthnPasskeyService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A database failure is the server's problem: it is answered as a server error with a stable
 * code, never as a bad request, an expired link or a failed sign-in, and never with SQL text.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class OAuthServerFailureTest {
    private val failure = SQLException("SELECT secret_column FROM oauth_authorization_requests")

    @Test
    fun `the authorization page says the server failed instead of the link expired`() =
        testApplication {
            installFailingAuthorization()

            val page =
                client.get(
                    "/oauth/authorize?request_uri=urn:ietf:params:oauth:request_uri:any",
                ) { header(HttpHeaders.Accept, "text/html") }

            assertEquals(HttpStatusCode.ServiceUnavailable, page.status)
            val body = page.bodyAsText()
            assertTrue("available right now" in body, body)
            assertFalse("expired" in body, body)
            assertFalse("secret_column" in body, body)
        }

    @Test
    fun `the passkey endpoints answer a stable server error without internal details`() =
        testApplication {
            installFailingAuthorization()

            val options =
                client.post("/oauth/authorize/passkey/options") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"request_uri":"urn:ietf:params:oauth:request_uri:any"}""")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, options.status)
            assertTrue("temporarily_unavailable" in options.bodyAsText(), options.bodyAsText())
            assertFalse("secret_column" in options.bodyAsText(), options.bodyAsText())
        }

    @Test
    fun `an unreachable replay store is a server error, not a bad DPoP proof`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val identity = identityService(accounts)
            val account =
                runBlocking {
                    identity.ensureIdentity(
                        accounts.save(
                            app.logdate.server.auth.Account(
                                id = kotlin.uuid.Uuid.random(),
                                username = "alice",
                                displayName = "Alice",
                                createdAt =
                                    kotlin.time.Clock.System
                                        .now(),
                            ),
                        ),
                    )
                }
            val keyService = OAuthKeyService()
            val tokens = OAuthAccessTokenService(OAuthConfig(issuer = "https://logdate.app"), keyService)
            val nonces = OAuthNonceService()
            val failingStore =
                object : OAuthReplayStore {
                    override suspend fun firstUse(
                        key: String,
                        ttl: Duration,
                    ): Boolean = throw failure
                }
            val verifier = OAuthDpopVerifier(usedProofs = failingStore)
            application {
                install(ContentNegotiation) { json() }
                routing {
                    xrpcRoutes(
                        identityService = identity,
                        accountRepository = accounts,
                        tokenService = JwtTokenService("failure-test-secret"),
                        oauthAccessTokenService = tokens,
                        oauthDpopVerifier = verifier,
                        oauthNonceService = nonces,
                        repoService = mockk(relaxed = true),
                    )
                }
            }
            val key = generateP256KeyPair()
            val accessToken =
                tokens
                    .issueAccessToken(
                        subjectDid = account.did!!,
                        clientId = "https://viewer.example.com/client.json",
                        scope = "atproto transition:generic",
                        keyThumbprint = verifier.jwkThumbprint(publicJwk(key)),
                    ).token

            val response =
                client.get("/xrpc/com.atproto.repo.listRecords?repo=${account.did}&collection=com.example.note") {
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = key,
                            method = "GET",
                            htu = "http://localhost/xrpc/com.atproto.repo.listRecords",
                            nonce = nonces.currentNonce(),
                            ath = verifier.accessTokenHash(accessToken),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertFalse("secret_column" in response.bodyAsText(), response.bodyAsText())
        }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installFailingAuthorization() {
        val authorization = mockk<OAuthAuthorizationService>()
        coEvery { authorization.describeAuthorizationRequest(any()) } throws failure
        val config = OAuthConfig(issuer = "https://logdate.app", passkeySignInEnabled = true)
        val accounts = InMemoryAccountRepository()
        val signIn =
            OAuthPasskeySignInService(
                config = config,
                authorizationService = authorization,
                webAuthnService =
                    WebAuthnPasskeyService(
                        relyingPartyId = "logdate.app",
                        origins = setOf("https://logdate.app"),
                        strictVerificationEnabled = true,
                    ),
                accountRepository = accounts,
                identityService = identityService(accounts),
            )
        application {
            install(ContentNegotiation) { json() }
            routing {
                oauthRoutes(
                    config = config,
                    keyService = OAuthKeyService(),
                    authorizationService = authorization,
                    passkeySignIn = signIn,
                )
            }
        }
    }

    private fun identityService(accounts: InMemoryAccountRepository) =
        AtprotoIdentityService(
            accountRepository = accounts,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "failure-test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )
}
