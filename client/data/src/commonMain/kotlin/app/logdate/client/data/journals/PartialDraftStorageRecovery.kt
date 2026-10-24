package app.logdate.client.data.journals

import app.logdate.shared.model.EditorDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Salvages complete records only; the caller retains the entire damaged value independently. */
internal object PartialDraftStorageRecovery {
    fun recover(
        json: Json,
        value: String,
    ): ArchiveDraftStorageState {
        val drafts =
            array(value, "drafts")
                .mapNotNull { fragment ->
                    runCatching {
                        val record = json.parseToJsonElement(fragment) as? JsonObject ?: return@runCatching null
                        if (record["blocks"] !is JsonArray || record["id"] == null) return@runCatching null
                        json.decodeFromString<EditorDraft>(fragment)
                    }.getOrNull()
                }.distinctBy { it.id }
        val receipts = array(value, "appliedRecoveries").mapNotNull { runCatching { json.decodeFromString<String>(it) }.getOrNull() }
        val deleted = array(value, "localDeletionIds").mapNotNull { runCatching { json.decodeFromString<String>(it) }.getOrNull() }
        return ArchiveDraftStorageState(drafts, receipts.toSet(), deleted.toSet())
    }

    private fun array(
        value: String,
        field: String,
    ): List<String> {
        val start = arrayStart(value, field) ?: return emptyList()
        val values = mutableListOf<String>()
        var begin = start + 1
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in begin until value.length) {
            val character = value[index]
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    quoted = false
                }
                continue
            }
            when (character) {
                '"' -> {
                    quoted = true
                }

                '{', '[' -> {
                    depth++
                }

                '}' -> {
                    depth--
                }

                ']' -> {
                    if (depth == 0) {
                        appendFragment(value, begin, index, values)
                        return values
                    } else {
                        depth--
                    }
                }

                ',' -> {
                    if (depth == 0) {
                        appendFragment(value, begin, index, values)
                        begin = index + 1
                    }
                }
            }
        }
        if (depth == 0 && !quoted) {
            appendFragment(value, begin, value.length, values)
        }
        return values
    }

    private fun appendFragment(
        value: String,
        begin: Int,
        end: Int,
        values: MutableList<String>,
    ) {
        value
            .substring(begin, end)
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(values::add)
    }

    private fun arrayStart(
        value: String,
        field: String,
    ): Int? {
        val first = value.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return null
        if (value[first] == '[') return first.takeIf { field == "drafts" }
        var depth = 0
        var index = first
        while (index < value.length) {
            when (value[index]) {
                '{', '[' -> {
                    depth++
                }

                '}', ']' -> {
                    depth--
                }

                '"' -> {
                    val begin = index++
                    var escaped = false
                    while (index < value.length) {
                        val character = value[index]
                        if (!escaped && character == '"') break
                        escaped = !escaped && character == '\\'
                        index++
                    }
                    if (depth == 1 && value.substring(begin, (index + 1).coerceAtMost(value.length)) == "\"$field\"") {
                        var next = index + 1
                        while (next < value.length && value[next].isWhitespace()) next++
                        if (value.getOrNull(next++) != ':') {
                            index++
                            continue
                        }
                        while (next < value.length && value[next].isWhitespace()) next++
                        if (value.getOrNull(next) == '[') return next
                    }
                }
            }
            index++
        }
        return null
    }
}
