package app.logdate.server.openapi

import app.logdate.server.module
import app.logdate.server.routes.atprotoConnectionRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtprotoConnectionOpenApiTest {
    @Test
    fun `reconnect contract accepts app proof without a replacement identity and returns only status`() =
        withSpec { doc ->
            val start = operation(doc, "/reconnect/start", "post")
            val complete = operation(doc, "/reconnect/complete", "post")
            assertTrue(start["security"]!!.jsonArray.isNotEmpty())
            assertTrue(complete["security"]!!.jsonArray.isNotEmpty())
            assertEquals(setOf("challenge"), requestFields(doc, start))
            assertEquals(setOf("attemptId", "authorizationUrl"), responseFields(doc, start))
            assertEquals(setOf("attemptId", "verifier"), requestFields(doc, complete))
            assertEquals(setOf("state", "revision", "observedAt", "eligible", "canDisconnect"), responseFields(doc, complete))
        }

    @Test
    fun `connection operations publish bearer protection and the native response contracts`() =
        withSpec { doc ->
            val base = operation(doc, "", "get")
            assertTrue(base["security"]!!.jsonArray.isNotEmpty())
            assertEquals(setOf("state", "revision", "observedAt", "eligible", "canDisconnect"), responseFields(doc, base))
            val begin = operation(doc, "/step-up", "post")
            assertTrue(begin["security"]!!.jsonArray.isNotEmpty())
            assertEquals(setOf("expectedRevision"), requestFields(doc, begin))
            assertEquals(setOf("decisionId", "expectedRevision", "authenticationOptions", "expiresAt"), responseFields(doc, begin))
            val finish = operation(doc, "", "delete")
            assertTrue(finish["security"]!!.jsonArray.isNotEmpty())
            assertEquals(setOf("decisionId", "challenge", "credential"), requestFields(doc, finish))
            assertEquals(setOf("receipt", "current"), responseFields(doc, finish))
        }

    @Test
    fun `connection errors document proof expiry conflicts and observation failures`() =
        withSpec { doc ->
            for ((path, method) in listOf("" to "get", "/step-up" to "post", "" to "delete")) {
                val responses = operation(doc, path, method)["responses"]!!.jsonObject
                for (code in listOf("401", "403", "429", "503")) assertNotNull(responses[code], "$method $path lacks $code")
            }
            val delete = operation(doc, "", "delete")["responses"]!!.jsonObject
            assertTrue(
                delete["409"]!!
                    .jsonObject["description"]!!
                    .jsonPrimitive.content
                    .contains("CONNECTION_CHANGED"),
            )
            assertTrue(
                delete["410"]!!
                    .jsonObject["description"]!!
                    .jsonPrimitive.content
                    .contains("CONNECTION_PROOF_EXPIRED"),
            )
        }

    private fun withSpec(assertion: (JsonObject) -> Unit) =
        testApplication {
            application {
                module()
                routing {
                    route("/api/v1") { atprotoConnectionRoutes(mockk(), mockk(), mockk(), mockk(), reconnect = mockk()) }
                }
            }
            assertion(Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject)
        }

    private fun operation(
        doc: JsonObject,
        suffix: String,
        method: String,
    ): JsonObject =
        assertNotNull(doc["paths"]!!.jsonObject["/api/v1/auth/atproto/connection$suffix"], "Missing connection operation")
            .jsonObject[method]!!
            .jsonObject

    private fun responseFields(
        doc: JsonObject,
        operation: JsonObject,
    ): Set<String> = fields(doc, operation["responses"]!!.jsonObject["200"]!!.jsonObject)

    private fun requestFields(
        doc: JsonObject,
        operation: JsonObject,
    ): Set<String> = fields(doc, operation["requestBody"]!!.jsonObject)

    private fun fields(
        doc: JsonObject,
        media: JsonObject,
    ): Set<String> {
        var schema = media["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject
        val ref = schema["\$ref"]?.jsonPrimitive?.content
        if (ref != null) schema = doc["components"]!!.jsonObject["schemas"]!!.jsonObject[ref.substringAfterLast('/')]!!.jsonObject
        return schema["properties"]!!.jsonObject.keys
    }
}
