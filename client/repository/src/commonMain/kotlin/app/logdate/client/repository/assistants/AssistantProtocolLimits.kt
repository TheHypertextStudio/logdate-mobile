package app.logdate.client.repository.assistants

object AssistantProtocolLimits {
    const val SEARCH_SOURCES = 10
    const val QUERY_CODE_POINTS = 256
    const val EXCERPT_CODE_POINTS = 280
    const val SOURCE_CODE_POINTS = 4000
    const val CURSOR_LENGTH = 512
    const val TITLE_CODE_POINTS = 280
    const val URL_CODE_POINTS = 2048

    fun opaqueIdFits(
        value: String,
        maximum: Int,
    ): Boolean = value.length in 1..maximum && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    /** Counts supplementary characters once and refuses malformed UTF-16. Empty text fits. */
    fun textFits(
        value: String,
        maximum: Int,
    ): Boolean {
        var index = 0
        var count = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index >= value.length || !value[index].isLowSurrogate()) return false
                index++
            } else if (char.isLowSurrogate()) {
                return false
            }
            if (++count > maximum) return false
        }
        return true
    }
}
