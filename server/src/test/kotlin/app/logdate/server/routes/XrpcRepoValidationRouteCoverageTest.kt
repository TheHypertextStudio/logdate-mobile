package app.logdate.server.routes

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.RepoListPage
import studio.hypertext.atproto.repo.RepoRecord
import studio.hypertext.atproto.repo.UnsupportedCollectionException
import studio.hypertext.atproto.syntax.AtUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class XrpcRepoValidationRouteCoverageTest : XrpcRouteTestSupport() {
    @Test
    fun `xrpc routes validate repo identifiers and missing resources`() =
        testApplication {
            val store = StubRepoRecordStore()
            val env = configureXrpcApp(repoRecordStore = store)
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
            val ensuredAccount = runBlocking { env.identityService.ensureIdentity(account) }
            val accessToken = env.tokenService.generateAccessToken(ensuredAccount.id.toString())

            val missingDescribeRepo = client.get("/xrpc/com.atproto.repo.describeRepo")
            val unknownDescribeRepo = client.get("/xrpc/com.atproto.repo.describeRepo?repo=missing.logdate.app")
            val getRecordMissing = client.get("/xrpc/com.atproto.repo.getRecord")
            val getRecordInvalid =
                client.get(
                    "/xrpc/com.atproto.repo.getRecord?repo=alice.logdate.app&collection=bad collection&rkey=entry-1",
                ) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            val getRecordMissingValue =
                client.get(
                    "/xrpc/com.atproto.repo.getRecord?repo=alice.logdate.app&collection=studio.hypertext.logdate.content&rkey=entry-1",
                ) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            store.getRecordResult = Result.failure(UnsupportedCollectionException("studio.hypertext.logdate.other"))
            val getRecordFailure =
                client.get(
                    "/xrpc/com.atproto.repo.getRecord?repo=alice.logdate.app&collection=studio.hypertext.logdate.content&rkey=entry-1",
                ) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            store.getRecordResult =
                Result.success(
                    RepoRecord(
                        uri = AtUri.require("at://${ensuredAccount.did}/studio.hypertext.logdate.content/entry-1"),
                        cid = "bafy-record",
                        value = buildJsonObject { put("content", "hello") },
                    ),
                )
            val getRecordCidMismatch =
                client.get(
                    "/xrpc/com.atproto.repo.getRecord?repo=alice.logdate.app&collection=studio.hypertext.logdate.content&rkey=entry-1&cid=bafy-other",
                ) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            val listRecordsMissing = client.get("/xrpc/com.atproto.repo.listRecords")
            val listRecordsInvalid =
                client.get("/xrpc/com.atproto.repo.listRecords?repo=alice.logdate.app&collection=bad collection") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            store.listRecordsResult = Result.success(RepoListPage(records = emptyList(), cursor = "next"))
            val listRecordsSuccess =
                client.get(
                    "/xrpc/com.atproto.repo.listRecords?repo=alice.logdate.app&collection=studio.hypertext.logdate.content&limit=999&cursor=%20cursor-1%20&reverse=true",
                ) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

            assertEquals(HttpStatusCode.BadRequest, missingDescribeRepo.status)
            assertEquals(HttpStatusCode.BadRequest, unknownDescribeRepo.status)
            assertEquals(HttpStatusCode.BadRequest, getRecordMissing.status)
            assertEquals(HttpStatusCode.BadRequest, getRecordInvalid.status)
            assertEquals(HttpStatusCode.BadRequest, getRecordMissingValue.status)
            assertEquals(HttpStatusCode.BadRequest, getRecordFailure.status)
            assertEquals(HttpStatusCode.BadRequest, getRecordCidMismatch.status)
            assertEquals(HttpStatusCode.BadRequest, listRecordsMissing.status)
            assertEquals(HttpStatusCode.BadRequest, listRecordsInvalid.status)
            assertEquals(HttpStatusCode.OK, listRecordsSuccess.status)
            assertEquals(
                "RepoNotFound",
                json
                    .parseToJsonElement(unknownDescribeRepo.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "RecordNotFound",
                json
                    .parseToJsonElement(getRecordMissingValue.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "InvalidRequest",
                json
                    .parseToJsonElement(getRecordFailure.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "RecordNotFound",
                json
                    .parseToJsonElement(getRecordCidMismatch.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(100, store.lastListLimit)
            assertEquals("cursor-1", store.lastListCursor)
            assertEquals(true, store.lastListReverse)
        }

    @Test
    fun `xrpc routes require auth wiring when repo store is present`() =
        testApplication {
            configureXrpcApp(
                repoRecordStore = StubRepoRecordStore(),
                accountRepository = null,
                tokenService = null,
            )

            val createResponse =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "repo": "alice.logdate.app",
                          "collection": "studio.hypertext.logdate.content",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            val putResponse =
                client.post("/xrpc/com.atproto.repo.putRecord") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"repo":"alice.logdate.app","collection":"studio.hypertext.logdate.content","rkey":"entry-1","record":{"type":"TEXT"}}""",
                    )
                }
            val deleteResponse =
                client.post("/xrpc/com.atproto.repo.deleteRecord") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"repo":"alice.logdate.app","collection":"studio.hypertext.logdate.content","rkey":"entry-1"}""")
                }

            assertEquals(HttpStatusCode.NotImplemented, createResponse.status)
            assertEquals(HttpStatusCode.NotImplemented, putResponse.status)
            assertEquals(HttpStatusCode.NotImplemented, deleteResponse.status)
            assertEquals(
                "Unsupported",
                json
                    .parseToJsonElement(createResponse.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "Unsupported",
                json
                    .parseToJsonElement(putResponse.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "Unsupported",
                json
                    .parseToJsonElement(deleteResponse.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `parseRecordId exercises success and unknown repo paths`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = YieldingAccountRepository()
            val identityService = identityService(accountRepository)
            val account =
                identityService.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "alice",
                            displayName = "Alice",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val resolvedRecordId = parseRecordId(identityService, account.handle!!, "studio.hypertext.logdate.content", "entry-1")
            val missingRecordId = parseRecordId(identityService, "missing.logdate.app", "studio.hypertext.logdate.content", "entry-1")

            assertEquals(account.did, resolvedRecordId?.repo?.toString())
            assertEquals("entry-1", resolvedRecordId?.recordKey.toString())
            assertEquals(null, missingRecordId)
        }
}
