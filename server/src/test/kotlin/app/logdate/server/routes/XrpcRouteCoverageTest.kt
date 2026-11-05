package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.pds.CreateRecordRequest
import studio.hypertext.atproto.pds.DeleteRecordRequest
import studio.hypertext.atproto.pds.DescribeRepoResponse
import studio.hypertext.atproto.pds.DescribeServerResponse
import studio.hypertext.atproto.pds.EmptyPdsResponse
import studio.hypertext.atproto.pds.ListRecordsResponse
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.pds.PutRecordRequest
import studio.hypertext.atproto.pds.ResolveHandleResponse
import studio.hypertext.atproto.repo.RepoListPage
import studio.hypertext.atproto.repo.RepoRecord
import studio.hypertext.atproto.syntax.AtUri
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class XrpcRouteCoverageTest : XrpcRouteTestSupport() {
    @Test
    fun `xrpc route models expose shared contract fields and defaults`() {
        val didDocument = DidDocument(id = AtprotoDid.require("did:web:alice.logdate.app"))
        val collection = Nsid.require("studio.hypertext.logdate.content")
        val repoRecord =
            RepoRecord(
                uri = AtUri.require("at://did:web:alice.logdate.app/studio.hypertext.logdate.content/entry-1"),
                cid = "bafy-record",
                value = buildJsonObject { put("content", "hello") },
            )
        val errorResponse = PdsErrorResponse("error", "message")
        val resolveHandle = ResolveHandleResponse(AtprotoDid.require("did:web:alice.logdate.app"))
        val describeServer =
            DescribeServerResponse(
                did = "did:web:logdate.app",
                availableUserDomains = listOf("logdate.app"),
                inviteCodeRequired = false,
                phoneVerificationRequired = false,
            )
        val describeRepo =
            DescribeRepoResponse(
                handle = "alice.logdate.app",
                did = AtprotoDid.require("did:web:alice.logdate.app"),
                didDoc = didDocument,
                collections = listOf(collection),
                handleIsCorrect = true,
            )
        val listRecords = ListRecordsResponse.fromPage(RepoListPage(records = listOf(repoRecord)))
        val createRequest =
            CreateRecordRequest(
                repo = AtprotoDid.require("did:web:alice.logdate.app"),
                collection = collection,
                record = buildJsonObject { put("content", "hello") },
            )
        val putRequest =
            PutRecordRequest(
                repo = AtprotoDid.require("did:web:alice.logdate.app"),
                collection = collection,
                recordKey = RecordKey.require("entry-1"),
                record = buildJsonObject { put("content", "hello") },
            )
        val deleteRequest =
            DeleteRecordRequest(
                repo = AtprotoDid.require("did:web:alice.logdate.app"),
                collection = collection,
                recordKey = RecordKey.require("entry-1"),
            )
        val emptyResponse = EmptyPdsResponse()

        assertEquals("error", errorResponse.error)
        assertEquals("message", errorResponse.message)
        assertEquals("did:web:alice.logdate.app", resolveHandle.did.toString())
        assertEquals("did:web:logdate.app", describeServer.did)
        assertEquals(listOf("logdate.app"), describeServer.availableUserDomains)
        assertFalse(describeServer.inviteCodeRequired)
        assertFalse(describeServer.phoneVerificationRequired)
        assertEquals("alice.logdate.app", describeRepo.handle)
        assertEquals("did:web:alice.logdate.app", describeRepo.did.toString())
        assertEquals(didDocument, describeRepo.didDoc)
        assertEquals(listOf(collection), describeRepo.collections)
        assertTrue(describeRepo.handleIsCorrect)
        assertEquals(listOf(repoRecord), listRecords.records)
        assertEquals(null, listRecords.cursor)
        assertEquals(null, createRequest.validate)
        assertEquals(null, createRequest.swapCommit)
        assertEquals(null, putRequest.validate)
        assertEquals(null, putRequest.swapCommit)
        assertEquals(null, deleteRequest.swapCommit)
        assertNotNull(emptyResponse)
    }

    @Test
    fun `xrpc routes default overload and missing repo store return unsupported`() =
        testApplication {
            val identityService = identityService(InMemoryAccountRepository())
            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    xrpcRoutes(identityService)
                }
            }

            val getRecord = client.get("/xrpc/com.atproto.repo.getRecord")
            val listRecords = client.get("/xrpc/com.atproto.repo.listRecords")
            val createRecord =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"repo":"alice.logdate.app","collection":"studio.hypertext.logdate.content","record":{"type":"TEXT"}}""")
                }
            val putRecord =
                client.post("/xrpc/com.atproto.repo.putRecord") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"repo":"alice.logdate.app","collection":"studio.hypertext.logdate.content","rkey":"entry-1","record":{"type":"TEXT"}}""",
                    )
                }
            val deleteRecord =
                client.post("/xrpc/com.atproto.repo.deleteRecord") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"repo":"alice.logdate.app","collection":"studio.hypertext.logdate.content","rkey":"entry-1"}""")
                }

            assertEquals(HttpStatusCode.NotImplemented, getRecord.status)
            assertEquals(HttpStatusCode.NotImplemented, listRecords.status)
            assertEquals(HttpStatusCode.NotImplemented, createRecord.status)
            assertEquals(HttpStatusCode.NotImplemented, putRecord.status)
            assertEquals(HttpStatusCode.NotImplemented, deleteRecord.status)
            assertEquals(
                "Unsupported",
                json
                    .parseToJsonElement(getRecord.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `journal lexicons stay closed to OAuth tokens even when the repository accepts them`() =
        testApplication {
            val store = StubRepoRecordStore()
            val journalRecord =
                RepoRecord(
                    uri = AtUri.require("at://did:web:closed-user.logdate.app/app.logdate.journal.entry/entry-1"),
                    cid = "bafy-record",
                    value = buildJsonObject { put("text", "a private entry") },
                )
            store.getRecordResult = Result.success(journalRecord)
            store.listRecordsResult = Result.success(RepoListPage(records = listOf(journalRecord)))
            val env = configureXrpcApp(repoRecordStore = store)
            val account =
                runBlocking {
                    env.identityService.ensureIdentity(
                        env.accountRepository.save(
                            Account(id = Uuid.random(), username = "closed-user", displayName = "Closed", createdAt = Clock.System.now()),
                        ),
                    )
                }
            val did = requireNotNull(account.did)
            val clientKey = generateP256KeyPair()
            val token = { scope: String ->
                env.oauthAccessTokenService
                    .issueAccessToken(
                        did,
                        "https://viewer.example.com/client.json",
                        scope,
                        env.oauthDpopVerifier.jwkThumbprint(publicJwk(clientKey)),
                    ).token
            }
            val generic = token("atproto transition:generic")
            val proof = { method: String, path: String, accessToken: String ->
                createDpopProof(
                    keyPair = clientKey,
                    method = method,
                    htu = "http://localhost$path",
                    nonce = env.oauthNonceService.currentNonce(),
                    ath = env.oauthDpopVerifier.accessTokenHash(accessToken),
                )
            }

            listOf(
                "app.logdate.journal.entry",
                "App.LogDate.journal.entry",
                "studio.hypertext.logdate.content",
                "Studio.Hypertext.Logdate.content",
            ).forEach { collection ->
                val create =
                    client.post("/xrpc/com.atproto.repo.createRecord") {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "DPoP $generic")
                        header("DPoP", proof("POST", "/xrpc/com.atproto.repo.createRecord", generic))
                        setBody("""{"repo":"$did","collection":"$collection","record":{"text":"hi"}}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, create.status, "$collection write: ${create.bodyAsText()}")

                val read =
                    client.get("/xrpc/com.atproto.repo.getRecord?repo=$did&collection=$collection&rkey=entry-1") {
                        header(HttpHeaders.Authorization, "DPoP $generic")
                        header("DPoP", proof("GET", "/xrpc/com.atproto.repo.getRecord", generic))
                    }
                assertTrue("RecordNotFound" in read.bodyAsText(), "$collection read: ${read.bodyAsText()}")

                val list =
                    client.get("/xrpc/com.atproto.repo.listRecords?repo=$did&collection=$collection") {
                        header(HttpHeaders.Authorization, "DPoP $generic")
                        header("DPoP", proof("GET", "/xrpc/com.atproto.repo.listRecords", generic))
                    }
                assertEquals(HttpStatusCode.OK, list.status, "$collection list: ${list.bodyAsText()}")
                assertFalse("entry-1" in list.bodyAsText(), "$collection list: ${list.bodyAsText()}")
            }

            val identityOnly = token("atproto")
            val identityRead =
                client.get("/xrpc/com.atproto.repo.listRecords?repo=$did&collection=com.example.note") {
                    header(HttpHeaders.Authorization, "DPoP $identityOnly")
                    header("DPoP", proof("GET", "/xrpc/com.atproto.repo.listRecords", identityOnly))
                }
            assertEquals(HttpStatusCode.Forbidden, identityRead.status, "atproto alone does not include the repository")
            assertTrue("InsufficientScope" in identityRead.bodyAsText())
        }

    @Test
    fun `journal collections cannot be opened to apps`() {
        listOf("app.logdate.journal.entry", "Studio.Hypertext.Logdate.content").forEach { collection ->
            assertFailsWith<IllegalArgumentException>(collection) {
                app.logdate.server.oauth.OAuthScopes
                    .requireOpenable(setOf(collection))
            }
        }
        assertEquals(
            setOf("com.example.note"),
            app.logdate.server.oauth.OAuthScopes
                .requireOpenable(setOf("Com.Example.note")),
        )
    }
}
