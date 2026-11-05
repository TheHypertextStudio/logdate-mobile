package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.InMemoryAtprotoSessionRepository
import app.logdate.server.auth.Account
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.syntax.Tid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class XrpcSessionRouteCoverageTest : XrpcRouteTestSupport() {
    @Test
    fun `xrpc routes proxy token session endpoints and refuse password methods`() =
        testApplication {
            val sessionService = StubSessionService()
            configureXrpcApp(
                repoRecordStore = StubRepoRecordStore(),
                sessionService = sessionService,
            )

            val createdAccount =
                client.post("/xrpc/com.atproto.server.createAccount") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "email": "alice@example.com",
                          "handle": "alice.logdate.app",
                          "password": "pass1"
                        }
                        """.trimIndent(),
                    )
                }
            val createdSession =
                client.post("/xrpc/com.atproto.server.createSession") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "identifier": "alice@example.com",
                          "password": "pass1"
                        }
                        """.trimIndent(),
                    )
                }
            val fetchedSession =
                client.get("/xrpc/com.atproto.server.getSession") {
                    header(HttpHeaders.Authorization, "Bearer hosted-access")
                }
            val refreshedSession =
                client.post("/xrpc/com.atproto.server.refreshSession") {
                    header(HttpHeaders.Authorization, "Bearer hosted-refresh")
                }
            val deletedSession =
                client.post("/xrpc/com.atproto.server.deleteSession") {
                    header(HttpHeaders.Authorization, "Bearer hosted-refresh")
                }

            assertEquals(HttpStatusCode.BadRequest, createdAccount.status)
            assertEquals(HttpStatusCode.BadRequest, createdSession.status)
            assertEquals(HttpStatusCode.OK, fetchedSession.status)
            assertEquals(HttpStatusCode.OK, refreshedSession.status)
            assertEquals(HttpStatusCode.OK, deletedSession.status)
            assertEquals(null, sessionService.lastCreateAccountRequest)
            assertEquals(null, sessionService.lastCreateSessionRequest)
            assertEquals("hosted-access", sessionService.lastAccessToken)
            assertEquals("hosted-refresh", sessionService.lastRefreshToken)
            assertEquals("hosted-refresh", sessionService.deletedRefreshToken)
        }

    @Test
    fun `xrpc routes proxy standard sync export endpoints`() =
        testApplication {
            val syncService = StubSyncService()
            val env =
                configureXrpcApp(
                    repoRecordStore = StubRepoRecordStore(),
                    syncService = syncService,
                )
            val account =
                runBlocking {
                    env.accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "alice",
                            displayName = "Alice",
                            createdAt = Clock.System.now(),
                        ),
                    )
                }
            val did = requireNotNull(runBlocking { env.identityService.ensureIdentity(account) }.did)
            val accessToken = env.tokenService.generateAccessToken(account.id.toString())

            val repoResponse =
                client.get("/xrpc/com.atproto.sync.getRepo?did=$did&since=${Tid.fromLong(5L)}") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            val latestCommit =
                client.get("/xrpc/com.atproto.sync.getLatestCommit?did=$did") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            val repoStatus =
                client.get("/xrpc/com.atproto.sync.getRepoStatus?did=$did") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

            assertEquals(HttpStatusCode.OK, repoResponse.status)
            assertEquals("application/vnd.ipld.car", repoResponse.contentType().toString())
            assertEquals("car-bytes", repoResponse.bodyAsText())
            assertEquals(did, syncService.lastGetRepoRequest?.did.toString())
            assertEquals(5L, syncService.lastGetRepoRequest?.since?.toLong())
            assertEquals(did, syncService.lastGetLatestCommitRequest?.did.toString())
            assertEquals(did, syncService.lastGetRepoStatusRequest?.did.toString())
            assertEquals(
                "bafyreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku",
                json
                    .parseToJsonElement(latestCommit.bodyAsText())
                    .jsonObject["cid"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                Tid.fromLong(9L).toString(),
                json
                    .parseToJsonElement(repoStatus.bodyAsText())
                    .jsonObject["rev"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `xrpc repo routes accept hosted atproto session bearer tokens`() =
        testApplication {
            val store = StubRepoRecordStore()
            val atprotoSessionTokenService = AtprotoSessionTokenService(InMemoryAtprotoSessionRepository(), secret = "test")
            val env =
                configureXrpcApp(
                    repoRecordStore = store,
                    tokenService = null,
                    atprotoSessionTokenService = atprotoSessionTokenService,
                )
            val account =
                runBlocking {
                    env.accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "session-user",
                            displayName = "Session User",
                            createdAt = Clock.System.now(),
                        ),
                    )
                }
            val ensuredAccount = runBlocking { env.identityService.ensureIdentity(account) }
            val session = runBlocking { atprotoSessionTokenService.createSession(ensuredAccount) }

            val created =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer ${session.accessJwt}")
                    setBody(
                        """
                        {
                          "repo": "${ensuredAccount.did}",
                          "collection": "studio.hypertext.logdate.content",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals(
                "bafy-create",
                json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["cid"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }
}
