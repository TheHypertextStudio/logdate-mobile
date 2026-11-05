@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.lexicon.app.logdate.space.DeleteRecordInput
import app.logdate.lexicon.app.logdate.space.PutRecordInput
import app.logdate.server.AuthV1TestEnvironment
import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.database.toJavaUUID
import app.logdate.server.entitlements.Entitlement
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.EntitlementLimits
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.entitlements.EntitlementStatus
import app.logdate.server.entitlements.EntitlementTier
import app.logdate.server.entitlements.UsageCalculator
import app.logdate.server.oauth.createDpopProof
import app.logdate.server.oauth.generateP256KeyPair
import app.logdate.server.oauth.publicJwk
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.routes.support.DeviceLinkTestKit
import app.logdate.server.space.InMemorySpaceStore
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceBlobPurgingException
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceStore
import app.logdate.server.space.SpaceUploadReservation
import app.logdate.server.transfer.TransferLimits
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import studio.hypertext.atproto.repo.Cid
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class SpaceXrpcRoutesTest {
    @Test
    fun `an upload receipt recovers committed bytes without downloading or replacing them`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("receipt-owner")
            val (_, stranger) = env.signedInAccount("receipt-stranger")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val intent = "bf155688-bcb4-49c4-a61e-3769eb0d31e8"
            val path = "/xrpc/app.logdate.space.getBlobUploadReceipt?space=$space&intent=$intent"
            val absent = client.get(path) { bearer(token) }
            assertEquals(HttpStatusCode.OK, absent.status)
            assertTrue("true" in absent.bodyAsText())
            val bytes = ByteArray(512) { 7 }
            val first =
                client.post("/xrpc/app.logdate.space.uploadBlob?space=$space") {
                    bearer(token)
                    header("X-LogDate-Upload-Intent", intent)
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }
            assertEquals(HttpStatusCode.OK, first.status)
            val cid =
                Json
                    .parseToJsonElement(
                        first.bodyAsText(),
                    ).jsonObject["blob"]!!
                    .jsonObject["ref"]!!
                    .jsonObject["${'$'}link"]!!
                    .jsonPrimitive.content
            val receipt = client.get(path) { bearer(token) }
            assertEquals(HttpStatusCode.OK, receipt.status)
            assertTrue(cid in receipt.bodyAsText())
            assertEquals("private, no-store", receipt.headers[HttpHeaders.CacheControl])
            val replay =
                client.post("/xrpc/app.logdate.space.uploadBlob?space=$space") {
                    bearer(token)
                    header("X-LogDate-Upload-Intent", intent)
                    contentType(ContentType.Application.OctetStream)
                    setBody(ByteArray(512) { 8 })
                }
            assertEquals(first.bodyAsText(), replay.bodyAsText())
            assertContentEquals(bytes, client.get("/xrpc/app.logdate.space.getBlob?space=$space&cid=$cid") { bearer(token) }.bodyAsBytes())
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            assertEquals(HttpStatusCode.BadRequest, client.get(path) { bearer(stranger) }.status)
        }

    @Test
    fun `the owner can write, read and list their personal space`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val put =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
                }
            assertEquals(HttpStatusCode.OK, put.status)
            assertEquals(HttpStatusCode.OK, client.get("/xrpc/app.logdate.space.getRecord?space=$space&rkey=abc") { bearer(token) }.status)
            val list = client.get("/xrpc/app.logdate.space.listRepoOps?space=$space") { bearer(token) }.bodyAsText()
            assertTrue(""""rkey":"abc"""" in list.replace(" ", ""))
        }

    @Test
    fun `another account can't read or write it, and it looks missing`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val (_, other) = env.signedInAccount("bob")
            val space = "at://$did/space/app.logdate.space.personal/self"
            client.post("/xrpc/app.logdate.space.putRecord") {
                bearer(owner)
                json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
            }
            val read = client.get("/xrpc/app.logdate.space.getRecord?space=$space&rkey=abc") { bearer(other) }
            assertEquals(HttpStatusCode.BadRequest, read.status)
            assertTrue("RecordNotFound" in read.bodyAsText())
            val write =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(other)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, write.status)
            assertTrue("RecordNotFound" in write.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, client.get("/xrpc/app.logdate.space.getRecord?space=$space&rkey=abc").status)
        }

    @Test
    fun `records that aren't padded sealed envelopes are refused`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val response =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":{"${'$'}type":"app.logdate.journal.entry","text":"plain"}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue("InvalidRecord" in response.bodyAsText())
        }

    @Test
    fun `a changed record is a swap conflict`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val first =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
                }
            assertEquals(HttpStatusCode.OK, first.status)
            val stale =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson(fill = 2)},"swapCid":"bafybogus"}""")
                }
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("InvalidSwap", stale.errorCode())
            val existing =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson(fill = 2)},"createOnly":true}""")
                }
            assertEquals(HttpStatusCode.Conflict, existing.status)
            assertEquals("InvalidSwap", existing.errorCode())
            val delete =
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","swapCid":"bafybogus"}""")
                }
            assertEquals(HttpStatusCode.Conflict, delete.status)
            assertEquals("InvalidSwap", delete.errorCode())
            assertEquals(HttpStatusCode.OK, getRecord(token, space, "abc").status)
        }

    @Test
    fun `blobs upload and read back only for the owner`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val (_, other) = env.signedInAccount("bob")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val bytes = ByteArray(600) { it.toByte() }
            val upload = uploadBlob(owner, space, bytes)
            assertEquals(HttpStatusCode.OK, upload.status)
            val blob =
                Json
                    .parseToJsonElement(upload.bodyAsText())
                    .jsonObject
                    .getValue("blob")
                    .jsonObject
            val cid =
                blob
                    .getValue("ref")
                    .jsonObject
                    .getValue("\$link")
                    .jsonPrimitive.content
            assertEquals(Cid.rawSha256(bytes).value, cid)
            assertEquals("blob", blob.getValue("\$type").jsonPrimitive.content)
            assertEquals("application/octet-stream", blob.getValue("mimeType").jsonPrimitive.content)
            assertEquals(600L, blob.getValue("size").jsonPrimitive.long)

            val read = getBlob(owner, space, cid)
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(ContentType.Application.OctetStream, read.contentType()?.withoutParameters())
            assertEquals("private, no-store", read.headers[HttpHeaders.CacheControl])
            assertContentEquals(bytes, read.bodyAsBytes())

            val stranger = getBlob(other, space, cid)
            assertEquals(HttpStatusCode.BadRequest, stranger.status)
            assertEquals("BlobNotFound", stranger.errorCode())
            val missing = getBlob(owner, space, Cid.rawSha256(byteArrayOf(9)).value)
            assertEquals(stranger.status, missing.status)
            assertContentEquals(missing.bodyAsBytes(), stranger.bodyAsBytes())
        }

    @Test
    fun `uploading the same blob twice keeps one stored copy`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val bytes = ByteArray(600) { 7 }
            assertEquals(HttpStatusCode.OK, uploadBlob(owner, space, bytes).status)
            assertEquals(HttpStatusCode.OK, uploadBlob(owner, space, bytes).status)
            val ownerId = runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString()
            val stored = env.blobStorage.listOwnerBlobPaths(java.util.UUID.fromString(ownerId))
            assertEquals(1, stored.size, "stored copies: $stored")
            assertTrue(stored.single().startsWith("users/$ownerId/spaces/"))
            assertContentEquals(bytes, getBlob(owner, space, Cid.rawSha256(bytes).value).bodyAsBytes())
        }

    @Test
    fun `blobs over 100 MiB are refused`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val response = uploadBlob(owner, space, ByteArray(100 * 1024 * 1024 + 1))
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("BlobTooLarge", response.errorCode())
        }

    @Test
    fun `a record at the largest sealed size is accepted`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val largest = 61 * 65_536 + 16
            val put =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"big","record":${sealedJson(ciphertextBytes = largest)}}""")
                }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
            assertEquals(HttpStatusCode.OK, getRecord(token, space, "big").status)
        }

    @Test
    fun `a page of changes stops before its records pass the byte budget`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            listOf("a", "b", "c").forEach { rkey ->
                val put =
                    client.post("/xrpc/app.logdate.space.putRecord") {
                        bearer(token)
                        json("""{"space":"$space","rkey":"$rkey","record":${sealedJson(ciphertextBytes = 61 * 65_536 + 16)}}""")
                    }
                assertEquals(HttpStatusCode.OK, put.status)
            }
            val page =
                client.get("/xrpc/app.logdate.space.listRepoOps") {
                    bearer(token)
                    parameter("space", space)
                }
            val body = Json.parseToJsonElement(page.bodyAsText()).jsonObject
            assertEquals(2, body.getValue("ops").jsonArray.size)
            assertEquals(2L, body.getValue("cursor").jsonPrimitive.long)
            assertEquals("true", body.getValue("hasMore").jsonPrimitive.content)
        }

    @Test
    fun `a cursor from before purged tombstones answers CursorTooOld`() =
        testApplication {
            val store = InMemorySpaceStore()
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceStore = store)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            listOf("a", "b").forEach { rkey ->
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"$rkey","record":${sealedJson()}}""")
                }
            }
            client.post("/xrpc/app.logdate.space.deleteRecord") {
                bearer(token)
                json("""{"space":"$space","rkey":"a"}""")
            }
            val later = Clock.System.now() + 1.hours
            runBlocking { store.purge(tombstonesBefore = later, unreferencedBlobsBefore = later) {} }

            fun list(since: Long) =
                runBlocking {
                    client.get("/xrpc/app.logdate.space.listRepoOps") {
                        bearer(token)
                        parameter("space", space)
                        parameter("since", since.toString())
                    }
                }
            val stale = list(since = 1)
            assertEquals(HttpStatusCode.BadRequest, stale.status)
            assertEquals("CursorTooOld", stale.errorCode())
            assertEquals(HttpStatusCode.OK, list(since = 0).status)
            assertEquals(HttpStatusCode.OK, list(since = 3).status)
        }

    @Test
    fun `a delete request larger than 16 KiB is refused as too large`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val padded = " ".repeat(16 * 1024)
            val tooLarge =
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc"}$padded""")
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertEquals("RecordTooLarge", tooLarge.errorCode())
            val fits =
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc"}""")
                }
            assertEquals(HttpStatusCode.OK, fits.status)
        }

    @Test
    fun `every method looks missing to another account`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val (_, other) = env.signedInAccount("bob")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val bytes = ByteArray(600) { 3 }
            assertEquals(HttpStatusCode.OK, uploadBlob(owner, space, bytes).status)
            client.post("/xrpc/app.logdate.space.putRecord") {
                bearer(owner)
                json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
            }

            val responses =
                mapOf(
                    "putRecord" to
                        client.post("/xrpc/app.logdate.space.putRecord") {
                            bearer(other)
                            json("""{"space":"$space","rkey":"abc","record":${sealedJson(fill = 5)}}""")
                        },
                    "deleteRecord" to
                        client.post("/xrpc/app.logdate.space.deleteRecord") {
                            bearer(other)
                            json("""{"space":"$space","rkey":"abc"}""")
                        },
                    "listRepoOps" to
                        client.get("/xrpc/app.logdate.space.listRepoOps") {
                            bearer(other)
                            parameter("space", space)
                        },
                    "getRecord" to getRecord(other, space, "abc"),
                    "uploadBlob" to uploadBlob(other, space, bytes),
                    "getBlob" to getBlob(other, space, Cid.rawSha256(bytes).value),
                )
            responses.forEach { (method, response) ->
                val expected = if ("Blob" in method) "BlobNotFound" else "RecordNotFound"
                assertEquals(HttpStatusCode.BadRequest, response.status, method)
                assertEquals(expected, response.errorCode(), method)
            }

            val unchanged = getRecord(owner, space, "abc")
            assertEquals(HttpStatusCode.OK, unchanged.status)
            assertEquals(
                1L,
                Json
                    .parseToJsonElement(unchanged.bodyAsText())
                    .jsonObject
                    .getValue("seq")
                    .jsonPrimitive.long,
            )
        }

    @Test
    fun `a missing record and someone else's record answer byte for byte the same`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val (otherDid, other) = env.signedInAccount("bob")
            val space = "at://$did/space/app.logdate.space.personal/self"
            client.post("/xrpc/app.logdate.space.putRecord") {
                bearer(owner)
                json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
            }
            val missing = getRecord(owner, space, "nothing-here")
            val someoneElses = getRecord(other, space, "abc")
            val unknownAuthor = getRecord(other, "at://did:plc:nobodyhere/space/app.logdate.space.personal/self", "abc")
            val otherType = getRecord(owner, "at://$did/space/app.logdate.space.group/self", "abc")
            val otherKey = getRecord(owner, "at://$did/space/app.logdate.space.personal/other", "abc")
            listOf(someoneElses, unknownAuthor, otherType, otherKey).forEach { response ->
                assertEquals(missing.status, response.status)
                assertContentEquals(missing.bodyAsBytes(), response.bodyAsBytes())
            }
            assertEquals(HttpStatusCode.BadRequest, getRecord(other, "at://$otherDid/space/app.logdate.space.personal/self", "abc").status)
        }

    @Test
    fun `deleted records are listed as tombstones and stop reading back`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            repeat(3) { index ->
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"r$index","record":${sealedJson()}}""")
                }
            }
            val delete =
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"r0"}""")
                }
            assertEquals(HttpStatusCode.OK, delete.status)
            assertEquals(
                4L,
                Json
                    .parseToJsonElement(delete.bodyAsText())
                    .jsonObject
                    .getValue("seq")
                    .jsonPrimitive.long,
            )
            assertEquals("RecordNotFound", getRecord(token, space, "r0").errorCode())

            val page =
                client.get("/xrpc/app.logdate.space.listRepoOps") {
                    bearer(token)
                    parameter("space", space)
                    parameter("since", "1")
                    parameter("limit", "2")
                }
            val body = Json.parseToJsonElement(page.bodyAsText()).jsonObject
            assertEquals(3L, body.getValue("cursor").jsonPrimitive.long)
            assertEquals("true", body.getValue("hasMore").jsonPrimitive.content)
            val rest =
                client.get("/xrpc/app.logdate.space.listRepoOps") {
                    bearer(token)
                    parameter("space", space)
                    parameter("since", "3")
                    parameter("limit", "500")
                }
            val tombstone = Json.parseToJsonElement(rest.bodyAsText()).jsonObject
            assertTrue(""""deleted":true""" in rest.bodyAsText().replace(" ", ""))
            assertEquals("false", tombstone.getValue("hasMore").jsonPrimitive.content)
            val badLimit =
                client.get("/xrpc/app.logdate.space.listRepoOps") {
                    bearer(token)
                    parameter("space", space)
                    parameter("limit", "lots")
                }
            assertEquals(HttpStatusCode.BadRequest, badLimit.status)
            assertEquals("InvalidRequest", badLimit.errorCode())
            listOf("0", "501", "9999", "-1").forEach { limit ->
                val outOfRange =
                    client.get("/xrpc/app.logdate.space.listRepoOps") {
                        bearer(token)
                        parameter("space", space)
                        parameter("limit", limit)
                    }
                assertEquals(HttpStatusCode.BadRequest, outOfRange.status, "limit=$limit")
                assertEquals("InvalidRequest", outOfRange.errorCode(), "limit=$limit")
            }
        }

    @Test
    fun `malformed requests are invalid`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val notASpace = getRecord(token, "https://example.com", "abc")
            assertEquals(HttpStatusCode.BadRequest, notASpace.status)
            assertEquals("InvalidRequest", notASpace.errorCode())
            val badKey =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"no spaces/allowed","record":${sealedJson()}}""")
                }
            assertEquals("InvalidRequest", badKey.errorCode())
            val notJson =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":""")
                }
            assertEquals(HttpStatusCode.BadRequest, notJson.status)
            assertEquals("InvalidRequest", notJson.errorCode())
            val unknownBlob =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson()},"blobs":["bafkreinotuploaded"]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, unknownBlob.status)
            assertEquals("InvalidRecord", unknownBlob.errorCode())
        }

    @Test
    fun `with the flag off every method is not implemented and the feature isn't advertised`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = false)
            val (did, token) = env.signedInAccount("alice")
            val response =
                client.get("/xrpc/app.logdate.space.listRepoOps?space=at://$did/space/app.logdate.space.personal/self") { bearer(token) }
            assertEquals(HttpStatusCode.NotImplemented, response.status)
            assertTrue("MethodNotImplemented" in response.bodyAsText())
            val space = "at://$did/space/app.logdate.space.personal/self"
            listOf(
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson()}}""")
                },
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"abc"}""")
                },
                getRecord(token, space, "abc"),
                uploadBlob(token, space, ByteArray(600)),
                getBlob(token, space, Cid.rawSha256(ByteArray(600)).value),
            ).forEach { assertEquals(HttpStatusCode.NotImplemented, it.status) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/xrpc/app.logdate.space.listRepoOps") { parameter("space", space) }.status,
            )
        }

    @Test
    fun `OAuth and AT Protocol session tokens are refused on every method`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val account = runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(600)).status)

            val clientKey = generateP256KeyPair()
            val oauthToken =
                env.oauthAccessTokenService
                    .issueAccessToken(
                        subjectDid = did,
                        clientId = "https://viewer.example.com/client.json",
                        scope = "atproto transition:generic",
                        keyThumbprint = env.oauthDpopVerifier.jwkThumbprint(publicJwk(clientKey)),
                    ).token
            var proofs = 0
            val dpop: HttpRequestBuilder.(String, String) -> Unit = { method, path ->
                header(HttpHeaders.Authorization, "DPoP $oauthToken")
                header(
                    "DPoP",
                    createDpopProof(
                        keyPair = clientKey,
                        method = method,
                        htu = "http://localhost$path",
                        nonce = env.oauthNonceService.currentNonce(),
                        ath = env.oauthDpopVerifier.accessTokenHash(oauthToken),
                        jti = "space-proof-${proofs++}",
                    ),
                )
            }
            val sessionToken = runBlocking { env.atprotoSessionTokenService.createSession(account).accessJwt }
            val session: HttpRequestBuilder.(String, String) -> Unit = { _, _ -> bearer(sessionToken) }

            val listRecords = "/xrpc/com.atproto.repo.listRecords"
            val collection = "studio.hypertext.logdate.content"
            assertEquals(
                HttpStatusCode.OK,
                client.get("$listRecords?repo=$did&collection=$collection") { dpop("GET", listRecords) }.status,
                "the OAuth token works on the AT Protocol repository",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("$listRecords?repo=$did&collection=$collection") { session("GET", listRecords) }.status,
                "the AT Protocol session token works on the AT Protocol repository",
            )

            val unauthenticated = everySpaceMethod(space) { _, _ -> }
            val oauthAsBearer: HttpRequestBuilder.(String, String) -> Unit = { _, _ -> bearer(oauthToken) }
            mapOf(
                "OAuth DPoP" to dpop,
                "OAuth sent as Bearer" to oauthAsBearer,
                "AT Protocol session" to session,
            ).forEach { (kind, authorize) ->
                everySpaceMethod(space, authorize).forEach { (method, response) ->
                    assertEquals(HttpStatusCode.Unauthorized, response.status, "$kind token on $method")
                    assertEquals("AuthRequired", response.errorCode(), "$kind token on $method")
                    assertContentEquals(unauthenticated.getValue(method).bodyAsBytes(), response.bodyAsBytes(), "$kind token on $method")
                }
            }
            everySpaceMethod(space) { _, _ -> bearer(token) }.forEach { (method, response) ->
                assertTrue(response.status.value in 200..299, "LogDate token on $method answered ${response.status}")
            }
        }

    @Test
    fun `a linked watch uses its account's personal space on every method`() =
        testApplication {
            val (env, kit) = configureWithDeviceSessions()
            val (did, owner) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val watch = runBlocking { kit.linkWatch(env.accountId(did)) }.tokens.accessToken
            assertEquals(HttpStatusCode.OK, uploadBlob(owner, space, ByteArray(600)).status)

            everySpaceMethod(space) { _, _ -> bearer(watch) }.forEach { (method, response) ->
                assertTrue(response.status.value in 200..299, "linked watch on $method answered ${response.status}")
            }

            client.post("/xrpc/app.logdate.space.putRecord") {
                bearer(owner)
                json("""{"space":"$space","rkey":"from-phone","record":${sealedJson()}}""")
            }
            assertEquals(HttpStatusCode.OK, getRecord(watch, space, "from-phone").status, "the watch reads what the phone wrote")
        }

    @Test
    fun `a linked watch can't reach another account's space`() =
        testApplication {
            val (env, kit) = configureWithDeviceSessions()
            val (aliceDid, _) = env.signedInAccount("alice")
            val (bobDid, bob) = env.signedInAccount("bob")
            val bobSpace = "at://$bobDid/space/app.logdate.space.personal/self"
            client.post("/xrpc/app.logdate.space.putRecord") {
                bearer(bob)
                json("""{"space":"$bobSpace","rkey":"abc","record":${sealedJson()}}""")
            }
            val aliceWatch = runBlocking { kit.linkWatch(env.accountId(aliceDid)) }.tokens.accessToken

            val read = getRecord(aliceWatch, bobSpace, "abc")

            assertEquals(HttpStatusCode.BadRequest, read.status)
            assertEquals("RecordNotFound", read.errorCode())
        }

    @Test
    fun `a removed watch is told its session was revoked on every method`() =
        testApplication {
            val (env, kit) = configureWithDeviceSessions()
            val (did, _) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val accountId = env.accountId(did)
            val linked = runBlocking { kit.linkWatch(accountId) }
            runBlocking { kit.linkService.revoke(accountId.toJavaUUID(), java.util.UUID.fromString(linked.sessionId)) }
            val unknownSession =
                env.tokenService.generateDeviceAccessToken(accountId.toString(), did, Uuid.random().toString())

            listOf(linked.tokens.accessToken, unknownSession).forEach { token ->
                everySpaceMethod(space) { _, _ -> bearer(token) }.forEach { (method, response) ->
                    assertEquals(HttpStatusCode.Unauthorized, response.status, method)
                    assertEquals(DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED, response.errorCode(), method)
                }
            }
        }

    @Test
    fun `a linked watch of a deactivated account is refused`() =
        testApplication {
            val (env, kit) = configureWithDeviceSessions()
            val (did, _) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val watch = runBlocking { kit.linkWatch(env.accountId(did)) }.tokens.accessToken
            runBlocking {
                val account = requireNotNull(env.accountRepository.findByDid(did))
                env.accountRepository.deactivateAccount(account.id)
            }

            val unauthenticated = everySpaceMethod(space) { _, _ -> }
            everySpaceMethod(space) { _, _ -> bearer(watch) }.forEach { (method, response) ->
                assertEquals(HttpStatusCode.Unauthorized, response.status, method)
                assertContentEquals(unauthenticated.getValue(method).bodyAsBytes(), response.bodyAsBytes(), method)
            }
        }

    /** The test app with device sessions that a linked watch's token is checked against. */
    private fun ApplicationTestBuilder.configureWithDeviceSessions(): Pair<AuthV1TestEnvironment, DeviceLinkTestKit> {
        val tokenService = JwtTokenService("space-device-session-secret")
        val accounts = InMemoryAccountRepository()
        val kit = DeviceLinkTestKit(tokenService, accounts)
        val env =
            configureAuthV1TestApp(
                tokenService = tokenService,
                accountRepository = accounts,
                accessTokenVerifier = kit.verifier,
                personalSpaceEnabled = true,
            )
        return env to kit
    }

    @Test
    fun `the request bodies use the lexicon's field names`() {
        assertEquals(
            PutRecordInput
                .serializer()
                .descriptor.elementNames
                .toSet(),
            SpacePutRecordInput
                .serializer()
                .descriptor.elementNames
                .toSet(),
        )
        assertEquals(
            DeleteRecordInput
                .serializer()
                .descriptor.elementNames
                .toSet(),
            SpaceDeleteRecordInput
                .serializer()
                .descriptor.elementNames
                .toSet(),
        )
    }

    @Test
    fun `an upload waiting on object cleanup answers ServerBusy and removes its candidate`() =
        testApplication {
            val store =
                object : SpaceStore by InMemorySpaceStore() {
                    override suspend fun putBlob(
                        accountId: java.util.UUID,
                        space: String,
                        blob: SpaceBlob,
                        quota: SpaceQuota?,
                        reservation: SpaceUploadReservation?,
                        identityEpoch: Long,
                    ): SpaceBlob = throw SpaceBlobPurgingException()
                }
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceStore = store,
                    spaceTransferLimits = TransferLimits(retryAfterSeconds = 7),
                )
            val (did, token) = env.signedInAccount("alice")
            val response = uploadBlob(token, "at://$did/space/app.logdate.space.personal/self", ByteArray(600))
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("ServerBusy", response.errorCode())
            assertEquals("7", response.headers[HttpHeaders.RetryAfter])
            val owner = runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString()
            assertEquals(emptyList(), env.blobStorage.listOwnerBlobPaths(java.util.UUID.fromString(owner)))
        }

    @Test
    fun `an upload whose metadata can't be stored leaves no object behind`() =
        testApplication {
            val store =
                object : SpaceStore by InMemorySpaceStore() {
                    override suspend fun putBlob(
                        accountId: java.util.UUID,
                        space: String,
                        blob: SpaceBlob,
                        quota: SpaceQuota?,
                        reservation: SpaceUploadReservation?,
                        identityEpoch: Long,
                    ): SpaceBlob = error("The metadata store is down")
                }
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceStore = store)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val outcome = runCatching { uploadBlob(token, space, ByteArray(600)) }
            assertTrue(outcome.isFailure || outcome.getOrThrow().status == HttpStatusCode.InternalServerError, "$outcome")
            val ownerId = runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString()
            assertEquals(emptyList(), env.blobStorage.listOwnerBlobPaths(java.util.UUID.fromString(ownerId)))
        }

    @Test
    fun `an upload cancelled after its row was saved keeps the object`() =
        assertSavedUploadSurvives(CancellationException("The caller went away"))

    @Test
    fun `an upload that fails after its row was saved keeps the object`() =
        assertSavedUploadSurvives(IllegalStateException("The response failed"))

    private fun assertSavedUploadSurvives(failure: Throwable) =
        testApplication {
            val saving = InMemorySpaceStore()
            var failNext = true
            val store =
                object : SpaceStore by saving {
                    override suspend fun putBlob(
                        accountId: java.util.UUID,
                        space: String,
                        blob: SpaceBlob,
                        quota: SpaceQuota?,
                        reservation: SpaceUploadReservation?,
                        identityEpoch: Long,
                    ): SpaceBlob {
                        val stored = saving.putBlob(accountId, space, blob, quota, reservation, identityEpoch)
                        if (failNext) {
                            failNext = false
                            throw failure
                        }
                        return stored
                    }
                }
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceStore = store)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val bytes = ByteArray(600) { 4 }
            val cid = Cid.rawSha256(bytes).value
            runCatching { uploadBlob(token, space, bytes) }
            val saved = assertNotNull(saving.getBlob(space, cid))
            assertContentEquals(bytes, env.blobStorage.getBlob(saved.storagePath), "the saved row's object survives")
            val read = getBlob(token, space, cid)
            assertEquals(HttpStatusCode.OK, read.status)
            assertContentEquals(bytes, read.bodyAsBytes())
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, bytes).status)
            assertContentEquals(bytes, getBlob(token, space, cid).bodyAsBytes())
        }

    @Test
    fun `a deactivated account is refused`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val before =
                client.get("/xrpc/app.logdate.space.listRepoOps") {
                    bearer(token)
                    parameter("space", space)
                }
            assertEquals(HttpStatusCode.OK, before.status)
            runBlocking {
                val account = requireNotNull(env.accountRepository.findByDid(did))
                env.accountRepository.deactivateAccount(account.id)
            }
            val unauthenticated = everySpaceMethod(space) { _, _ -> }
            everySpaceMethod(space) { _, _ -> bearer(token) }.forEach { (method, response) ->
                assertEquals(HttpStatusCode.Unauthorized, response.status, method)
                assertContentEquals(unauthenticated.getValue(method).bodyAsBytes(), response.bodyAsBytes(), method)
            }
        }

    @Test
    fun `an upload over the limit is refused by its declared length`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceMaxBlobBytes = 1024)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val tooLarge = uploadChunked(token, space, ByteArray(1025))
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertEquals("BlobTooLarge", tooLarge.errorCode())
            assertEquals(HttpStatusCode.OK, uploadChunked(token, space, ByteArray(1024)).status)
            assertEquals(HttpStatusCode.PayloadTooLarge, uploadBlob(token, space, ByteArray(1025)).status)
        }

    @Test
    fun `malformed keys and CIDs are invalid for the owner and missing for anyone else`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, owner) = env.signedInAccount("alice")
            val (_, other) = env.signedInAccount("bob")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val badKey = "no spaces/allowed"
            val badCid = "not-a-cid"

            fun deleteRecord(token: String) =
                runBlocking {
                    client.post("/xrpc/app.logdate.space.deleteRecord") {
                        bearer(token)
                        json("""{"space":"$space","rkey":"$badKey"}""")
                    }
                }

            fun putWithBadSwap(token: String) =
                runBlocking {
                    client.post("/xrpc/app.logdate.space.putRecord") {
                        bearer(token)
                        json("""{"space":"$space","rkey":"abc","record":${sealedJson()},"swapCid":"$badCid"}""")
                    }
                }
            assertEquals("InvalidRequest", getRecord(owner, space, badKey).errorCode())
            assertEquals("InvalidRequest", getBlob(owner, space, badCid).errorCode())
            assertEquals("InvalidRequest", deleteRecord(owner).errorCode())
            assertEquals("InvalidRequest", putWithBadSwap(owner).errorCode())
            assertEquals("RecordNotFound", getRecord(other, space, badKey).errorCode())
            assertEquals("BlobNotFound", getBlob(other, space, badCid).errorCode())
            assertEquals("RecordNotFound", deleteRecord(other).errorCode())
            assertEquals("RecordNotFound", putWithBadSwap(other).errorCode())
        }

    @Test
    fun `too many record writes answer RateLimitExceeded with when to retry`() =
        testApplication {
            val tight = RateLimitPolicy(maxRequests = 2, windowSeconds = 60)
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceRateLimits = SpaceRateLimits(recordWrites = tight, reads = tight, blobUploads = tight),
                )
            val (did, token) = env.signedInAccount("alice")
            val (otherDid, other) = env.signedInAccount("bob")
            val space = "at://$did/space/app.logdate.space.personal/self"
            repeat(2) { assertEquals(HttpStatusCode.OK, putRecord(token, space, "r$it").status) }

            val limited = putRecord(token, space, "r2")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("RateLimitExceeded", limited.errorCode())
            val retryAfter = assertNotNull(limited.headers[HttpHeaders.RetryAfter]).toLong()
            assertTrue(retryAfter in 1..60, "Retry-After: $retryAfter")
            val delete =
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"r0"}""")
                }
            assertEquals("RateLimitExceeded", delete.errorCode(), "deletes share the record write budget")

            assertEquals(HttpStatusCode.OK, getRecord(token, space, "r0").status, "reads have their own budget")
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(10)).status, "uploads have their own budget")
            val otherSpace = "at://$otherDid/space/app.logdate.space.personal/self"
            assertEquals(HttpStatusCode.OK, putRecord(other, otherSpace, "r0").status, "each account has its own budget")
        }

    @Test
    fun `reads and blob downloads share one budget and blob uploads have another`() =
        testApplication {
            val tight = RateLimitPolicy(maxRequests = 3, windowSeconds = 60)
            val env =
                configureAuthV1TestApp(personalSpaceEnabled = true, spaceRateLimits = SpaceRateLimits(reads = tight, blobUploads = tight))
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val bytes = ByteArray(10) { 3 }
            repeat(3) { assertEquals(HttpStatusCode.OK, uploadBlob(token, space, bytes).status) }
            assertEquals("RateLimitExceeded", uploadBlob(token, space, bytes).errorCode())

            val cid = Cid.rawSha256(bytes).value
            assertEquals("RecordNotFound", getRecord(token, space, "missing").errorCode())
            assertEquals(HttpStatusCode.OK, getBlob(token, space, cid).status)
            assertEquals(HttpStatusCode.OK, client.get("/xrpc/app.logdate.space.listRepoOps?space=$space") { bearer(token) }.status)
            val limited = getBlob(token, space, cid)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("RateLimitExceeded", limited.errorCode())
        }

    @Test
    fun `with the feature off a rate limit never hides that the method isn't implemented`() =
        testApplication {
            val tight = RateLimitPolicy(maxRequests = 1, windowSeconds = 60)
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = false,
                    spaceRateLimits = SpaceRateLimits(recordWrites = tight, reads = tight, blobUploads = tight),
                )
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            repeat(3) { assertEquals("MethodNotImplemented", putRecord(token, space, "r$it").errorCode()) }
        }

    @Test
    fun `the default budgets let a device sync thousands of records as fast as it can send them`() {
        val limits = SpaceRateLimits()
        val limiter = FixedWindowRateLimiter()
        val start = 1_000_000L
        // One request every 15 ms is faster than a phone can send them one after another.
        val writes = (0 until 10_000).count { limiter.allow("writes", limits.recordWrites, start + it * 15L) }
        assertEquals(10_000, writes)
        val reads = (0 until 10_000).count { limiter.allow("reads", limits.reads, start + it * 15L) }
        assertEquals(10_000, reads)
        val uploads = (0 until 2_000).count { limiter.allow("uploads", limits.blobUploads, start + it * 60L) }
        assertEquals(2_000, uploads)
    }

    @Test
    fun `parallel requests from two devices share one account budget`() =
        testApplication {
            val tight = RateLimitPolicy(maxRequests = 5, windowSeconds = 60)
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceRateLimits = SpaceRateLimits(recordWrites = tight))
            val (did, phone) = env.signedInAccount("alice")
            val ownerId = runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString()
            val tablet = env.tokenService.generateAccessToken(ownerId)
            val space = "at://$did/space/app.logdate.space.personal/self"
            val statuses =
                coroutineScope {
                    (0 until 20)
                        .map { index ->
                            async { putRecord(if (index % 2 == 0) phone else tablet, space, "r$index").status }
                        }.awaitAll()
                }
            assertEquals(5, statuses.count { it == HttpStatusCode.OK }, "$statuses")
            assertEquals(15, statuses.count { it == HttpStatusCode.TooManyRequests }, "$statuses")
        }

    @Test
    fun `a record write that would pass the storage quota answers QuotaExceeded`() =
        testApplication {
            val usage = MutableUsage()
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceEntitlementEnforcer = enforcer(limit = 10_000, usage))
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            assertEquals(HttpStatusCode.OK, putRecord(token, space, "kept").status)

            usage.outsideSpaces = 10_000
            val refused = putRecord(token, space, "new")
            assertEquals(HttpStatusCode.PaymentRequired, refused.status)
            assertEquals("QuotaExceeded", refused.errorCode())
            assertEquals(HttpStatusCode.BadRequest, getRecord(token, space, "new").status, "nothing was stored")

            assertEquals(HttpStatusCode.OK, putRecord(token, space, "kept", fill = 2).status, "a same-size replacement adds nothing")
            val delete =
                client.post("/xrpc/app.logdate.space.deleteRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"kept"}""")
                }
            assertEquals(HttpStatusCode.OK, delete.status, "a delete frees space even over the quota")
        }

    @Test
    fun `a record that grows past the storage quota is refused but a smaller one is stored`() =
        testApplication {
            val usage = MutableUsage()
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceEntitlementEnforcer = enforcer(limit = 10_000, usage))
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            assertEquals(HttpStatusCode.OK, putRecord(token, space, "grow", ciphertextBytes = 2_048 + 16).status)

            usage.outsideSpaces = 10_000
            assertEquals("QuotaExceeded", putRecord(token, space, "grow", ciphertextBytes = 4_096 + 16).errorCode())
            assertEquals(HttpStatusCode.OK, putRecord(token, space, "grow", ciphertextBytes = 528).status)
        }

    @Test
    fun `a write that fails its swap condition is a swap conflict even past the quota`() =
        testApplication {
            val usage = MutableUsage()
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceEntitlementEnforcer = enforcer(limit = 10_000, usage))
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            assertEquals(HttpStatusCode.OK, putRecord(token, space, "taken").status)
            usage.outsideSpaces = 10_000
            val conflict =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearer(token)
                    json("""{"space":"$space","rkey":"taken","record":${sealedJson(ciphertextBytes = 4_096 + 16)},"createOnly":true}""")
                }
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("InvalidSwap", conflict.errorCode())
        }

    @Test
    fun `a blob upload past the storage quota is refused before anything is stored`() =
        testApplication {
            val usage = MutableUsage()
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceEntitlementEnforcer = enforcer(limit = 10_000, usage))
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(600) { 1 }).status)
            val ownerId = java.util.UUID.fromString(runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString())
            val before = env.blobStorage.listOwnerBlobPaths(ownerId)

            usage.outsideSpaces = 9_000
            val refused = uploadBlob(token, space, ByteArray(600) { 2 })
            assertEquals(HttpStatusCode.PaymentRequired, refused.status)
            assertEquals("QuotaExceeded", refused.errorCode())
            assertEquals(before, env.blobStorage.listOwnerBlobPaths(ownerId))
            assertEquals(600L, runBlocking { env.spaceStore.storedBytes(ownerId) })
        }

    @Test
    fun `uploading a file the space already holds adds nothing to the stored bytes`() =
        testApplication {
            val usage = MutableUsage()
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceEntitlementEnforcer = enforcer(limit = 10_000, usage))
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val stored = ByteArray(600) { 1 }
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, stored).status)
            val ownerId = java.util.UUID.fromString(runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString())

            usage.outsideSpaces = 10_000 - 600 - 600
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, stored).status)
            assertEquals(600L, runBlocking { env.spaceStore.storedBytes(ownerId) })
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(600) { 3 }).status, "the repeat upload held no room")
            assertEquals(1_200L, runBlocking { env.spaceStore.storedBytes(ownerId) })
        }

    @Test
    fun `an upload past the instance's transfer slots answers ServerBusy with when to retry`() =
        testApplication {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val saving = InMemorySpaceStore()
            val store =
                object : SpaceStore by saving {
                    override suspend fun reserveUpload(
                        accountId: java.util.UUID,
                        bytes: Long,
                        quota: SpaceQuota,
                        identityEpoch: Long,
                    ): SpaceUploadReservation {
                        if (!entered.isCompleted) {
                            entered.complete(Unit)
                            proceed.await()
                        }
                        return saving.reserveUpload(accountId, bytes, quota, identityEpoch)
                    }
                }
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceStore = store,
                    spaceTransferLimits = TransferLimits(streamedUploads = 1, downloads = 1, retryAfterSeconds = 7),
                    spaceRateLimits = SpaceRateLimits(blobUploads = RateLimitPolicy(maxRequests = 2, windowSeconds = 60)),
                    spaceEntitlementEnforcer = enforcer(limit = 1_000_000, MutableUsage()),
                )
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            coroutineScope {
                val first = async { uploadBlob(token, space, ByteArray(600) { 5 }) }
                entered.await()
                val busy = uploadBlob(token, space, ByteArray(600) { 6 })
                assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
                assertEquals("ServerBusy", busy.errorCode())
                assertEquals("7", busy.headers[HttpHeaders.RetryAfter])
                proceed.complete(Unit)
                assertEquals(HttpStatusCode.OK, first.await().status)
            }
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(600) { 6 }).status, "the slot is free again")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                uploadBlob(token, space, ByteArray(600) { 7 }).status,
                "only the two uploads that got a slot spent the budget of two",
            )
        }

    @Test
    fun `an upload that fails after reserving its room frees the room`() =
        testApplication {
            val saving = InMemorySpaceStore()
            var failNext = true
            val store =
                object : SpaceStore by saving {
                    override suspend fun putBlob(
                        accountId: java.util.UUID,
                        space: String,
                        blob: SpaceBlob,
                        quota: SpaceQuota?,
                        reservation: SpaceUploadReservation?,
                        identityEpoch: Long,
                    ): SpaceBlob {
                        if (failNext) {
                            failNext = false
                            error("The metadata store is down")
                        }
                        return saving.putBlob(accountId, space, blob, quota, reservation, identityEpoch)
                    }
                }
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceStore = store,
                    spaceEntitlementEnforcer = enforcer(limit = 1_000, MutableUsage()),
                )
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"

            val failed = runCatching { uploadBlob(token, space, ByteArray(600) { 1 }) }
            assertTrue(failed.isFailure || failed.getOrThrow().status == HttpStatusCode.InternalServerError, "$failed")

            assertEquals(
                HttpStatusCode.OK,
                uploadBlob(token, space, ByteArray(600) { 2 }).status,
                "the failed upload's 600 bytes would leave no room for this one if it were still held",
            )
        }

    @Test
    fun `a body that isn't as long as its Content-Length is refused and frees its room`() =
        testApplication {
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceEntitlementEnforcer = enforcer(limit = 1_000, MutableUsage()),
                )
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val ownerId = java.util.UUID.fromString(runBlocking { requireNotNull(env.accountRepository.findByDid(did)) }.id.toString())

            val short = uploadSized(token, space, declared = 600, body = ByteArray(300))
            assertEquals(HttpStatusCode.BadRequest, short.status)
            assertEquals(emptyList(), env.blobStorage.listOwnerBlobPaths(ownerId), "the partial object is deleted")

            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(600) { 3 }).status, "the room was freed")
        }

    @Test
    fun `an upload without a Content-Length is refused before anything is received`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"

            val refused = uploadUnsized(token, space, ByteArray(600))

            assertEquals(HttpStatusCode.LengthRequired, refused.status)
            assertEquals("LengthRequired", refused.errorCode())
            assertEquals(HttpStatusCode.OK, uploadChunked(token, space, ByteArray(600)).status)
        }

    @Test
    fun `an upload whose client stops sending is ended and frees its slot and room`() =
        testApplication {
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceTransferLimits = TransferLimits(streamedUploads = 1, idleTimeout = 300.milliseconds),
                    spaceEntitlementEnforcer = enforcer(limit = 1_000, MutableUsage()),
                )
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"

            val stalled = uploadStalled(token, space, declared = 600)

            assertEquals(HttpStatusCode.RequestTimeout, stalled.status)
            assertEquals("UploadStalled", stalled.errorCode())
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, ByteArray(600) { 4 }).status, "slot and room are free again")
        }

    @Test
    fun `a download past the instance's transfer slots answers ServerBusy, and a finished download frees its slot`() =
        testApplication {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val saving = InMemorySpaceStore()
            var hold = false
            val store =
                object : SpaceStore by saving {
                    override suspend fun getBlob(
                        space: String,
                        cid: String,
                    ): SpaceBlob? {
                        if (hold) {
                            hold = false
                            entered.complete(Unit)
                            proceed.await()
                        }
                        return saving.getBlob(space, cid)
                    }
                }
            val env =
                configureAuthV1TestApp(
                    personalSpaceEnabled = true,
                    spaceStore = store,
                    spaceTransferLimits = TransferLimits(downloads = 1, retryAfterSeconds = 9),
                    spaceRateLimits = SpaceRateLimits(reads = RateLimitPolicy(maxRequests = 3, windowSeconds = 60)),
                )
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val bytes = ByteArray(600) { 8 }
            val cid = Cid.rawSha256(bytes).value
            assertEquals(HttpStatusCode.OK, uploadBlob(token, space, bytes).status)

            hold = true
            coroutineScope {
                val first = async { getBlob(token, space, cid) }
                entered.await()
                val busy = getBlob(token, space, cid)
                assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
                assertEquals("ServerBusy", busy.errorCode())
                assertEquals("9", busy.headers[HttpHeaders.RetryAfter])
                proceed.complete(Unit)
                assertContentEquals(bytes, first.await().bodyAsBytes())
            }

            assertContentEquals(bytes, getBlob(token, space, cid).bodyAsBytes(), "the slot is free after the body was sent")
            assertContentEquals(bytes, getBlob(token, space, cid).bodyAsBytes(), "and again")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                getBlob(token, space, cid).status,
                "only the three downloads that got a slot spent the budget of three",
            )
        }

    @Test
    fun `a large blob streams through without leaving temporary files behind`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true, spaceMaxBlobBytes = 8L * 1024 * 1024)
            val (did, token) = env.signedInAccount("alice")
            val space = "at://$did/space/app.logdate.space.personal/self"
            val before = spaceTempFiles()
            val bytes = ByteArray(6 * 1024 * 1024) { (it * 31).toByte() }
            val upload = uploadChunked(token, space, bytes)
            assertEquals(HttpStatusCode.OK, upload.status, upload.bodyAsText())
            val cid = Cid.rawSha256(bytes).value
            assertTrue(cid in upload.bodyAsText())

            val read = getBlob(token, space, cid)
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("private, no-store", read.headers[HttpHeaders.CacheControl])
            assertEquals(bytes.size.toString(), read.headers[HttpHeaders.ContentLength])
            assertContentEquals(bytes, read.bodyAsBytes())

            assertEquals(HttpStatusCode.PayloadTooLarge, uploadChunked(token, space, ByteArray(8 * 1024 * 1024 + 1)).status)
            assertEquals(before, spaceTempFiles())
        }

    private fun spaceTempFiles(): Set<String> =
        java.io
            .File(System.getProperty("java.io.tmpdir"))
            .list { _, name -> name.startsWith("logdate-space-blob-") }
            .orEmpty()
            .toSet()

    private suspend fun ApplicationTestBuilder.putRecord(
        token: String,
        space: String,
        rkey: String,
        fill: Int = 1,
        ciphertextBytes: Int = 528,
    ): HttpResponse =
        client.post("/xrpc/app.logdate.space.putRecord") {
            bearer(token)
            json("""{"space":"$space","rkey":"$rkey","record":${sealedJson(fill, ciphertextBytes)}}""")
        }

    private class MutableUsage : UsageCalculator {
        @Volatile var outsideSpaces = 0L

        override suspend fun storageBytes(accountId: java.util.UUID): Long = outsideSpaces

        override suspend fun storageBytesOutsideSpaces(accountId: java.util.UUID): Long = outsideSpaces

        override suspend fun backupCount(accountId: java.util.UUID): Int = 0
    }

    private fun enforcer(
        limit: Long,
        usage: UsageCalculator,
    ): EntitlementEnforcer =
        EntitlementEnforcer(
            entitlementService =
                object : EntitlementService {
                    override suspend fun resolve(accountId: java.util.UUID): Entitlement =
                        Entitlement(
                            planId = "standard",
                            tier = EntitlementTier.STANDARD,
                            status = EntitlementStatus.ACTIVE,
                            limits = EntitlementLimits(storageBytes = limit, backupCount = null),
                        )
                },
            usageCalculator = usage,
        )

    private suspend fun ApplicationTestBuilder.everySpaceMethod(
        space: String,
        authorize: HttpRequestBuilder.(method: String, path: String) -> Unit,
    ): Map<String, HttpResponse> {
        val bytes = ByteArray(600)
        val cid = Cid.rawSha256(bytes).value

        fun path(method: String) = "/xrpc/app.logdate.space.$method"
        return linkedMapOf(
            "putRecord" to
                client.post(path("putRecord")) {
                    authorize("POST", path("putRecord"))
                    json("""{"space":"$space","rkey":"abc","record":${sealedJson()},"blobs":["$cid"]}""")
                },
            "getRecord" to
                client.get(path("getRecord")) {
                    authorize("GET", path("getRecord"))
                    parameter("space", space)
                    parameter("rkey", "abc")
                },
            "listRepoOps" to
                client.get(path("listRepoOps")) {
                    authorize("GET", path("listRepoOps"))
                    parameter("space", space)
                },
            "uploadBlob" to
                client.post(path("uploadBlob")) {
                    authorize("POST", path("uploadBlob"))
                    parameter("space", space)
                    header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    setBody(bytes)
                },
            "getBlob" to
                client.get(path("getBlob")) {
                    authorize("GET", path("getBlob"))
                    parameter("space", space)
                    parameter("cid", cid)
                },
            "deleteRecord" to
                client.post(path("deleteRecord")) {
                    authorize("POST", path("deleteRecord"))
                    json("""{"space":"$space","rkey":"abc"}""")
                },
        )
    }

    /** Sends [bytes] in small pieces with the Content-Length a client that knows the file's size sends. */
    private suspend fun ApplicationTestBuilder.uploadChunked(
        token: String,
        space: String,
        bytes: ByteArray,
    ): HttpResponse = uploadSized(token, space, declared = bytes.size.toLong(), body = bytes)

    /** Sends [body] in small pieces while declaring a Content-Length of [declared]. */
    private suspend fun ApplicationTestBuilder.uploadSized(
        token: String,
        space: String,
        declared: Long,
        body: ByteArray,
    ): HttpResponse =
        client.post("/xrpc/app.logdate.space.uploadBlob") {
            bearer(token)
            parameter("space", space)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override val contentLength = declared

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        body.asList().chunked(256).forEach { channel.writeFully(it.toByteArray()) }
                    }
                },
            )
        }

    /** Sends [bytes] with no Content-Length, as a chunked request does. */
    private suspend fun ApplicationTestBuilder.uploadUnsized(
        token: String,
        space: String,
        bytes: ByteArray,
    ): HttpResponse =
        client.post("/xrpc/app.logdate.space.uploadBlob") {
            bearer(token)
            parameter("space", space)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        bytes.asList().chunked(256).forEach { channel.writeFully(it.toByteArray()) }
                    }
                },
            )
        }

    /** Declares [declared] bytes, sends some of them, then goes quiet without ending the request. */
    private suspend fun ApplicationTestBuilder.uploadStalled(
        token: String,
        space: String,
        declared: Long,
    ): HttpResponse =
        client.post("/xrpc/app.logdate.space.uploadBlob") {
            bearer(token)
            parameter("space", space)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override val contentLength = declared

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(ByteArray(100))
                        channel.flush()
                        delay(5_000)
                    }
                },
            )
        }

    private suspend fun ApplicationTestBuilder.getRecord(
        token: String,
        space: String,
        rkey: String,
    ): HttpResponse =
        client.get("/xrpc/app.logdate.space.getRecord") {
            bearer(token)
            parameter("space", space)
            parameter("rkey", rkey)
        }

    private suspend fun ApplicationTestBuilder.uploadBlob(
        token: String,
        space: String,
        bytes: ByteArray,
    ): HttpResponse =
        client.post("/xrpc/app.logdate.space.uploadBlob") {
            bearer(token)
            parameter("space", space)
            header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            setBody(bytes)
        }

    private suspend fun ApplicationTestBuilder.getBlob(
        token: String,
        space: String,
        cid: String,
    ): HttpResponse =
        client.get("/xrpc/app.logdate.space.getBlob") {
            bearer(token)
            parameter("space", space)
            parameter("cid", cid)
        }
}

private suspend fun HttpResponse.errorCode(): String? =
    Json
        .parseToJsonElement(bodyAsText())
        .jsonObject["error"]
        ?.jsonPrimitive
        ?.content

private fun AuthV1TestEnvironment.accountId(did: String): Uuid = runBlocking { requireNotNull(accountRepository.findByDid(did)).id }

private fun AuthV1TestEnvironment.signedInAccount(username: String): Pair<String, String> =
    runBlocking {
        val saved =
            accountRepository.save(
                Account(id = Uuid.random(), username = username, displayName = username, createdAt = Clock.System.now()),
            )
        val account = atprotoIdentityService.ensureIdentity(saved)
        requireNotNull(account.did) to tokenService.generateAccessToken(account.id.toString())
    }

private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private fun HttpRequestBuilder.json(body: String) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(body)
}

private fun sealedJson(
    fill: Int = 1,
    ciphertextBytes: Int = 528,
): String {
    val nonce = Base64.encode(ByteArray(12))
    val ciphertext = Base64.encode(ByteArray(ciphertextBytes) { fill.toByte() })
    return """{"${'$'}type":"app.logdate.crypto.sealed","version":1,"keyId":"abcdefghijklm",""" +
        """"nonce":{"${'$'}bytes":"$nonce"},"ciphertext":{"${'$'}bytes":"$ciphertext"}}"""
}
