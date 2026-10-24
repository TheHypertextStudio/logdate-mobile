package app.logdate.client.domain.restore

import app.logdate.client.domain.export.archive.ArchiveJson
import kotlinx.serialization.decodeFromString

/** Retains only object keys; normal serializers validate typed values after this ambiguity gate. */
internal object ArchiveJsonKeyValidation {
    fun requireUnique(text: String) {
        val scanner = Scanner(text)
        scanner.value(0)
        scanner.whitespace()
        require(scanner.atEnd) { "Trailing archive JSON data" }
    }

    private class Scanner(
        private val text: String,
    ) {
        private var index = 0
        val atEnd get() = index == text.length

        fun whitespace() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        fun value(depth: Int) {
            require(depth < 128) { "Archive JSON nesting is too deep" }
            whitespace()
            require(index < text.length) { "Missing archive JSON value" }
            when (text[index]) {
                '{' -> {
                    objectValue(depth)
                }

                '[' -> {
                    arrayValue(depth)
                }

                '"' -> {
                    string(false)
                }

                else -> {
                    val start = index
                    while (index < text.length && text[index] !in " \t\r\n,]}") index++
                    require(index > start) { "Missing archive JSON value" }
                }
            }
        }

        private fun objectValue(depth: Int) {
            index++
            whitespace()
            if (takeIf('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                val key = requireNotNull(string(true))
                require(keys.add(key)) { "Repeated archive JSON object key" }
                take(':')
                value(depth + 1)
                whitespace()
                if (takeIf('}')) return
                take(',')
            }
        }

        private fun arrayValue(depth: Int) {
            index++
            whitespace()
            if (takeIf(']')) return
            while (true) {
                value(depth + 1)
                whitespace()
                if (takeIf(']')) return
                take(',')
            }
        }

        private fun string(decode: Boolean): String? {
            whitespace()
            val start = index
            take('"')
            while (index < text.length) {
                when (val character = text[index]) {
                    '"' -> {
                        index++
                        return if (decode) ArchiveJson.document.decodeFromString<String>(text.substring(start, index)) else null
                    }

                    '\\' -> {
                        index++
                        require(index < text.length) { "Truncated archive JSON escape" }
                        if (text[index] == 'u') {
                            require(text.length - index >= 5 && (index + 1..index + 4).all { text[it].digitToIntOrNull(16) != null }) {
                                "Invalid archive JSON Unicode escape"
                            }
                            index += 5
                        } else {
                            require(text[index] in "\"\\/bfnrt") { "Invalid archive JSON escape" }
                            index++
                        }
                    }

                    else -> {
                        require(character.code >= 0x20) { "Unescaped archive JSON control character" }
                        index++
                    }
                }
            }
            throw IllegalArgumentException("Unterminated archive JSON string")
        }

        private fun take(character: Char) {
            whitespace()
            require(takeIf(character)) { "Invalid archive JSON punctuation" }
        }

        private fun takeIf(character: Char): Boolean {
            if (index < text.length && text[index] == character) {
                index++
                return true
            }
            return false
        }
    }
}
