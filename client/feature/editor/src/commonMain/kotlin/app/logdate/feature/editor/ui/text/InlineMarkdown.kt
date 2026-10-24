package app.logdate.feature.editor.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import app.logdate.ui.common.MarkdownStyleKind
import app.logdate.ui.common.parseMarkdownDocument

internal typealias InlineMarkdownStyle = MarkdownStyleKind

@Composable
internal fun rememberInlineMarkdownVisualTransformation(): VisualTransformation {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    return remember(typography, colors) {
        InlineMarkdownVisualTransformation { style ->
            when (style) {
                InlineMarkdownStyle.HEADING_1 -> typography.headlineMedium.toSpanStyle()

                InlineMarkdownStyle.HEADING_2 -> typography.headlineSmall.toSpanStyle()

                InlineMarkdownStyle.HEADING_3 -> typography.titleLarge.toSpanStyle()

                InlineMarkdownStyle.HEADING_4 -> typography.titleMedium.toSpanStyle()

                InlineMarkdownStyle.HEADING_5 -> typography.titleSmall.toSpanStyle()

                InlineMarkdownStyle.HEADING_6 -> typography.labelLarge.toSpanStyle()

                InlineMarkdownStyle.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)

                InlineMarkdownStyle.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)

                InlineMarkdownStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)

                InlineMarkdownStyle.INLINE_CODE,
                InlineMarkdownStyle.CODE_BLOCK,
                -> SpanStyle(color = colors.onSurfaceVariant, background = colors.surfaceVariant, fontFamily = FontFamily.Monospace)

                InlineMarkdownStyle.LINK -> SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)

                InlineMarkdownStyle.BLOCK_QUOTE -> SpanStyle(color = colors.onSurfaceVariant, fontStyle = FontStyle.Italic)

                InlineMarkdownStyle.LIST_MARKER -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal data class InlineMarkdownSpan(
    val style: InlineMarkdownStyle,
    val start: Int,
    val end: Int,
)

internal fun styleInlineMarkdown(
    text: String,
    styleFor: (InlineMarkdownStyle) -> SpanStyle,
): AnnotatedString {
    val styledText = AnnotatedString.Builder(text)
    if (text.isNotEmpty()) styledText.addStyle(ParagraphStyle(textDirection = TextDirection.Content), 0, text.length)
    parseInlineMarkdown(text).forEach { span ->
        styledText.addStyle(
            style = styleFor(span.style),
            start = span.start,
            end = span.end,
        )
    }
    return styledText.toAnnotatedString()
}

internal class InlineMarkdownVisualTransformation(
    private val styleFor: (InlineMarkdownStyle) -> SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            text = styleInlineMarkdown(text.text, styleFor),
            offsetMapping = OffsetMapping.Identity,
        )
}

internal fun parseInlineMarkdown(text: String): List<InlineMarkdownSpan> =
    parseMarkdownDocument(text).spans.map { span ->
        InlineMarkdownSpan(
            style = span.kind,
            start = span.sourceStart,
            end = span.sourceEnd,
        )
    }
