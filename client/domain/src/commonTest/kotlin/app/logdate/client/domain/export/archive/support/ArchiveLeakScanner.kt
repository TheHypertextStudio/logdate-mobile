package app.logdate.client.domain.export.archive.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Finds device-internal references that must never reach an export: content URIs, absolute device
 * paths, drive letters, the app's package name and stack trace text.
 *
 * Text a person wrote is exempt, since it may contain a link or a path on purpose; only structural
 * values are checked.
 */
object ArchiveLeakScanner {
    private val userText =
        setOf("text", "caption", "title", "description", "bio", "originalBio", "transcription", "displayName", "placeName", "name")

    /** Identifiers that name a standard rather than a place: a JSON Schema dialect is not fetched. */
    private val standardIdentifiers = setOf("\$schema")

    private val exempt = userText + standardIdentifiers

    private val patterns =
        listOf(
            Regex("""^(content|file|ph|assets-library|https?)://"""),
            Regex("""^/(data|storage|var|Users|home|private|sdcard)/"""),
            Regex("""^[A-Za-z]:[\\/]"""),
            Regex("""app\.logdate\."""),
            Regex("""Exception"""),
        )

    /** Every structural string value in [json] that looks like a device reference. */
    fun findInJson(json: String): List<String> = findIn(Json.parseToJsonElement(json), key = null)

    /** Every line of [lines] scanned as its own JSON document. */
    fun findInJsonLines(lines: String): List<String> =
        lines
            .lineSequence()
            .filter { it.isNotBlank() }
            .flatMap { findInJson(it) }
            .toList()

    private fun findIn(
        element: JsonElement,
        key: String?,
    ): List<String> =
        when (element) {
            is JsonObject -> {
                element.entries.flatMap { (name, value) -> findIn(value, name) }
            }

            is JsonArray -> {
                element.flatMap { findIn(it, key) }
            }

            JsonNull -> {
                emptyList()
            }

            is JsonPrimitive -> {
                if (key in exempt ||
                    !element.isString
                ) {
                    emptyList()
                } else {
                    patterns.filter { it.containsMatchIn(element.content) }.map { element.content }
                }
            }
        }
}
