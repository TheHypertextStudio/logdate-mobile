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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.repo.RepoListPage
import studio.hypertext.atproto.repo.RepoValidationStatus
import studio.hypertext.atproto.repo.RepoWriteResult
import studio.hypertext.atproto.repo.UnsupportedCollectionException
import studio.hypertext.atproto.syntax.AtUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class XrpcRepoErrorsRouteCoverageTest : XrpcRouteTestSupport() {
    @Test
    fun `xrpc routes surface auth validation repo errors and hide private descriptions`() =
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
            val otherAccount =
                runBlocking {
                    env.accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "bob",
                            displayName = "Bob",
                            createdAt = Clock.System.now(),
                        ),
                    )
                }
            val ensuredOtherAccount = runBlocking { env.identityService.ensureIdentity(otherAccount) }
            val repoDid = requireNotNull(ensuredAccount.did)
            val otherRepoDid = requireNotNull(ensuredOtherAccount.did)
            val validToken = env.tokenService.generateAccessToken(ensuredAccount.id.toString())
            val missingAccountToken = env.tokenService.generateAccessToken(Uuid.random().toString())

            store.listRecordsResult =
                Result.success(
                    RepoListPage(
                        records = emptyList(),
                        cursor = "next-cursor",
                    ),
                )
            val describeRepo =
                client.get("/xrpc/com.atproto.repo.describeRepo?repo=alice.logdate.app")
            assertEquals(HttpStatusCode.BadRequest, describeRepo.status)
            assertEquals(
                "RepoNotFound",
                json
                    .parseToJsonElement(describeRepo.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )

            val invalidBearer =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer not-a-jwt")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            val missingAccount =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $missingAccountToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            val wrongOwnerCreate =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$otherRepoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }

            store.createRecordResult = Result.failure(UnsupportedCollectionException("studio.hypertext.logdate.other"))
            val unsupportedCreate =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }

            store.createRecordResult = Result.failure(IllegalStateException("boom"))
            val genericCreate =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "entry-1",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }

            store.putRecordResult =
                Result.success(
                    RepoWriteResult(
                        uri = AtUri.require("at://${ensuredAccount.did}/studio.hypertext.logdate.content/entry-1"),
                        cid = "bafy-put",
                        validationStatus = RepoValidationStatus.UNKNOWN,
                    ),
                )
            val successfulPut =
                client.post("/xrpc/com.atproto.repo.putRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "entry-1",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            store.putRecordResult = Result.failure(IllegalStateException("boom"))
            val failingPut =
                client.post("/xrpc/com.atproto.repo.putRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "${ensuredAccount.did}",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "entry-1",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }

            store.deleteRecordResult = Result.failure(IllegalStateException("boom"))
            val failingDelete =
                client.post("/xrpc/com.atproto.repo.deleteRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "entry-1"
                        }
                        """.trimIndent(),
                    )
                }
            val wrongOwnerDelete =
                client.post("/xrpc/com.atproto.repo.deleteRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$otherRepoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "entry-1"
                        }
                        """.trimIndent(),
                    )
                }

            val invalidCreateRequest =
                client.post("/xrpc/com.atproto.repo.createRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "bad key!",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            val invalidPutRequest =
                client.post("/xrpc/com.atproto.repo.putRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "bad collection",
                          "rkey": "entry-1",
                          "record": { "type": "TEXT" }
                        }
                        """.trimIndent(),
                    )
                }
            val invalidDeleteRequest =
                client.post("/xrpc/com.atproto.repo.deleteRecord") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $validToken")
                    setBody(
                        """
                        {
                          "repo": "$repoDid",
                          "collection": "studio.hypertext.logdate.content",
                          "rkey": "bad key!"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, invalidBearer.status)
            assertEquals(HttpStatusCode.Unauthorized, missingAccount.status)
            assertEquals(HttpStatusCode.Forbidden, wrongOwnerCreate.status)
            assertEquals(HttpStatusCode.BadRequest, unsupportedCreate.status)
            assertEquals(HttpStatusCode.BadRequest, genericCreate.status)
            assertEquals(HttpStatusCode.OK, successfulPut.status)
            assertEquals(HttpStatusCode.BadRequest, failingPut.status)
            assertEquals(HttpStatusCode.BadRequest, failingDelete.status)
            assertEquals(HttpStatusCode.Forbidden, wrongOwnerDelete.status)
            assertEquals(HttpStatusCode.BadRequest, invalidCreateRequest.status)
            assertEquals(HttpStatusCode.BadRequest, invalidPutRequest.status)
            assertEquals(HttpStatusCode.BadRequest, invalidDeleteRequest.status)
            assertEquals(
                "AuthRequired",
                json
                    .parseToJsonElement(invalidBearer.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "AuthRequired",
                json
                    .parseToJsonElement(missingAccount.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "InvalidRequest",
                json
                    .parseToJsonElement(unsupportedCreate.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "InvalidRequest",
                json
                    .parseToJsonElement(genericCreate.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "bafy-put",
                json
                    .parseToJsonElement(successfulPut.bodyAsText())
                    .jsonObject["cid"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "InvalidRequest",
                json
                    .parseToJsonElement(failingPut.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "InvalidRequest",
                json
                    .parseToJsonElement(failingDelete.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }
}
