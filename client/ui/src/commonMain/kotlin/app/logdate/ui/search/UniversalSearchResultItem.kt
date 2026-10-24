@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.logdate.ui.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.ui.common.focusableWithRing
import app.logdate.util.toReadableDateTimeShort
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.search_type_label_journal
import logdate.client.ui.generated.resources.search_type_label_note
import logdate.client.ui.generated.resources.search_type_label_person
import logdate.client.ui.generated.resources.search_type_label_photo
import logdate.client.ui.generated.resources.search_type_label_place
import logdate.client.ui.generated.resources.search_type_label_postcard
import logdate.client.ui.generated.resources.search_type_label_rewind
import logdate.client.ui.generated.resources.search_type_label_soundscape
import logdate.client.ui.generated.resources.search_type_label_sticker
import logdate.client.ui.generated.resources.search_type_label_voice_note
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

data class UniversalSearchResultUiState(
    val id: String,
    val contentText: AnnotatedString,
    /**
     * Pre-formatted "date" portion of the supporting line. Null for content types that don't
     * surface a date (currently only [SearchContentType.PERSON]); the row then shows just the
     * type label.
     */
    val createdReadable: String?,
    val typeLabelKey: StringResource,
    val typeIcon: ImageVector,
    val contentType: SearchContentType,
)

/**
 * Renders a single universal search result.
 *
 * The leading type icon is wrapped in a tonal [Surface] colored by [SearchContentType] so users
 * can scan results visually. Match highlighting in [UniversalSearchResultUiState.contentText] is
 * pre-computed by the caller via [toUniversalSearchResultUiState] using the desired [SpanStyle].
 */
@Composable
fun UniversalSearchResultItem(
    state: UniversalSearchResultUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val typeLabel = stringResource(state.typeLabelKey)
    val supportingText =
        if (state.createdReadable != null) {
            "${state.createdReadable} · $typeLabel"
        } else {
            typeLabel
        }
    val scheme = MaterialTheme.colorScheme
    val containerColor: Color
    val contentColor: Color
    when (state.contentType) {
        SearchContentType.JOURNAL,
        SearchContentType.REWIND,
        SearchContentType.PERSON,
        -> {
            containerColor = scheme.primaryContainer
            contentColor = scheme.onPrimaryContainer
        }

        SearchContentType.TRANSCRIPTION,
        SearchContentType.AMBIENT_SOUND,
        SearchContentType.PLACE,
        -> {
            containerColor = scheme.tertiaryContainer
            contentColor = scheme.onTertiaryContainer
        }

        SearchContentType.MEDIA_CAPTION -> {
            containerColor = scheme.surfaceContainerHigh
            contentColor = scheme.onSurface
        }

        SearchContentType.TEXT_NOTE,
        SearchContentType.STICKER,
        SearchContentType.POSTCARD,
        -> {
            containerColor = scheme.secondaryContainer
            contentColor = scheme.onSecondaryContainer
        }
    }
    ListItem(
        headlineContent = {
            Text(
                text = state.contentText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(state.typeIcon, contentDescription = typeLabel)
                }
            }
        },
        modifier =
            modifier
                .focusableWithRing()
                .let { base ->
                    if (onLongClick != null) base.onSecondaryButtonClick(onLongClick) else base
                }.let { base ->
                    if (state.contentText.text.isNotBlank()) {
                        base.dragSearchResultText(state.contentText.text)
                    } else {
                        base
                    }
                }.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    )
}

/**
 * Fires [action] when the user clicks with a secondary pointer button (right-click on a mouse,
 * stylus secondary button). Pairs with `combinedClickable(onLongClick = ...)` so touch users get
 * the same affordance via long-press while desktop / pointer users get the conventional context-
 * menu gesture per Android Adaptive desktop guidelines.
 */
private fun Modifier.onSecondaryButtonClick(action: () -> Unit): Modifier =
    pointerInput(action) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    action()
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }

/**
 * Converts a [SearchResult] into a render-ready [UniversalSearchResultUiState].
 *
 * @param matchStyle The [SpanStyle] to apply to FTS5-matched terms inside [SearchResult.content].
 *   Defaults to the bold-only style; pass a richer style (e.g. with `background`) at the call site
 *   when composable theme colors are available.
 */
fun SearchResult.toUniversalSearchResultUiState(matchStyle: SpanStyle = DefaultMatchStyle): UniversalSearchResultUiState {
    val (icon, labelKey) = contentType.visualInfo()
    return UniversalSearchResultUiState(
        id = "${contentType.ftsValue}_$uid",
        contentText = parseSnippetMarkers(content, matchStyle),
        createdReadable =
            when (contentType) {
                SearchContentType.PERSON -> null
                else -> created.toReadableDateTimeShort()
            },
        typeLabelKey = labelKey,
        typeIcon = icon,
        contentType = contentType,
    )
}

private fun SearchContentType.visualInfo(): Pair<ImageVector, StringResource> =
    when (this) {
        SearchContentType.TEXT_NOTE -> Icons.AutoMirrored.Default.Notes to Res.string.search_type_label_note
        SearchContentType.TRANSCRIPTION -> Icons.Default.Mic to Res.string.search_type_label_voice_note
        SearchContentType.JOURNAL -> Icons.Default.Book to Res.string.search_type_label_journal
        SearchContentType.MEDIA_CAPTION -> Icons.Default.Image to Res.string.search_type_label_photo
        SearchContentType.PLACE -> Icons.Default.LocationOn to Res.string.search_type_label_place
        SearchContentType.REWIND -> Icons.Default.Replay to Res.string.search_type_label_rewind
        SearchContentType.STICKER -> Icons.Default.Star to Res.string.search_type_label_sticker
        SearchContentType.POSTCARD -> Icons.Default.Mail to Res.string.search_type_label_postcard
        SearchContentType.AMBIENT_SOUND -> Icons.Default.GraphicEq to Res.string.search_type_label_soundscape
        SearchContentType.PERSON -> Icons.Default.Person to Res.string.search_type_label_person
    }

/**
 * Match-highlight style applied to FTS5-snippet matches. Memoized against the current color
 * scheme so the resulting [SpanStyle] is stable as a `remember` key and snippet AnnotatedStrings
 * are not rebuilt on every recomposition.
 */
@Composable
fun rememberSearchHighlightStyle(): SpanStyle {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(primaryContainer, onPrimaryContainer) {
        SpanStyle(
            background = primaryContainer,
            color = onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
