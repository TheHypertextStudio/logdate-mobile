package app.logdate.server.openapi

import app.logdate.server.module
import app.logdate.server.routes.blueskyAccountRoutes
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

/** Published enabled-route schemas must be usable by the native client, not just exist in source. */
class BlueskyOpenApiTest {
    @Test
    fun `enabled sign-in operations publish the native request and response shapes`() =
        withSpec { document ->
            val expected =
                mapOf(
                    "start" to (setOf("handle", "challenge") to setOf("attemptId", "authorizationUrl")),
                    "exchange" to (setOf("attemptId", "verifier") to setOf("continuationToken", "nextStep")),
                    "registration/begin" to
                        (
                            setOf(
                                "continuationToken",
                                "requestedOwnerId",
                                "username",
                                "displayName",
                            ) to setOf("continuationToken", "options")
                        ),
                    "registration/complete" to (setOf("continuationToken", "credential") to setOf("success", "data")),
                    "authentication/begin" to (setOf("continuationToken") to setOf("continuationToken", "options")),
                    "authentication/complete" to (setOf("continuationToken", "credential") to setOf("success", "data")),
                )
            expected.forEach { (path, fields) ->
                val operation = operation(document, path)
                assertTrue(
                    operation["security"]?.jsonArray.orEmpty().isEmpty(),
                    "$path uses app-bound proof, not an existing bearer session",
                )
                val request = assertNotNull(operation["requestBody"]?.jsonObject, "$path request is undocumented")
                val requestProperties = properties(document, jsonMedia(request)["schema"]!!.jsonObject)
                assertTrue(requestProperties.keys.containsAll(fields.first), "$path omits required native request fields")
                val success = operation["responses"]!!.jsonObject["200"]!!.jsonObject
                val responseProperties = properties(document, jsonMedia(success)["schema"]!!.jsonObject)
                assertEquals(fields.second, responseProperties.keys, "$path changes the flat versus session-envelope contract")
            }
        }

    @Test
    fun `enabled sign-in documentation exposes bounded errors and retry timing`() =
        withSpec { document ->
            PATHS.forEach { path ->
                val responses = operation(document, path)["responses"]!!.jsonObject
                val invalid = assertNotNull(responses["400"]?.jsonObject, "$path malformed-request response is undocumented")
                assertEquals(setOf("error"), properties(document, jsonMedia(invalid)["schema"]!!.jsonObject).keys)
                val limited = assertNotNull(responses["429"]?.jsonObject, "$path rate limit is undocumented")
                assertNotNull(limited["headers"]?.jsonObject?.get("Retry-After"), "$path omits the retry deadline")
                assertNotNull(responses["503"], "$path availability failure is undocumented")
            }
            listOf("registration/complete", "authentication/complete").forEach { path ->
                val unauthorized = operation(document, path)["responses"]!!.jsonObject["401"]!!.jsonObject
                val explanation = unauthorized["description"]!!.jsonPrimitive.content
                assertTrue("BLUESKY_HANDOFF_EXPIRED" in explanation && "PASSKEY_VERIFICATION_FAILED" in explanation)
            }
        }

    private fun withSpec(assertions: (JsonObject) -> Unit) =
        testApplication {
            application {
                module()
                routing {
                    route("/api/v1") {
                        // Only external business services are doubled. Real routes and the production
                        // schema/example generator produce the document; no provider call is needed.
                        blueskyAccountRoutes(mockk(), mockk(), mockk()) { error("Schema generation must not issue a session") }
                    }
                }
            }
            assertions(Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject)
        }

    private fun operation(
        document: JsonObject,
        path: String,
    ): JsonObject =
        assertNotNull(document["paths"]?.jsonObject?.get("/api/v1/auth/bluesky/$path"), "Missing published Bluesky $path")
            .jsonObject["post"]!!
            .jsonObject

    private fun jsonMedia(body: JsonObject): JsonObject = body["content"]!!.jsonObject["application/json"]!!.jsonObject

    private fun properties(
        document: JsonObject,
        schema: JsonObject,
    ): JsonObject {
        val reference = schema["\$ref"]?.jsonPrimitive?.content
        val resolved =
            if (reference == null) {
                schema
            } else {
                document["components"]!!.jsonObject["schemas"]!!.jsonObject[reference.substringAfterLast('/')]!!.jsonObject
            }
        return assertNotNull(resolved["properties"]?.jsonObject, "Schema has no object properties: $resolved")
    }

    private companion object {
        val PATHS =
            listOf("start", "exchange", "registration/begin", "registration/complete", "authentication/begin", "authentication/complete")
    }
}
