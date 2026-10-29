package app.logdate.server

import app.logdate.server.routes.serverMetaRoutes
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerInfoResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * High-level integration tests for the [Application], verifying that the Ktor
 * server correctly mounts and responds to its primary endpoints.
 *
 * This suite covers root routes, health checks, OAuth discovery metadata,
 * API v1 mounting, and the availability of OpenAPI/Scalar documentation.
 */
class ApplicationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `root json for non browsers`() =
        testApplication {
            application {
                module()
            }

            client.get("/").apply {
                assertEquals(HttpStatusCode.OK, status)
                val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                assertEquals("LogDate Server API", payload["name"]?.jsonPrimitive?.content)
                assertEquals("/docs", payload["docs"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `root html for browsers`() =
        testApplication {
            application {
                module()
            }

            client
                .get("/") {
                    header(HttpHeaders.Accept, "text/html")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val body = bodyAsText()
                    assertTrue(body.contains("<title>LogDate API</title>"))
                    assertTrue(body.contains("/docs"))
                }
        }

    @Test
    fun `health omits internal details without token`() =
        testApplication {
            application {
                healthProbe("shh-secret")
            }

            client.get("/health").apply {
                assertEquals(HttpStatusCode.OK, status)
                val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                assertEquals("healthy", payload["status"]?.jsonPrimitive?.content)
                assertEquals(HEALTH_RELEASE, payload["release"]?.jsonPrimitive?.content)
                assertNull(
                    payload["db_connected"],
                    "public /health must not leak deployment internals",
                )
            }
        }

    @Test
    fun `health omits internal details with wrong token`() =
        testApplication {
            application {
                healthProbe("shh-secret")
            }

            client
                .get("/health") {
                    header("X-LogDate-Health-Token", "wrong")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                    assertNull(payload["db_connected"], "wrong token must not unlock internals")
                }
        }

    @Test
    fun `health omits internal details when token unconfigured`() =
        testApplication {
            application {
                healthProbe("")
            }

            // No token configured = even a header-bearing caller stays public.
            client
                .get("/health") {
                    header("X-LogDate-Health-Token", "anything")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                    assertNull(payload["db_connected"])
                }
        }

    @Test
    fun `health includes db connected with correct token`() =
        testApplication {
            application {
                healthProbe("shh-secret")
            }

            client
                .get("/health") {
                    header("X-LogDate-Health-Token", "shh-secret")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                    assertEquals("healthy", payload["status"]?.jsonPrimitive?.content)
                    assertEquals(HEALTH_RELEASE, payload["release"]?.jsonPrimitive?.content)
                    assertEquals(true, payload["db_connected"]?.jsonPrimitive?.content?.toBoolean())
                }
        }

    @Test
    fun `o auth discovery routes`() =
        testApplication {
            application {
                module()
            }

            client.get("/.well-known/oauth-authorization-server").apply {
                assertEquals(HttpStatusCode.OK, status)
                val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                assertEquals("https://logdate.app/oauth/authorize", payload["authorization_endpoint"]?.jsonPrimitive?.content)
            }

            client.get("/oauth/jwks").apply {
                assertEquals(HttpStatusCode.OK, status)
                val payload = json.parseToJsonElement(bodyAsText()).jsonObject
                assertTrue(payload["keys"]?.jsonArray?.isNotEmpty() == true)
            }
        }

    @Test
    fun `api v 1 base route`() =
        testApplication {
            application {
                module()
            }

            // Test that API routes are properly mounted
            client.get("/api/v1/auth/signup/username/testuser/available").apply {
                assertEquals(HttpStatusCode.OK, status)
                val responseBody = bodyAsText()
                assertTrue(responseBody.contains("success"))
                assertTrue(responseBody.contains("data"))
            }

            client.get("/api/v1/server/info").apply {
                assertEquals(HttpStatusCode.OK, status)
                val payload = json.decodeFromString<ServerInfoResponse>(bodyAsText())
                assertTrue(payload.success)
                assertTrue(payload.data.capabilities.contains(ServerCapability.AUTH_PASSKEY))
                assertTrue(payload.data.capabilities.contains(ServerCapability.SYNC_CONTENT))
            }
        }

    @Test
    fun `open api json`() =
        testApplication {
            application {
                module()
            }

            client.get("/openapi.json").apply {
                assertEquals(HttpStatusCode.OK, status)
                val responseBody = bodyAsText()
                assertTrue(responseBody.contains("\"openapi\""))
                assertTrue(responseBody.contains("\"/api/v1/auth/signup/google\""))
                assertTrue(responseBody.contains("\"/api/v1/media\""))
                assertTrue(responseBody.contains("\"bearerAuth\""))
            }
        }

    @Test
    fun `open api yaml`() =
        testApplication {
            application {
                module()
            }

            client.get("/openapi.yaml").apply {
                assertEquals(HttpStatusCode.OK, status)
                val responseBody = bodyAsText()
                assertTrue(responseBody.contains("openapi:"))
                assertTrue(responseBody.contains("/api/v1/backups:"))
            }
        }

    @Test
    fun `scalar api reference`() =
        testApplication {
            application {
                module()
            }

            client.get("/docs").apply {
                assertEquals(HttpStatusCode.OK, status)
                val responseBody = bodyAsText()
                assertTrue(responseBody.contains("LogDate Cloud API Reference"))
                assertTrue(responseBody.contains("/openapi.json"))
                assertTrue(responseBody.contains("bearerAuth"), "bearer auth should be preselected in the Try-it panel")
                assertTrue(Regex("\"telemetry\"\\s*:\\s*false").containsMatchIn(responseBody), "Scalar telemetry must stay off")
                assertTrue(responseBody.contains("/favicon.svg"))
            }
            client.get("/favicon.svg").apply {
                assertEquals(HttpStatusCode.OK, status)
                assertTrue(contentType()?.match(ContentType.Image.SVG) == true)
            }
            client.get("/docs/scalar.js").apply {
                assertEquals(HttpStatusCode.OK, status)
                assertTrue(contentType()?.match(ContentType.Application.JavaScript) == true)
                assertTrue(bodyAsText().isNotBlank())
            }
            client.get("/swagger").apply {
                assertEquals(HttpStatusCode.NotFound, status)
            }
        }

    @Test
    fun `published open api operations are complete`() =
        testApplication {
            application { module() }
            client.get("/openapi.json").apply {
                val document = json.parseToJsonElement(bodyAsText()).jsonObject
                val paths = assertNotNull(document["paths"]?.jsonObject)
                setOf("/health", "/docs", "/api/v1/auth/metrics", "/api/v1/ops/sync/metrics").forEach {
                    assertFalse(paths.containsKey(it), "$it must not be published")
                }
                val ids = mutableSetOf<String>()
                paths.forEach { (path, item) ->
                    item.jsonObject.forEach operation@{ (method, value) ->
                        if (method !in setOf("get", "head", "post", "put", "patch", "delete")) return@operation
                        val operation = value.jsonObject
                        val label = "$method $path"
                        assertTrue(operation["summary"]?.jsonPrimitive?.content?.isNotBlank() == true, "$label needs summary")
                        assertTrue(operation["description"]?.jsonPrimitive?.content?.isNotBlank() == true, "$label needs description")
                        assertTrue(operation["tags"]?.jsonArray?.isNotEmpty() == true, "$label needs tags")
                        assertTrue(operation["responses"]?.jsonObject?.isNotEmpty() == true, "$label needs responses")
                        val id = assertNotNull(operation["operationId"]?.jsonPrimitive?.content)
                        assertTrue(ids.add(id), "duplicate operationId $id")
                    }
                }
                val schemes = assertNotNull(document["components"]?.jsonObject?.get("securitySchemes")?.jsonObject)
                assertTrue(setOf("bearerAuth", "dpopProof", "oauth2").all(schemes::containsKey))
                assertTrue(
                    document["components"]
                        ?.jsonObject
                        ?.get("schemas")
                        ?.jsonObject
                        ?.size ?: 0 >= 34,
                )
            }
        }

    private fun Application.healthProbe(token: String) {
        installJsonSerialization()
        routing { serverMetaRoutes(isDatabaseAvailable = true, healthInternalToken = token, releaseVersion = HEALTH_RELEASE) }
    }

    companion object {
        private const val HEALTH_RELEASE = "logdate-server@0123456789abcdef0123456789abcdef01234567"
    }
}
