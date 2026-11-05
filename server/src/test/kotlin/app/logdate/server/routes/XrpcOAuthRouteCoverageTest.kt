package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.oauth.createDpopProof
import app.logdate.server.oauth.generateP256KeyPair
import app.logdate.server.oauth.publicJwk
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.pds.runtime.DefaultPdsRepoService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class XrpcOAuthRouteCoverageTest : XrpcRouteTestSupport() {
    @Test
    fun `xrpc routes support dpop oauth auth and reject invalid proofs`() =
        testApplication {
            val store = StubRepoRecordStore()
            val env = configureXrpcApp(repoRecordStore = store)
            val account =
                runBlocking {
                    env.accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "dpop-user",
                            displayName = "DPoP User",
                            createdAt = Clock.System.now(),
                        ),
                    )
                }
            val ensuredAccount = runBlocking { env.identityService.ensureIdentity(account) }
            val repoDid = requireNotNull(ensuredAccount.did)
            val clientKey = generateP256KeyPair()
            val thumbprint = env.oauthDpopVerifier.jwkThumbprint(publicJwk(clientKey))
            val accessToken =
                env.oauthAccessTokenService
                    .issueAccessToken(
                        subjectDid = repoDid,
                        clientId = "https://viewer.example.com/client.json",
                        scope = "atproto transition:generic",
                        keyThumbprint = thumbprint,
                    ).token

            val created =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = clientKey,
                            method = "POST",
                            htu = "http://localhost/xrpc/com.atproto.repo.createRecord",
                            nonce = env.oauthNonceService.currentNonce(),
                            ath = env.oauthDpopVerifier.accessTokenHash(accessToken),
                        ),
                    )
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "com.example.note",
                          "rkey": "entry-1",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, created.status)

            val listed =
                client.get("/xrpc/com.atproto.repo.listRecords?repo=$repoDid&collection=com.example.note") {
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = clientKey,
                            method = "GET",
                            htu = "http://localhost/xrpc/com.atproto.repo.listRecords",
                            nonce = env.oauthNonceService.currentNonce(),
                            ath = env.oauthDpopVerifier.accessTokenHash(accessToken),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, listed.status)

            val missingProof =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    setBody("""{"repo":"$repoDid","collection":"com.example.note","record":{"type":"TEXT"}}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, missingProof.status)

            val wrongKey =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = generateP256KeyPair(),
                            method = "POST",
                            htu = "http://localhost/xrpc/com.atproto.repo.createRecord",
                            nonce = env.oauthNonceService.currentNonce(),
                            ath = env.oauthDpopVerifier.accessTokenHash(accessToken),
                        ),
                    )
                    setBody("""{"repo":"$repoDid","collection":"com.example.note","record":{"type":"TEXT"}}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)

            val wrongNonce =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = clientKey,
                            method = "POST",
                            htu = "http://localhost/xrpc/com.atproto.repo.createRecord",
                            nonce = "wrong-nonce",
                            ath = env.oauthDpopVerifier.accessTokenHash(accessToken),
                        ),
                    )
                    setBody("""{"repo":"$repoDid","collection":"com.example.note","record":{"type":"TEXT"}}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, wrongNonce.status)
            assertEquals(env.oauthNonceService.currentNonce(), wrongNonce.headers["DPoP-Nonce"])

            val missingAccountToken =
                env.oauthAccessTokenService
                    .issueAccessToken(
                        subjectDid = "did:plc:missing-user",
                        clientId = "https://viewer.example.com/client.json",
                        scope = "atproto transition:generic",
                        keyThumbprint = thumbprint,
                    ).token
            val missingAccount =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP $missingAccountToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = clientKey,
                            method = "POST",
                            htu = "http://localhost/xrpc/com.atproto.repo.createRecord",
                            nonce = env.oauthNonceService.currentNonce(),
                            ath = env.oauthDpopVerifier.accessTokenHash(missingAccountToken),
                        ),
                    )
                    setBody("""{"repo":"$repoDid","collection":"com.example.note","record":{"type":"TEXT"}}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, missingAccount.status)
        }

    @Test
    fun `xrpc routes reject invalid auth schemes and support custom port dpop proofs`() =
        testApplication {
            val store = StubRepoRecordStore()
            val env = configureXrpcApp(repoRecordStore = store)
            val account =
                runBlocking {
                    env.accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "port-user",
                            displayName = "Port User",
                            createdAt = Clock.System.now(),
                        ),
                    )
                }
            val ensuredAccount = runBlocking { env.identityService.ensureIdentity(account) }
            val repoDid = requireNotNull(ensuredAccount.did)
            val clientKey = generateP256KeyPair()
            val thumbprint = env.oauthDpopVerifier.jwkThumbprint(publicJwk(clientKey))
            val accessToken =
                env.oauthAccessTokenService
                    .issueAccessToken(
                        subjectDid = repoDid,
                        clientId = "https://viewer.example.com/client.json",
                        scope = "atproto transition:generic",
                        keyThumbprint = thumbprint,
                    ).token

            val invalidToken =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP bad-token")
                    setBody("""{"repo":"$repoDid","collection":"com.example.note","record":{"type":"TEXT"}}""")
                }
            val unsupportedScheme =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Basic abc")
                    setBody("""{"repo":"$repoDid","collection":"com.example.note","record":{"type":"TEXT"}}""")
                }
            val customPort =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Host, "localhost:444")
                    header(HttpHeaders.Authorization, "DPoP $accessToken")
                    header(
                        "DPoP",
                        createDpopProof(
                            keyPair = clientKey,
                            method = "POST",
                            htu = "http://localhost:444/xrpc/com.atproto.repo.createRecord",
                            nonce = env.oauthNonceService.currentNonce(),
                            ath = env.oauthDpopVerifier.accessTokenHash(accessToken),
                        ),
                    )
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "com.example.note",
                          "rkey": "entry-custom-port",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, invalidToken.status)
            assertEquals(HttpStatusCode.Unauthorized, unsupportedScheme.status)
            assertEquals(HttpStatusCode.OK, customPort.status)
        }

    @Test
    fun `xrpc routes return unsupported when dpop auth is requested without oauth services`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val identityService = identityService(accountRepository)
            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    xrpcRoutes(
                        identityService = identityService,
                        accountRepository = accountRepository,
                        tokenService = JwtTokenService("xrpc-route-coverage-secret"),
                        repoService = DefaultPdsRepoService(StubRepoRecordStore()),
                    )
                }
            }

            val response =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "DPoP token")
                    header("DPoP", createDpopProof(generateP256KeyPair(), "POST", "http://localhost/xrpc/com.atproto.repo.createRecord"))
                    setBody("""{"repo":"user.logdate.app","collection":"studio.hypertext.logdate.content","record":{"type":"TEXT"}}""")
                }

            assertEquals(HttpStatusCode.NotImplemented, response.status)
            assertTrue(response.bodyAsText().contains("Unsupported"))
        }
}
