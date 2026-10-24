package app.logdate.ui.common

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

class MarkdownDocument internal constructor(
    val source: String,
    val plainText: String,
    val blocks: List<MarkdownBlock>,
    val spans: List<MarkdownSemanticSpan>,
    internal val root: ASTNode,
    internal val projections: Map<ASTNode, IntRange>,
)

/** Parses once for editing, compact previews, and full reading on every Compose platform. */
fun parseMarkdownDocument(source: String): MarkdownDocument {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    return MarkdownDocumentBuilder(source).build(root)
}

private class MarkdownDocumentBuilder(
    private val source: String,
) {
    private val text = StringBuilder()
    private val spans = mutableListOf<MarkdownSemanticSpan>()
    private val projections = mutableMapOf<ASTNode, IntRange>()
    private val referenceLinks = mutableMapOf<String, String>()

    fun build(root: ASTNode): MarkdownDocument {
        root.descendants().filter { it.type == MarkdownElementTypes.LINK_DEFINITION }.forEach { definition ->
            val label = definition.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val destination = definition.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            if (label != null && destination != null) referenceLinks.getOrPut(raw(label).referenceKey()) { raw(destination).trim('<', '>') }
        }
        val visibleNodes = root.children.takeWhileLastDefinitionIsHidden()
        visibleNodes.forEach(::render)
        root.descendants().forEach { projections.getOrPut(it) { text.length..<text.length } }
        return MarkdownDocument(
            source = source,
            plainText = text.toString(),
            blocks = root.children.mapNotNull { node -> node.blockKind()?.let { MarkdownBlock(it, node.startOffset, node.endOffset) } },
            spans =
                spans.sortedWith(
                    compareBy(MarkdownSemanticSpan::sourceStart, MarkdownSemanticSpan::sourceEnd, MarkdownSemanticSpan::kind),
                ),
            root = root,
            projections = projections,
        )
    }

    private fun render(node: ASTNode) {
        val start = text.length
        when (node.type) {
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> {
                heading(node, MarkdownStyleKind.HEADING_1)
            }

            MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> {
                heading(node, MarkdownStyleKind.HEADING_2)
            }

            MarkdownElementTypes.ATX_3 -> {
                heading(node, MarkdownStyleKind.HEADING_3)
            }

            MarkdownElementTypes.ATX_4 -> {
                heading(node, MarkdownStyleKind.HEADING_4)
            }

            MarkdownElementTypes.ATX_5 -> {
                heading(node, MarkdownStyleKind.HEADING_5)
            }

            MarkdownElementTypes.ATX_6 -> {
                heading(node, MarkdownStyleKind.HEADING_6)
            }

            else -> {
                renderInlineOrBlock(node)
            }
        }
        projections[node] = start..<text.length
    }

    private fun renderInlineOrBlock(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.STRONG -> {
                delimited(node, MarkdownStyleKind.STRONG, 2)
            }

            MarkdownElementTypes.EMPH -> {
                if (node.children.isEmpty()) appendRaw(node) else delimited(node, MarkdownStyleKind.EMPHASIS, 1)
            }

            GFMElementTypes.STRIKETHROUGH -> {
                delimited(node, MarkdownStyleKind.STRIKETHROUGH, 2)
            }

            MarkdownElementTypes.CODE_SPAN -> {
                inlineCode(node)
            }

            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> {
                codeBlock(node)
            }

            MarkdownElementTypes.BLOCK_QUOTE -> {
                if (node.children.isNotEmpty()) {
                    val first = node.children.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH || it.children.isNotEmpty() }
                    if (first != null) styled(MarkdownStyleKind.BLOCK_QUOTE, first.startOffset, node.endOffset) { children(node) }
                }
            }

            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                link(
                    node,
                )
            }

            MarkdownElementTypes.AUTOLINK -> {
                val content = node.children.filterNot { it.type == MarkdownTokenTypes.LT || it.type == MarkdownTokenTypes.GT }
                if (content.isNotEmpty()) {
                    styled(MarkdownStyleKind.LINK, content.first().startOffset, content.last().endOffset, raw(node).trim('<', '>')) {
                        text.append(source.substring(content.first().startOffset, content.last().endOffset))
                    }
                }
            }

            MarkdownElementTypes.IMAGE -> {
                label(node)
            }

            else -> {
                renderStructure(node)
            }
        }
    }

    private fun renderStructure(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.LINK_DEFINITION -> {
                Unit
            }

            MarkdownElementTypes.LIST_ITEM -> {
                listItem(node)
            }

            MarkdownElementTypes.ORDERED_LIST -> {
                orderedList(node)
            }

            GFMElementTypes.TABLE -> {
                table(node)
            }

            MarkdownElementTypes.HTML_BLOCK -> {
                text.append(source.substring(node.startOffset, node.endOffset))
            }

            MarkdownTokenTypes.HORIZONTAL_RULE -> {
                text.append('—')
            }

            MarkdownTokenTypes.HARD_LINE_BREAK -> {
                Unit
            }

            // The following EOL owns the newline.
            MarkdownTokenTypes.BLOCK_QUOTE -> {
                Unit
            }

            GFMTokenTypes.GFM_AUTOLINK -> {
                styled(MarkdownStyleKind.LINK, node.startOffset, node.endOffset, raw(node)) { appendRaw(node) }
            }

            else -> {
                if (node.children.isEmpty()) appendRaw(node) else children(node)
            }
        }
    }

    private fun children(node: ASTNode) {
        var skipQuoteSpace = false
        node.children.forEach { child ->
            if (child.type == MarkdownTokenTypes.BLOCK_QUOTE) {
                projections[child] = text.length..<text.length
                skipQuoteSpace = true
            } else if (skipQuoteSpace && child.type == MarkdownTokenTypes.WHITE_SPACE) {
                projections[child] = text.length..<text.length
                skipQuoteSpace = false
            } else {
                skipQuoteSpace = false
                render(child)
            }
        }
    }

    private fun heading(
        node: ASTNode,
        kind: MarkdownStyleKind,
    ) {
        val content =
            node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT }
                ?: return
        val visible =
            content.children.dropWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }.dropLastWhile {
                it.type ==
                    MarkdownTokenTypes.WHITE_SPACE
            }
        if (visible.isNotEmpty()) styled(kind, visible.first().startOffset, visible.last().endOffset) { visible.forEach(::render) }
    }

    private fun delimited(
        node: ASTNode,
        kind: MarkdownStyleKind,
        width: Int,
    ) {
        val start = node.startOffset + width
        val end = node.endOffset - width
        if (start >= end) return
        styled(kind, start, end) {
            node.children.filter { it.startOffset >= start && it.endOffset <= end }.forEach(::render)
        }
    }

    private fun inlineCode(node: ASTNode) {
        val opening = node.children.firstOrNull() ?: return
        val closing = node.children.lastOrNull() ?: return
        val start = opening.endOffset
        val end = closing.startOffset
        if (start >= end) return
        styled(MarkdownStyleKind.INLINE_CODE, start, end) {
            var value = source.substring(start, end).replace("\r\n", " ").replace('\n', ' ')
            if (value.startsWith(' ') && value.endsWith(' ') && value.any { it != ' ' }) value = value.drop(1).dropLast(1)
            text.append(value)
        }
    }

    private fun codeBlock(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE) {
            fencedCode(node)
            return
        }
        val code = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.CODE_LINE }
        if (code.isEmpty()) return
        val start = code.first().startOffset
        val end = code.last().endOffset
        styled(MarkdownStyleKind.CODE_BLOCK, start, end) {
            node.children.forEach { child ->
                when (child.type) {
                    MarkdownTokenTypes.CODE_LINE -> {
                        val line = raw(child)
                        text.append(if (line.startsWith('\t')) line.drop(1) else line.removePrefix("    "))
                    }

                    MarkdownTokenTypes.EOL -> {
                        text.append('\n')
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    private fun fencedCode(node: ASTNode) {
        val openingNewline = node.children.firstOrNull { it.type == MarkdownTokenTypes.EOL } ?: return
        val closing = node.children.firstOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }
        val start = openingNewline.endOffset
        val end =
            if (closing == null) {
                node.endOffset
            } else {
                node.children.lastOrNull { it.type == MarkdownTokenTypes.EOL && it.endOffset <= closing.startOffset }?.startOffset
                    ?: closing.startOffset
            }
        if (start >= end) return
        styled(MarkdownStyleKind.CODE_BLOCK, start, end) {
            text.append(source.substring(start, end).replace("\r\n", "\n"))
        }
    }

    private fun link(node: ASTNode) {
        val label =
            node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                ?: node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL } ?: return
        val reference =
            node.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                ?.let(::raw)
                ?.takeIf { it.referenceKey().isNotEmpty() } ?: raw(label)
        val destination =
            node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.let(::raw)
                ?: referenceLinks[reference.referenceKey()]
        if (destination == null) {
            appendRaw(node)
            return
        }
        val visible = label.children.drop(1).dropLast(1)
        if (visible.isNotEmpty()) {
            styled(
                MarkdownStyleKind.LINK,
                visible.first().startOffset,
                visible.last().endOffset,
                destination.trim('<', '>'),
            ) {
                visible.forEach(::render)
            }
        }
    }

    private fun label(node: ASTNode) {
        val label = node.descendants().firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: return
        label.children
            .drop(1)
            .dropLast(1)
            .forEach(::render)
    }

    private fun orderedList(node: ASTNode) {
        val firstMarker =
            node.children
                .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
                ?.children
                ?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        var number = firstMarker?.let(::raw)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1
        node.children.forEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val start = text.length
                listItem(child, number++)
                projections[child] = start..<text.length
            } else {
                render(child)
            }
        }
    }

    private fun listItem(
        node: ASTNode,
        number: Int? = null,
    ) {
        val task = node.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
        node.children.forEach { child ->
            when (child.type) {
                MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER -> {
                    styled(
                        MarkdownStyleKind.LIST_MARKER,
                        child.startOffset,
                        child.endOffset - raw(child).takeLastWhile(Char::isWhitespace).length,
                    ) {
                        if (task == null) {
                            text.append(if (child.type == MarkdownTokenTypes.LIST_BULLET) "• " else number?.let { "$it. " } ?: raw(child))
                        }
                    }
                }

                GFMTokenTypes.CHECK_BOX -> {
                    styled(MarkdownStyleKind.LIST_MARKER, child.startOffset, child.startOffset + 3) {
                        text.append(if (raw(child).getOrNull(1)?.lowercaseChar() == 'x') "☑ " else "☐ ")
                    }
                }

                else -> {
                    render(child)
                }
            }
        }
    }

    private fun table(node: ASTNode) {
        node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) text.append('\n')
            row.children.filter { it.type == GFMTokenTypes.CELL }.forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) text.append('\t')
                val start = text.length
                cell.children
                    .dropWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }
                    .dropLastWhile {
                        it.type ==
                            MarkdownTokenTypes.WHITE_SPACE
                    }.forEach(::render)
                projections[cell] = start..<text.length
            }
        }
    }

    private fun styled(
        kind: MarkdownStyleKind,
        sourceStart: Int,
        sourceEnd: Int,
        destination: String? = null,
        render: () -> Unit,
    ) {
        val start = text.length
        render()
        if (sourceStart < sourceEnd) spans += MarkdownSemanticSpan(kind, sourceStart, sourceEnd, start, text.length, destination)
    }

    private fun raw(node: ASTNode): String = source.substring(node.startOffset, node.endOffset)

    private fun String.referenceKey(): String =
        trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
            .lowercase()
            .split(Regex("\\s+"))
            .joinToString(" ")

    private fun appendRaw(
        node: ASTNode,
        unescape: Boolean = true,
    ) {
        val value = raw(node)
        var index = 0
        while (index < value.length) {
            if (unescape && value[index] == '\\' && index + 1 < value.length && value[index + 1] in ESCAPABLE_MARKDOWN) index++
            if (value[index] != '\r') text.append(value[index])
            index++
        }
    }

    private fun List<ASTNode>.takeWhileLastDefinitionIsHidden(): List<ASTNode> {
        var end = size
        while (end > 0 && this[end - 1].type == MarkdownElementTypes.LINK_DEFINITION) {
            end--
            while (end > 0 && this[end - 1].type == MarkdownTokenTypes.EOL) end--
        }
        return take(end)
    }

    private fun ASTNode.descendants(): Sequence<ASTNode> = children.asSequence().flatMap { sequenceOf(it) + it.descendants() }

    private fun ASTNode.blockKind(): MarkdownBlockKind? =
        when (type) {
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
            -> MarkdownBlockKind.HEADING

            MarkdownElementTypes.PARAGRAPH -> MarkdownBlockKind.PARAGRAPH

            MarkdownElementTypes.ORDERED_LIST -> MarkdownBlockKind.ORDERED_LIST

            MarkdownElementTypes.UNORDERED_LIST -> MarkdownBlockKind.UNORDERED_LIST

            MarkdownElementTypes.BLOCK_QUOTE -> MarkdownBlockKind.BLOCK_QUOTE

            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> MarkdownBlockKind.CODE_BLOCK

            MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownBlockKind.DIVIDER

            GFMElementTypes.TABLE -> MarkdownBlockKind.TABLE

            MarkdownElementTypes.HTML_BLOCK -> MarkdownBlockKind.HTML

            else -> null
        }

    private companion object {
        val ESCAPABLE_MARKDOWN = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }
}
