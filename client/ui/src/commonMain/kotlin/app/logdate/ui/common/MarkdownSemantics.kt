package app.logdate.ui.common

enum class MarkdownStyleKind {
    HEADING_1,
    HEADING_2,
    HEADING_3,
    HEADING_4,
    HEADING_5,
    HEADING_6,
    STRONG,
    EMPHASIS,
    STRIKETHROUGH,
    INLINE_CODE,
    CODE_BLOCK,
    LINK,
    BLOCK_QUOTE,
    LIST_MARKER,
}

enum class MarkdownBlockKind {
    HEADING,
    PARAGRAPH,
    ORDERED_LIST,
    UNORDERED_LIST,
    BLOCK_QUOTE,
    CODE_BLOCK,
    DIVIDER,
    TABLE,
    HTML,
}

/** Half-open UTF-16 ranges in the exact source and in the readable text. */
data class MarkdownSemanticSpan(
    val kind: MarkdownStyleKind,
    val sourceStart: Int,
    val sourceEnd: Int,
    val textStart: Int,
    val textEnd: Int,
    val destination: String? = null,
)

data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val sourceStart: Int,
    val sourceEnd: Int,
)
