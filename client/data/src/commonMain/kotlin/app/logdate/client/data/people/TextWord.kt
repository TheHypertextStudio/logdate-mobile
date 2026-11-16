package app.logdate.client.data.people

/**
 * One word of entry text and where it sits in that text.
 *
 * @property startsSentence True when no other word comes between this word and the start of the
 * text, a line break, or sentence-ending punctuation.
 * @property continuesPreviousWord True when only spaces separate this word from the word before it,
 * so the two can belong to one name.
 * @property isNameLike True when the word is written the way a name is: a capital letter followed
 * by lowercase letters, optionally in parts joined by a hyphen or an apostrophe. A part may also
 * carry a second capital after a name prefix, as in "McKenzie" or "DeShawn".
 */
internal data class TextWord(
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val startsSentence: Boolean,
    val continuesPreviousWord: Boolean,
    val isNameLike: Boolean,
)

/**
 * Splits [text] into words. A possessive ending ("Sarah's") is left out of the word, and the word
 * after it never continues it.
 */
internal fun tokenizeWords(text: String): List<TextWord> {
    val words = mutableListOf<TextWord>()
    var index = 0
    var atSentenceStart = true
    var onlySpacesSinceWord = false
    while (index < text.length) {
        val char = text[index]
        if (!char.isLetterOrDigit()) {
            if (endsSentence(text, index, words.lastOrNull())) atSentenceStart = true
            if (char != ' ' && char != '\t') onlySpacesSinceWord = false
            index += 1
            continue
        }
        val rawEnd = wordEnd(text, index)
        val end = endWithoutPossessive(text, index, rawEnd)
        val word = text.substring(index, end)
        words +=
            TextWord(
                text = word,
                start = index,
                endExclusive = end,
                startsSentence = atSentenceStart,
                continuesPreviousWord = onlySpacesSinceWord,
                isNameLike = isNameLike(word),
            )
        atSentenceStart = false
        onlySpacesSinceWord = end == rawEnd
        index = rawEnd
    }
    return words
}

private fun wordEnd(
    text: String,
    start: Int,
): Int {
    var index = start
    while (index < text.length) {
        val char = text[index]
        val joinsNextPart = char.joinsNameParts() && index + 1 < text.length && text[index + 1].isLetterOrDigit()
        if (!char.isLetterOrDigit() && !joinsNextPart) break
        index += 1
    }
    return index
}

private fun endWithoutPossessive(
    text: String,
    start: Int,
    end: Int,
): Int {
    if (end - start < MIN_POSSESSIVE_LENGTH) return end
    val endsWithPossessive = text[end - 2].isApostrophe() && text[end - 1].lowercaseChar() == 's'
    return if (endsWithPossessive) end - 2 else end
}

private fun endsSentence(
    text: String,
    index: Int,
    previousWord: TextWord?,
): Boolean {
    val char = text[index]
    if (char in sentenceEndingCharacters) return true
    if (char != '.') return false
    if (previousWord == null || previousWord.endExclusive != index) return true
    return !previousWord.text.isAbbreviationBeforePeriod()
}

private fun String.isAbbreviationBeforePeriod(): Boolean {
    if (length == 1) return first().isUpperCase()
    return lowercase() in titlesBeforeNames
}

private fun isNameLike(word: String): Boolean {
    val parts = word.split(*nameJoiners)
    return parts.all(::isNamePart) && parts.any { it.length > 1 }
}

private fun isNamePart(part: String): Boolean {
    if (part.isEmpty()) return false
    if (part.isCapitalized()) return true
    val prefix = prefixesBeforeSecondCapital.firstOrNull { part.startsWith(it) } ?: return false
    val afterPrefix = part.substring(prefix.length)
    return afterPrefix.length > 1 && afterPrefix.isCapitalized()
}

private fun String.isCapitalized(): Boolean = first().isUpperCase() && drop(1).all(Char::isLowerCase)

private fun Char.joinsNameParts(): Boolean = this in nameJoiners

private fun Char.isApostrophe(): Boolean = this == '\'' || this == '’'

private val nameJoiners = charArrayOf('-', '\'', '’')

private val prefixesBeforeSecondCapital = listOf("Mac", "Mc", "De", "Le", "La", "Di", "Du", "Van", "Fitz")

private const val MIN_POSSESSIVE_LENGTH = 3

private val sentenceEndingCharacters = setOf('\n', '\r', '!', '?', '…')
