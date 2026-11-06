package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.configureAuthV1TestApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class XrpcServiceProxyTest {
    @Test
    fun `forwards only authorized protocol requests to the resolved service`() =
        testApplication {
            var upstreamCalls = 0
            val engine =
                MockEngine { request ->
                    upstreamCalls++
                    when (request.url.host) {
                        "labeler.example" -> {
                            assertEquals("/.well-known/did.json", request.url.encodedPath)
                            assertNull(request.headers[HttpHeaders.Authorization])
                            respond(
                                """
                                {"id":"did:web:labeler.example","service":[
                                    {"id":"#atproto_labeler","type":"Labeler","serviceEndpoint":"https://service.example"}
                                ]}
                                """.trimIndent(),
                            )
                        }

                        "api.bsky.app" -> {
                            assertEquals(HttpMethod.Get, request.method)
                            if (request.url.encodedPath == "/.well-known/did.json") {
                                assertNull(request.headers[HttpHeaders.Authorization])
                                respond(
                                    """
                                    {"id":"did:web:api.bsky.app","service":[
                                        {"id":"#bsky_appview","type":"BskyAppView","serviceEndpoint":"https://api.bsky.app"}
                                    ]}
                                    """.trimIndent(),
                                )
                            } else {
                                assertEquals("/xrpc/app.bsky.actor.getProfile", request.url.encodedPath)
                                val claims = tokenClaims(request.headers[HttpHeaders.Authorization].orEmpty())
                                assertEquals("did:web:api.bsky.app", claims["aud"]?.jsonPrimitive?.content)
                                respond(
                                    "",
                                    HttpStatusCode.Found,
                                    headersOf(HttpHeaders.Location, "https://elsewhere.example"),
                                )
                            }
                        }

                        "service.example" -> {
                            assertEquals("/xrpc/com.atproto.moderation.createReport", request.url.encodedPath)
                            assertEquals(listOf("one", "two"), request.url.parameters.getAll("filter"))
                            assertEquals("did:web:other.example", request.headers["atproto-accept-labelers"])
                            assertNull(request.headers[HttpHeaders.Cookie])
                            assertNull(request.headers["atproto-proxy"])
                            val authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                            val claims = tokenClaims(authorization)
                            assertEquals("did:web:labeler.example#atproto_labeler", claims["aud"]?.jsonPrimitive?.content)
                            assertEquals("com.atproto.moderation.createReport", claims["lxm"]?.jsonPrimitive?.content)
                            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                            assertEquals("""{"reasonType":"test"}""", body)
                            val contentType = request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString()
                            assertEquals("application/json", contentType)
                            respond(
                                """{"id":1}""",
                                HttpStatusCode.Created,
                                headersOf(
                                    HttpHeaders.ContentType to listOf("application/json"),
                                    "atproto-content-labelers" to listOf("did:web:other.example"),
                                    HttpHeaders.SetCookie to listOf("remote=1"),
                                ),
                            )
                        }

                        else -> {
                            error("Unexpected upstream host")
                        }
                    }
                }
            HttpClient(engine) { followRedirects = false }.use { http ->
                val env = configureAuthV1TestApp(serviceProxyHttp = http)
                val account =
                    runBlocking {
                        val saved =
                            env.accountRepository.save(
                                Account(
                                    id = Uuid.random(),
                                    username = "alice",
                                    displayName = "Alice",
                                    createdAt = Clock.System.now(),
                                ),
                            )
                        env.atprotoIdentityService.ensureIdentity(saved)
                    }
                val accessToken = env.tokenService.generateAccessToken(account.id.toString())
                val path = "/xrpc/com.atproto.moderation.createReport?filter=one&filter=two"
                val privateResult =
                    client.post(path) {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("atproto-proxy", "did:web:labeler.example#atproto_labeler")
                    }
                assertEquals(HttpStatusCode.Forbidden, privateResult.status)
                assertEquals(0, upstreamCalls)
                runBlocking { check(env.accountRepository.recordPublicIdentity(account.id, requireNotNull(account.did))) }

                val unsupported =
                    client.get("/xrpc/com.atproto.admin.getAccountInfo") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("atproto-proxy", "did:web:labeler.example#atproto_labeler")
                    }
                assertEquals(HttpStatusCode.Forbidden, unsupported.status)
                assertEquals(0, upstreamCalls)

                val response =
                    client.post(path) {
                        header(HttpHeaders.Origin, "https://client.example")
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header(HttpHeaders.ContentType, "application/json")
                        header(HttpHeaders.Cookie, "local=1")
                        header("atproto-proxy", "did:web:labeler.example#atproto_labeler")
                        header("atproto-accept-labelers", "did:web:other.example")
                        setBody("""{"reasonType":"test"}""")
                    }
                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                assertEquals("""{"id":1}""", response.bodyAsText())
                assertEquals("did:web:other.example", response.headers["atproto-content-labelers"])
                assertNull(response.headers[HttpHeaders.SetCookie])
                assertTrue("atproto-content-labelers" in response.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().lowercase())
                assertEquals(2, upstreamCalls)

                val redirected =
                    client.get("/xrpc/app.bsky.actor.getProfile") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                    }
                assertEquals(HttpStatusCode.BadGateway, redirected.status)
                assertNull(redirected.headers[HttpHeaders.Location])
                assertEquals(4, upstreamCalls)
            }
        }

    private fun tokenClaims(authorization: String): JsonObject {
        assertTrue(authorization.startsWith("Bearer "))
        val payload = Base64.getUrlDecoder().decode(authorization.split('.')[1]).decodeToString()
        return Json.parseToJsonElement(payload).jsonObject
    }
}
