package app.logdate.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import org.intellij.markdown.MarkdownElementTypes

/**
 * Renders persisted journal text with the same Markdown vocabulary used by the entry editor.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val typography = MaterialTheme.typography
    val defaultTypography = markdownTypography()
    val readingStyle = textStyle.copy(textDirection = TextDirection.Content)
    val styles = markdownPreviewStyles()
    val document = remember(content) { parseMarkdownDocument(content) }
    val readable = remember(document, styles) { buildMarkdownAnnotatedText(document, styles, clickableLinks = true) }
    Markdown(
        state = remember(document) { State.Success(document.root, document.source, linksLookedUp = false) },
        annotator =
            remember(document, readable) {
                markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)) { _, node ->
                    document.projections[node]?.let { range ->
                        if (!range.isEmpty()) append(readable.subSequence(range.first, range.last + 1))
                    }
                    true
                }
            },
        components =
            markdownComponents(
                codeFence = { MarkdownDocumentCode(document, readable, it) },
                codeBlock = { MarkdownDocumentCode(document, readable, it) },
                eol = { model ->
                    val range = document.projections[model.node]
                    if (range != null &&
                        !range.isEmpty() &&
                        (range.first == 0 || document.plainText.getOrNull(range.first - 1) == '\n')
                    ) {
                        Text("", style = readingStyle)
                    }
                },
                checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
                image = { model ->
                    document.projections[model.node]?.takeUnless { it.isEmpty() }?.let { range ->
                        Text(readable.subSequence(range.first, range.last + 1), style = readingStyle)
                    }
                },
                inlineImage = { model ->
                    document.projections[model.node]?.takeUnless { it.isEmpty() }?.let { range ->
                        Text(readable.subSequence(range.first, range.last + 1), style = readingStyle)
                    }
                },
                custom = { type, model ->
                    if (type == MarkdownElementTypes.HTML_BLOCK) {
                        Text(
                            document.source.substring(model.node.startOffset, model.node.endOffset),
                            style = readingStyle,
                        )
                    }
                },
            ),
        typography =
            markdownTypography(
                h1 = typography.headlineMedium.copy(textDirection = TextDirection.Content),
                h2 = typography.headlineSmall.copy(textDirection = TextDirection.Content),
                h3 = typography.titleLarge.copy(textDirection = TextDirection.Content),
                h4 = typography.titleMedium.copy(textDirection = TextDirection.Content),
                h5 = typography.titleSmall.copy(textDirection = TextDirection.Content),
                h6 = typography.labelLarge.copy(textDirection = TextDirection.Content),
                text = readingStyle,
                code = defaultTypography.code.copy(textDirection = TextDirection.Content),
                inlineCode = defaultTypography.inlineCode.copy(textDirection = TextDirection.Content),
                quote = defaultTypography.quote.copy(textDirection = TextDirection.Content),
                paragraph = readingStyle,
                ordered = readingStyle,
                bullet = readingStyle,
                list = readingStyle,
                table = readingStyle,
            ),
        modifier = modifier,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun MarkdownDocumentCode(
    document: MarkdownDocument,
    readable: AnnotatedString,
    model: MarkdownComponentModel,
) {
    val range = document.projections[model.node]?.takeUnless { it.isEmpty() } ?: return
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(
            text = readable.subSequence(range.first, range.last + 1),
            style = model.typography.code,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

/**
 * Renders a compact, line-safe Markdown summary for cards and lists.
 *
 * A single text layout owns the line limit so glyphs are ellipsized instead of clipping independent
 * Markdown blocks. Its semantics are also limited to the characters that remain visible.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MarkdownPreviewText(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = 4,
) {
    val styles = markdownPreviewStyles()
    val preview = remember(content, styles) { buildMarkdownPreview(parseMarkdownDocument(content), styles, compact = true) }
    var semanticsText by remember(preview) { mutableStateOf(preview) }

    Text(
        text = preview,
        style = textStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            val visibleEnd =
                if (result.lineCount == 0) {
                    0
                } else {
                    result.getLineEnd(result.lineCount - 1, visibleEnd = true)
                }
            val updatedSemantics =
                previewSemanticsText(
                    content = preview,
                    visibleEnd = visibleEnd,
                    hasVisualOverflow = result.hasVisualOverflow,
                )
            if (updatedSemantics != semanticsText) {
                semanticsText = updatedSemantics
            }
        },
        modifier =
            modifier.clearAndSetSemantics {
                text = semanticsText
            },
    )
}

@Composable
internal fun markdownPreviewStyles(): MarkdownPreviewStyles {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    return remember(typography, colors) {
        MarkdownPreviewStyles(
            h1 = typography.headlineMedium,
            h2 = typography.headlineSmall,
            h3 = typography.titleLarge,
            h4 = typography.titleMedium,
            h5 = typography.titleSmall,
            h6 = typography.labelLarge,
            strong = SpanStyle(fontWeight = FontWeight.Bold),
            emphasis = SpanStyle(fontStyle = FontStyle.Italic),
            strikethrough = SpanStyle(textDecoration = TextDecoration.LineThrough),
            code =
                SpanStyle(
                    color = colors.onSurfaceVariant,
                    background = colors.surfaceVariant,
                    fontFamily = FontFamily.Monospace,
                ),
            link =
                SpanStyle(
                    color = colors.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            quote =
                SpanStyle(
                    color = colors.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                ),
        )
    }
}

internal data class MarkdownPreviewStyles(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val strong: SpanStyle,
    val emphasis: SpanStyle,
    val strikethrough: SpanStyle,
    val code: SpanStyle,
    val link: SpanStyle,
    val quote: SpanStyle,
)

internal fun buildMarkdownPreview(
    content: String,
    styles: MarkdownPreviewStyles,
): AnnotatedString = buildMarkdownPreview(parseMarkdownDocument(content), styles)

internal fun previewSemanticsText(
    content: AnnotatedString,
    visibleEnd: Int,
    hasVisualOverflow: Boolean,
): AnnotatedString {
    if (!hasVisualOverflow) return content

    var end = visibleEnd.coerceIn(0, content.length)
    while (end > 0 && content[end - 1].isWhitespace()) end--
    val visibleContent = content.subSequence(0, end)
    return AnnotatedString.Builder(visibleContent).apply { append('…') }.toAnnotatedString()
}

internal fun buildMarkdownPreview(
    document: MarkdownDocument,
    styles: MarkdownPreviewStyles,
    clickableLinks: Boolean = false,
    compact: Boolean = false,
): AnnotatedString {
    val text = buildMarkdownAnnotatedText(document, styles, clickableLinks)
    if (text.isEmpty()) return text
    val paragraphs = buildParagraphs(document, styles, text)
    return if (compact) paragraphs.withoutBlankLines() else paragraphs
}

private fun buildParagraphs(
    document: MarkdownDocument,
    styles: MarkdownPreviewStyles,
    text: AnnotatedString,
): AnnotatedString {
    // Android sizes every line of a paragraph from the paragraph's first line. A heading that shares the body's
    // paragraph would shrink the body lines to the heading's baseline and clip their descenders, so each heading
    // line is its own paragraph at its heading line height. A paragraph boundary is a line break, so it replaces
    // the newline it falls on.
    val body = ParagraphStyle(textDirection = TextDirection.Content)
    return buildAnnotatedString {
        var cursor = 0
        document.spans.forEach { span ->
            val heading = styles.headingFor(span.kind)
            if (heading == null || span.textStart >= span.textEnd) return@forEach
            val lineStart = text.text.lastIndexOf('\n', span.textStart - 1) + 1
            if (lineStart < cursor) return@forEach
            val lineEnd = text.text.indexOf('\n', span.textEnd).takeIf { it >= 0 } ?: text.length
            if (lineStart > cursor) withStyle(body) { append(text.subSequence(cursor, lineStart - 1)) }
            withStyle(body.copy(lineHeight = heading.lineHeight)) { append(text.subSequence(lineStart, lineEnd)) }
            cursor = minOf(lineEnd + 1, text.length)
        }
        if (cursor < text.length || text[cursor - 1] == '\n') {
            withStyle(body) { append(text.subSequence(cursor, text.length)) }
        }
    }
}

/**
 * Drops the blank lines between blocks so a line-limited card spends its lines on content and never ends on an
 * ellipsis that sits alone on an empty line. Heading paragraphs keep their own line height, which still sets
 * them apart from the body.
 */
private fun AnnotatedString.withoutBlankLines(): AnnotatedString {
    val paragraphStarts = paragraphStyles.map { it.start }.toSet()
    val removed =
        BooleanArray(length) { index ->
            text[index] == '\n' && (index == 0 || text[index - 1] == '\n' || index in paragraphStarts)
        }
    val shifts = IntArray(length + 1)
    for (index in 0 until length) shifts[index + 1] = shifts[index] + if (removed[index]) 1 else 0
    val result = AnnotatedString.Builder(buildString { text.forEachIndexed { index, char -> if (!removed[index]) append(char) } })
    spanStyles.forEach { range ->
        val start = range.start - shifts[range.start]
        val end = range.end - shifts[range.end]
        if (start < end) result.addStyle(range.item, start, end)
    }
    paragraphStyles.forEach { range ->
        val start = range.start - shifts[range.start]
        val end = range.end - shifts[range.end]
        if (start < end) result.addStyle(range.item, start, end)
    }
    return result.toAnnotatedString()
}

private fun buildMarkdownAnnotatedText(
    document: MarkdownDocument,
    styles: MarkdownPreviewStyles,
    clickableLinks: Boolean,
): AnnotatedString {
    val result = AnnotatedString.Builder(document.plainText)
    document.spans.forEach { span ->
        if (span.textStart < span.textEnd) {
            result.addStyle(styles.forKind(span.kind), span.textStart, span.textEnd)
            if (clickableLinks && span.kind == MarkdownStyleKind.LINK) {
                span.destination?.safeLinkDestination()?.let { destination ->
                    result.addLink(LinkAnnotation.Url(destination, TextLinkStyles(style = styles.link)), span.textStart, span.textEnd)
                }
            }
        }
    }
    return result.toAnnotatedString()
}

private fun String.safeLinkDestination(): String? =
    when {
        startsWith("https://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("mailto:", ignoreCase = true) ||
            startsWith("tel:", ignoreCase = true) -> this

        startsWith("www.", ignoreCase = true) -> "https://$this"

        contains('@') && !contains(':') && !contains(' ') -> "mailto:$this"

        else -> null
    }

private fun MarkdownPreviewStyles.headingFor(kind: MarkdownStyleKind): TextStyle? =
    when (kind) {
        MarkdownStyleKind.HEADING_1 -> h1
        MarkdownStyleKind.HEADING_2 -> h2
        MarkdownStyleKind.HEADING_3 -> h3
        MarkdownStyleKind.HEADING_4 -> h4
        MarkdownStyleKind.HEADING_5 -> h5
        MarkdownStyleKind.HEADING_6 -> h6
        else -> null
    }

private fun MarkdownPreviewStyles.forKind(kind: MarkdownStyleKind): SpanStyle =
    when (kind) {
        MarkdownStyleKind.HEADING_1 -> h1.toSpanStyle()
        MarkdownStyleKind.HEADING_2 -> h2.toSpanStyle()
        MarkdownStyleKind.HEADING_3 -> h3.toSpanStyle()
        MarkdownStyleKind.HEADING_4 -> h4.toSpanStyle()
        MarkdownStyleKind.HEADING_5 -> h5.toSpanStyle()
        MarkdownStyleKind.HEADING_6 -> h6.toSpanStyle()
        MarkdownStyleKind.STRONG -> strong
        MarkdownStyleKind.EMPHASIS -> emphasis
        MarkdownStyleKind.STRIKETHROUGH -> strikethrough
        MarkdownStyleKind.INLINE_CODE, MarkdownStyleKind.CODE_BLOCK -> code
        MarkdownStyleKind.LINK -> link
        MarkdownStyleKind.BLOCK_QUOTE -> quote
        MarkdownStyleKind.LIST_MARKER -> SpanStyle()
    }
