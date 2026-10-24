package app.logdate.client.intelligence.structured

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

sealed interface StructuredOutputResult<out T> {
    data class Success<T>(
        val value: T,
    ) : StructuredOutputResult<T>

    data object Empty : StructuredOutputResult<Nothing>

    data object Invalid : StructuredOutputResult<Nothing>
}

interface StructuredOutputParser<T> {
    fun parse(raw: String): StructuredOutputResult<T>
}

class JsonStructuredOutputParser<T>(
    private val json: Json,
    private val serializer: KSerializer<T>,
    private val allowEmbeddedJson: Boolean = false,
) : StructuredOutputParser<T> {
    override fun parse(raw: String): StructuredOutputResult<T> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return StructuredOutputResult.Empty
        }
        decode(trimmed)?.let { return StructuredOutputResult.Success(it) }
        if (allowEmbeddedJson) {
            val embedded = extractJsonObject(trimmed) ?: return StructuredOutputResult.Invalid
            decode(embedded)?.let { return StructuredOutputResult.Success(it) }
        }
        return StructuredOutputResult.Invalid
    }

    private fun decode(payload: String): T? = runCatching { json.decodeFromString(serializer, payload) }.getOrNull()
}

private fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    if (start == -1) return null
    var depth = 0
    var inString = false
    var escape = false
    for (index in start until raw.length) {
        val char = raw[index]
        if (escape) {
            escape = false
            continue
        }
        when (char) {
            '\\' -> {
                if (inString) escape = true
            }

            '"' -> {
                inString = !inString
            }

            '{' -> {
                if (!inString) depth += 1
            }

            '}' -> {
                if (!inString) {
                    depth -= 1
                    if (depth == 0) {
                        return raw.substring(start, index + 1)
                    }
                }
            }
        }
    }
    return null
}
