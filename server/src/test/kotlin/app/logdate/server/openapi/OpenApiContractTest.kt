package app.logdate.server.openapi

import app.logdate.server.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The gates every published operation has to pass before it reaches a reader. */
class OpenApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class Operation(
        val method: String,
        val path: String,
        val body: JsonObject,
    ) {
        val label get() = "$method $path"
        val operationId get() = body["operationId"]?.jsonPrimitive?.content
        val responses get() = body["responses"]?.jsonObject.orEmpty()
        val tags get() = body["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val hasSecurity get() = body["security"]?.jsonArray?.isNotEmpty() == true
    }

    private fun withSpec(block: (JsonObject, List<Operation>) -> Unit) =
        testApplication {
            application { module() }
            val document = json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
            val operations =
                assertNotNull(document["paths"]?.jsonObject).flatMap { (path, item) ->
                    item.jsonObject.filterKeys { it in HTTP_METHODS }.map { (method, op) ->
                        Operation(method.uppercase(), path, op.jsonObject)
                    }
                }
            block(document, operations)
        }

    @Test
    fun `every documented operation is hand written`() =
        withSpec { _, operations ->
            val ids = mutableSetOf<String>()
            operations.forEach { op ->
                val summary =
                    op.body["summary"]
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                val description =
                    op.body["description"]
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                assertTrue(summary.isNotBlank() && summary.length <= 60, "${op.label}: summary must be a short title, got '$summary'")
                assertTrue(description.length >= 40, "${op.label}: description too short to help anyone: '$description'")
                assertTrue(!MACHINE_TEXT.matches(summary), "${op.label}: summary looks machine generated: '$summary'")
                assertTrue(!description.contains("this endpoint", ignoreCase = true), "${op.label}: say what it does, not 'this endpoint'")
                assertEquals(1, op.tags.size, "${op.label}: exactly one tag, got ${op.tags}")
                val id = assertNotNull(op.operationId, "${op.label}: operationId")
                assertTrue(OPERATION_ID.matches(id), "${op.label}: operationId must be camelCase, got '$id'")
                assertTrue(ids.add(id), "duplicate operationId $id")
            }
        }

    @Test
    fun `every tag is declared with a description and grouped exactly once`() =
        withSpec { document, operations ->
            val declared =
                assertNotNull(document["tags"]?.jsonArray).associate { tag ->
                    val name =
                        tag.jsonObject["name"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    name to
                        tag.jsonObject["description"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                }
            declared.forEach { (name, description) -> assertTrue(description.length >= 80, "tag '$name' needs a real description") }
            val groups = assertNotNull(document["x-tagGroups"]?.jsonArray, "x-tagGroups missing")
            val grouped = groups.flatMap { it.jsonObject["tags"]!!.jsonArray.map { t -> t.jsonPrimitive.content } }
            assertEquals(grouped.size, grouped.toSet().size, "a tag appears in more than one group: $grouped")
            assertEquals(declared.keys, grouped.toSet(), "declared tags and grouped tags differ")
            operations.forEach { op ->
                op.tags.forEach { assertTrue(it in declared, "${op.label}: tag '$it' is not declared in ApiTags") }
            }
            val used = operations.flatMap { it.tags }.toSet()
            assertEquals(declared.keys, used, "every declared tag must be used by at least one operation")
        }

    @Test
    fun `path templates and path parameters agree`() =
        withSpec { _, operations ->
            operations.forEach { op ->
                val templated = PATH_PARAM.findAll(op.path).map { it.groupValues[1] }.toSet()
                val declared =
                    op.body["parameters"]?.jsonArray.orEmpty().map { it.jsonObject }.filter {
                        it["in"]?.jsonPrimitive?.content == "path"
                    }
                assertEquals(templated, declared.map { it["name"]!!.jsonPrimitive.content }.toSet(), "${op.label}: path parameters")
                declared.forEach { parameter ->
                    assertTrue(parameter["required"]?.jsonPrimitive?.content == "true", "${op.label}: path parameter must be required")
                    assertTrue(
                        parameter["description"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                            .length >= 20,
                        "${op.label}: describe ${parameter["name"]}",
                    )
                }
            }
        }

    @Test
    fun `every documented operation declares a success response with content`() =
        withSpec { _, operations ->
            operations.forEach { op ->
                val successes = op.responses.filterKeys { it.startsWith("2") || it.startsWith("3") }
                assertTrue(successes.isNotEmpty(), "${op.label}: no 2xx or 3xx response")
                successes.forEach { (status, response) ->
                    val body = response.jsonObject
                    assertTrue(
                        body["description"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                            .isNotBlank(),
                        "${op.label}: $status needs a description",
                    )
                    if (status.startsWith("3")) {
                        assertTrue(
                            body["headers"]?.jsonObject?.containsKey("Location") == true,
                            "${op.label}: $status must document Location",
                        )
                    } else if (status != "204" && op.operationId !in SUCCESS_WITHOUT_BODY) {
                        assertTrue(body.containsKey("content"), "${op.label}: $status must declare a body")
                    }
                    if (status == "201" && op.operationId !in CREATED_WITHOUT_LOCATION) {
                        assertTrue(
                            body["headers"]?.jsonObject?.containsKey("Location") == true,
                            "${op.label}: 201 must document Location",
                        )
                    }
                }
            }
        }

    @Test
    fun `every json body carries an example`() =
        withSpec { _, operations ->
            operations.forEach { op ->
                op.body["requestBody"]?.jsonObject?.get("content")?.jsonObject?.forEach { (mediaType, media) ->
                    if (mediaType ==
                        "application/json"
                    ) {
                        assertTrue(media.jsonObject.hasExample(), "${op.label}: request body needs an example")
                    }
                }
                op.responses.filterKeys { it.startsWith("2") }.forEach { (status, response) ->
                    response.jsonObject["content"]?.jsonObject?.get("application/json")?.jsonObject?.let { media ->
                        assertTrue(media.hasExample(), "${op.label}: $status response needs an example")
                    }
                }
            }
        }

    @Test
    fun `protected operations document 401 in their family's envelope`() =
        withSpec { _, operations ->
            operations.forEach { op ->
                if (op.hasSecurity) {
                    val unauthorized = assertNotNull(op.responses["401"], "${op.label}: protected but no 401 documented").jsonObject
                    val ref =
                        unauthorized["content"]
                            ?.jsonObject
                            ?.get("application/json")
                            ?.jsonObject
                            ?.get("schema")
                            ?.jsonObject
                            ?.get("\$ref")
                            ?.jsonPrimitive
                            ?.content
                    assertEquals("#/components/schemas/${envelopeFor(op.path)}", ref, "${op.label}: 401 envelope")
                } else if (!op.hasSecurity) {
                    assertTrue(op.operationId in PUBLIC_OPERATIONS, "${op.label}: no security declared but not listed as public")
                }
            }
        }

    @Test
    fun `rate limited operations document 429 and quota enforced operations document 402`() =
        withSpec { _, operations ->
            operations.forEach { op ->
                if (op.operationId in RATE_LIMITED_WITH_RETRY_AFTER + RATE_LIMITED_WITHOUT_RETRY_AFTER) {
                    val limited = assertNotNull(op.responses["429"], "${op.label}: rate limited but no 429").jsonObject
                    val hasRetryAfter = limited["headers"]?.jsonObject?.containsKey("Retry-After") == true
                    assertEquals(op.operationId in RATE_LIMITED_WITH_RETRY_AFTER, hasRetryAfter, "${op.label}: Retry-After header")
                }
                if (op.operationId in QUOTA_ENFORCED) assertNotNull(op.responses["402"], "${op.label}: quota enforced but no 402")
                if (op.path.startsWith("/xrpc/") && op.operationId !in XRPC_ALWAYS_AVAILABLE) {
                    assertNotNull(op.responses["501"], "${op.label}: XRPC methods answer 501 when disabled")
                }
            }
        }

    @Test
    fun `no placeholder or dangling schema reference reaches the document`() =
        withSpec { document, _ ->
            assertTrue("{{" !in document.toString(), "a {{placeholder}} was published unrendered")
            val schemas =
                document["components"]
                    ?.jsonObject
                    ?.get("schemas")
                    ?.jsonObject
                    ?.keys
                    .orEmpty()
            val refs = mutableSetOf<String>()

            fun collect(element: JsonElement) {
                when (element) {
                    is JsonObject -> {
                        element["\$ref"]?.jsonPrimitive?.content?.let(refs::add)
                        element.values.forEach(::collect)
                    }
                    is JsonArray -> element.forEach(::collect)
                    else -> Unit
                }
            }
            collect(document)
            val dangling = refs.filterNot { it.startsWith(SCHEMA_REF_PREFIX) && it.removePrefix(SCHEMA_REF_PREFIX) in schemas }
            assertTrue(dangling.isEmpty(), "references that resolve to nothing: $dangling")
        }

    @Test
    fun `info servers and external docs are complete`() =
        withSpec { document, _ ->
            val info = assertNotNull(document["info"]?.jsonObject)
            assertEquals(API_TITLE, info["title"]?.jsonPrimitive?.content)
            assertEquals(OpenApiOverview.markdown, info["description"]?.jsonPrimitive?.content)
            assertEquals(
                PRODUCT_URL,
                info["contact"]
                    ?.jsonObject
                    ?.get("url")
                    ?.jsonPrimitive
                    ?.content,
            )
            val servers = assertNotNull(document["servers"]?.jsonArray).map { it.jsonObject["url"]!!.jsonPrimitive.content }
            assertEquals(listOf("/", HOSTED_SERVER_URL), servers)
            assertNotNull(document["externalDocs"]?.jsonObject?.get("url"))
        }

    @Test
    fun `operational routes are not published`() =
        withSpec { document, _ ->
            val paths = assertNotNull(document["paths"]?.jsonObject)
            HIDDEN_PATHS.forEach { assertTrue(!paths.containsKey(it), "$it must not be published") }
        }

    @Test
    fun `route files carry no inline documentation`() {
        val routesDir =
            listOf(File("src/main/kotlin/app/logdate/server/routes"), File("server/src/main/kotlin/app/logdate/server/routes"))
                .first { it.isDirectory }
        routesDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = file.relativeTo(routesDir).path
            if (relative.startsWith("docs/") || relative == "OpenApiDocumentation.kt") return@forEach
            val offending = file.readLines().withIndex().filter { (_, line) -> INLINE_DOC_LINE.containsMatchIn(line) }
            assertTrue(offending.isEmpty(), "$relative documents routes inline; move it to routes/docs: ${offending.map { it.index + 1 }}")
        }
    }

    private fun JsonObject.hasExample() = containsKey("example") || (this["examples"]?.jsonObject?.isNotEmpty() == true)

    private fun envelopeFor(path: String): String =
        when {
            path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/identity") -> "ApiErrorResponse"
            path.startsWith("/api/v1/quota") || path.startsWith("/api/v1/transcription") -> "MessageErrorResponse"
            path.startsWith("/xrpc/") -> "PdsErrorResponse"
            path.startsWith("/oauth/") -> "OAuthErrorResponse"
            else -> "SimpleErrorResponse"
        }

    companion object {
        private const val SCHEMA_REF_PREFIX = "#/components/schemas/"
        private val HTTP_METHODS = setOf("get", "head", "post", "put", "patch", "delete")
        private val MACHINE_TEXT = Regex("^(Get|Post|Put|Patch|Delete) [A-Za-z.]+\\.?$")
        private val OPERATION_ID = Regex("^[a-z][A-Za-z0-9]*$")
        private val PATH_PARAM = Regex("\\{([^}]+)}")
        private val INLINE_DOC_LINE = Regex("^\\s*(summary|description|operationId|tags)\\s*=\\s*(\"|listOf\\(|$)")
        private val HIDDEN_PATHS =
            setOf(
                "/health",
                "/docs",
                "/favicon.svg",
                "/api/v1/auth/metrics",
                "/api/v1/auth/metrics/prometheus",
                "/api/v1/ops/sync/metrics",
                "/api/v1/ops/sync/metrics/prometheus",
                "/api/v1/ops/sync/tombstones:purge",
                "/api/v1/ops/backups:purge",
            )

        /** Operations that are public by design; every other documented operation must declare security. */
        private val PUBLIC_OPERATIONS =
            setOf(
                "checkUsernameAvailability",
                "beginPasskeySignup",
                "completePasskeySignup",
                "signupWithGoogle",
                "beginPasskeySignin",
                "completePasskeySignin",
                "signinWithGoogle",
                "startBlueskySignin",
                "exchangeBlueskyIdentityProof",
                "beginBlueskyRegistration",
                "completeBlueskyRegistration",
                "beginBlueskyAuthentication",
                "completeBlueskyAuthentication",
                "beginRestoreSignin",
                "completeRestoreSignin",
                "refreshAccessToken",
                "logout",
                "resolveAtprotoDid",
                "getDidDocument",
                "getServerInfo",
                "listPlans",
                "getAssetLinks",
                "pushAuthorizationRequest",
                "exchangeOAuthToken",
                "revokeOAuthToken",
                "getAuthorizationPasskeyOptions",
                "approveAuthorizationWithPasskey",
                "resolveResource",
                "getOAuthAuthorizationServerMetadata",
                "getOAuthProtectedResourceMetadata",
                "getOAuthJwks",
                "resolveHandle",
                "describeAtprotoServer",
                "describeRepo",
                "createAtprotoAccount",
                "createAtprotoSession",
                "claimDeviceEnrollment",
                "redeemDeviceLink",
            )

        /** XRPC methods that work without any optional service and therefore never answer 501. */
        private val XRPC_ALWAYS_AVAILABLE = setOf("resolveHandle", "describeAtprotoServer", "describeRepo")

        /** Operations whose success status is the whole answer, so they reply with an empty body. */
        private val SUCCESS_WITHOUT_BODY = setOf("revokeOAuthToken", "putAccountKeyEnvelope", "headContent")

        /** Operations that answer 201 without a Location header because the created thing has no URL of its own. */
        private val CREATED_WITHOUT_LOCATION =
            setOf("completePasskeySignup", "completeAddPasskey", "pushAuthorizationRequest", "createTranscriptionSession")

        private val RATE_LIMITED_WITHOUT_RETRY_AFTER =
            setOf(
                "beginPasskeySignup",
                "completePasskeySignup",
                "signupWithGoogle",
                "beginPasskeySignin",
                "completePasskeySignin",
                "signinWithGoogle",
                "createDeviceLink",
                "redeemDeviceLink",
            )
        private val RATE_LIMITED_WITH_RETRY_AFTER =
            setOf(
                "uploadMedia",
                "uploadBackup",
                "createTranscriptionSession",
                "getAuthorizationPasskeyOptions",
                "approveAuthorizationWithPasskey",
                "startBlueskySignin",
                "exchangeBlueskyIdentityProof",
                "beginBlueskyRegistration",
                "completeBlueskyRegistration",
                "beginBlueskyAuthentication",
                "completeBlueskyAuthentication",
            )
        private val QUOTA_ENFORCED = setOf("uploadMedia", "uploadBackup", "createTranscriptionSession")
    }
}
