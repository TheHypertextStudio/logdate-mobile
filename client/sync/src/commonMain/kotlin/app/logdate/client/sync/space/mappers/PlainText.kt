package app.logdate.client.sync.space.mappers

/**
 * Plain [text] as Markdown that shows exactly the characters it was written with, or null when it
 * can't be done without changing what shows.
 *
 * - Characters Markdown acts on anywhere in a line are escaped with a backslash: `\`, `` ` ``,
 *   `*`, `_`, `[`, `<`, `~`, `|`, and `&` where it starts something that reads as an entity.
 * - A line's first characters are escaped when they would start a heading, quote, list, ruled line
 *   or setext underline: `#`, `>`, `-`, `+`, `=`, and the dot or bracket after a list number.
 * - Each line break inside a paragraph becomes a hard break, because Markdown would otherwise
 *   join the lines.
 * - Line endings are read as `\n`, and a line with only whitespace is an empty one.
 *
 * Markdown drops whitespace at the start of a line, and there is no escape for it, so text with a
 * line that starts with a space or a tab returns null.
 */
internal fun plainTextAsMarkdown(text: String): String? {
    val lines =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { if (it.isBlank()) "" else it }
    if (lines.any { it.isNotEmpty() && (it.first() == ' ' || it.first() == '\t') }) return null
    return lines
        .mapIndexed { index, line ->
            val escaped = escapeLine(line)
            val joinsNext = line.isNotEmpty() && lines.getOrNull(index + 1)?.isNotEmpty() == true
            if (joinsNext) "$escaped\\" else escaped
        }.joinToString("\n")
}

private val orderedListMarker = Regex("^(\\d{1,9})([.)])(?=[ \\t]|$)")
private val ruleOrUnderline = Regex("^[-=]+[ \\t]*$")
private val bulletMarker = Regex("^[-+](?=[ \\t]|$)")
private val entityStart = Regex("^&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
private const val ALWAYS_ESCAPED = "\\`*_[<~|"

private fun escapeLine(line: String): String {
    val body =
        buildString {
            for ((index, char) in line.withIndex()) {
                when {
                    char in ALWAYS_ESCAPED -> append('\\').append(char)
                    char == '&' && entityStart.containsMatchIn(line.substring(index)) -> append("\\&")
                    else -> append(char)
                }
            }
        }
    return escapeLineStart(line, body)
}

// The start of [line] is the same in [body] except for the characters escaped above, which are
// never the ones a line-start construct begins with, apart from `*`, `_` and `~`, already escaped.
private fun escapeLineStart(
    line: String,
    body: String,
): String {
    val first = line.firstOrNull() ?: return body
    return when {
        first == '#' || first == '>' -> {
            "\\$body"
        }

        ruleOrUnderline.matches(line) || bulletMarker.containsMatchIn(line) -> {
            "\\$body"
        }

        else -> {
            val ordered = orderedListMarker.find(line) ?: return body
            val delimiterAt = ordered.groupValues[1].length
            body.substring(0, delimiterAt) + "\\" + body.substring(delimiterAt)
        }
    }
}
