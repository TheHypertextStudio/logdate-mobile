package app.logdate.client.domain.export.archive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DIALECT = "https://json-schema.org/draft/2020-12/schema"
private const val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
private const val INSTANT_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z$"
private const val PATH_PATTERN = "^(?!/)(?!.*(^|/)\\.\\.?(/|$))[^\\\\:*?\"<>|\\u0000-\\u001f]+$"

internal fun document(
    title: String,
    required: List<String>,
    properties: Map<String, JsonElement>,
    defs: Map<String, JsonElement>,
): JsonObject =
    buildJsonObject {
        put("\$schema", DIALECT)
        put("title", title)
        put("type", "object")
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("properties", JsonObject(properties))
        if (defs.isNotEmpty()) put("\$defs", JsonObject(defs))
    }

internal fun defs(vararg names: String): Map<String, JsonElement> = names.associateWith(::shared)

private fun shared(name: String): JsonElement =
    when (name) {
        "uuid" -> {
            buildJsonObject {
                put("type", "string")
                put("pattern", UUID_PATTERN)
            }
        }

        "instant" -> {
            buildJsonObject {
                put("type", "string")
                put("pattern", INSTANT_PATTERN)
            }
        }

        "path" -> {
            buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", ArchivePath.MAX_PATH_LENGTH)
                put("pattern", PATH_PATTERN)
            }
        }

        "location" -> {
            obj(
                listOf("latitude", "longitude"),
                "latitude" to number(-90.0, 90.0),
                "longitude" to number(-180.0, 180.0),
                "altitudeMeters" to number(null, null),
                "accuracyMeters" to number(0.0, null),
                "placeName" to string(),
            )
        }

        "mediaRef" -> {
            mediaRef()
        }

        else -> {
            error("Unknown shared schema $name")
        }
    }

/** A media reference: an included file has a path and type, an omitted one has a reason. */
private fun mediaRef(): JsonElement =
    buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("status"))))
        put(
            "properties",
            JsonObject(
                mapOf(
                    "status" to enumOf(ArchiveMediaStatus.serializer()),
                    "path" to ref("path"),
                    "mediaType" to string(minLength = 1),
                    "omittedReason" to enumOf(ArchiveOmissionReason.serializer()),
                ),
            ),
        )
        put(
            "allOf",
            JsonArray(
                listOf(
                    conditional("included", listOf("path", "mediaType")),
                    conditional("omitted", listOf("omittedReason")),
                ),
            ),
        )
    }

private fun conditional(
    status: String,
    required: List<String>,
): JsonElement =
    buildJsonObject {
        put("if", buildJsonObject { put("properties", buildJsonObject { put("status", buildJsonObject { put("const", status) }) }) })
        put("then", buildJsonObject { put("required", JsonArray(required.map { JsonPrimitive(it) })) })
    }
