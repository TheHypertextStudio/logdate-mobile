package app.logdate.server.openapi

import app.logdate.server.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every schema an operation can send or receive must describe itself and each of its fields,
 * and the registry must not describe things that no longer exist.
 */
class SchemaDocumentationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun withSpec(block: (JsonObject) -> Unit) =
        testApplication {
            application { module() }
            block(json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject)
        }

    @Test
    fun `schemas reachable from documented operations are fully described`() =
        withSpec { document ->
            val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
            val reachable = reachableSchemas(document, schemas)
            val gaps = mutableListOf<String>()
            reachable.forEach { name ->
                val schema = schemas[name]!!.jsonObject
                if (schema["description"]?.jsonPrimitive?.content.isNullOrBlank()) gaps += name
                schema["properties"]?.jsonObject?.forEach { (property, propertySchema) ->
                    if (propertySchema.jsonObject["description"]
                            ?.jsonPrimitive
                            ?.content
                            .isNullOrBlank()
                    ) {
                        gaps += "$name.$property"
                    }
                }
                if (schema.containsKey("enum") && !schema.containsKey("x-enumDescriptions")) gaps += "$name (enum values)"
            }
            assertTrue(gaps.isEmpty(), "undocumented schemas or fields:\n" + gaps.joinToString("\n"))
        }

    @Test
    fun `registry has no stale entries`() =
        withSpec { document ->
            val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
            val stale = mutableListOf<String>()
            SchemaDocumentation.registry.forEach { (name, doc) ->
                val schema = schemas[name]?.jsonObject
                if (schema == null) {
                    stale += name
                    return@forEach
                }
                val properties = schema["properties"]?.jsonObject?.keys.orEmpty()
                doc.properties.keys
                    .filter { it !in properties }
                    .forEach { stale += "$name.$it" }
                val enumValues = (schema["enum"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                doc.enumValues.keys
                    .filter { it !in enumValues }
                    .forEach { stale += "$name.$it" }
            }
            assertTrue(stale.isEmpty(), "registry entries with no matching schema:\n" + stale.joinToString("\n"))
        }

    /** Every schema referenced, directly or through other schemas, by any published operation. */
    private fun reachableSchemas(
        document: JsonObject,
        schemas: JsonObject,
    ): Set<String> {
        val roots = mutableSetOf<String>()
        document["paths"]!!.jsonObject.values.forEach { item ->
            item.jsonObject.values.forEach { op ->
                val operation = op as? JsonObject ?: return@forEach
                collectRefs(operation, roots)
            }
        }
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            schemas[name]?.let { schema ->
                val nested = mutableSetOf<String>()
                collectRefs(schema, nested)
                queue += nested
            }
        }
        return seen
    }

    private fun collectRefs(
        element: JsonElement,
        into: MutableSet<String>,
    ) {
        when (element) {
            is JsonObject -> {
                (element["\$ref"] as? JsonPrimitive)?.content?.removePrefix("#/components/schemas/")?.let(into::add)
                element.values.forEach { collectRefs(it, into) }
            }

            is JsonArray -> {
                element.forEach { collectRefs(it, into) }
            }

            else -> {
                Unit
            }
        }
    }
}
