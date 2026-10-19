@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalFoundationApi::class)

package app.logdate.feature.search.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.key
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.search.rememberSearchHighlightStyle
import app.logdate.ui.search.toUniversalSearchResultUiState
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import app.logdate.util.toReadableDateShort
import coil3.compose.AsyncImage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.search.generated.resources.Res
import logdate.client.feature.search.generated.resources.search_date_range
import logdate.client.feature.search.generated.resources.search_day_match_count
import logdate.client.feature.search.generated.resources.search_entry_count
import logdate.client.feature.search.generated.resources.search_more_in_day
import logdate.client.feature.search.generated.resources.search_result_count
import logdate.client.feature.search.generated.resources.search_section_collections
import logdate.client.feature.search.generated.resources.search_section_days
import logdate.client.feature.search.generated.resources.search_section_top_matches
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

const val ADAPTIVE_SEARCH_RESULTS_TAG = "adaptive_search_results"
const val ADAPTIVE_SEARCH_TOP_MATCHES_TAG = "adaptive_search_top_matches"
const val ADAPTIVE_SEARCH_COLLECTIONS_TAG = "adaptive_search_collections"
const val ADAPTIVE_SEARCH_DAYS_TAG = "adaptive_search_days"

private const val MAX_THUMBNAILS_PER_DAY = 3
private const val MAX_LINES_PER_DAY = 3

/**
 * Search results arranged by what they are: people, places and journals first, then rewinds and
 * postcards with their covers, then entries grouped by day with their photos, stickers and
 * recordings.
 *
 * Each group sits in a tonal card of Material 3 list items, the same row anatomy the other search
 * results use.
 *
 * @param details The media, durations and covers of the results; a result with no entry shows only
 *   its text.
 * @param onResultClick Opens the destination of a single result.
 * @param onResultLongClick Opens the actions for a single result.
 * @param onOpenDay Opens the day a result belongs to.
 * @param selected The result or day shown in a preview beside the list, marked in the list.
 */
@Composable
fun AdaptiveSearchResults(
    groups: SearchResultGroups,
    details: Map<SearchResultKey, SearchResultDetail>,
    onResultClick: (SearchResult) -> Unit,
    onResultLongClick: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    selected: SearchSelection? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(ADAPTIVE_SEARCH_RESULTS_TAG),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "result_count") {
            Text(
                text = pluralStringResource(Res.plurals.search_result_count, groups.total, groups.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        }
        if (groups.topMatches.isNotEmpty()) {
            item(key = "top_matches") {
                ResultGroup(stringResource(Res.string.search_section_top_matches), ADAPTIVE_SEARCH_TOP_MATCHES_TAG) {
                    groups.topMatches.forEach { result ->
                        EntityItem(result, details[result.key], selected.isResult(result), onResultClick, onResultLongClick)
                    }
                }
            }
        }
        if (groups.collections.isNotEmpty()) {
            item(key = "collections") {
                ResultGroup(stringResource(Res.string.search_section_collections), ADAPTIVE_SEARCH_COLLECTIONS_TAG) {
                    groups.collections.forEach { result ->
                        CollectionItem(result, details[result.key], selected.isResult(result), onResultClick, onResultLongClick)
                    }
                }
            }
        }
        if (groups.days.isNotEmpty()) {
            item(key = "days_heading") {
                SectionHeading(stringResource(Res.string.search_section_days), ADAPTIVE_SEARCH_DAYS_TAG)
            }
            items(groups.days, key = { "day_${it.date}" }) { day ->
                DayCard(day, details, selected, onResultClick, onResultLongClick, onOpenDay)
            }
        }
    }
}

@Composable
private fun SectionHeading(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .testTag(tag),
    )
}

/** A heading over a tonal card whose rows are the group's results. */
@Composable
private fun ResultGroup(
    title: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeading(title, tag)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = Spacing.xs)) { content() }
        }
    }
}

@Composable
private fun EntityItem(
    result: SearchResult,
    detail: SearchResultDetail?,
    selected: Boolean,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    val state = result.toUniversalSearchResultUiState(rememberSearchHighlightStyle())
    val supporting =
        when (detail) {
            is SearchResultDetail.Journal -> pluralStringResource(Res.plurals.search_entry_count, detail.entryCount, detail.entryCount)
            is SearchResultDetail.Person -> detail.relationshipLabel
            else -> null
        }
    val cover = (detail as? SearchResultDetail.Journal)?.coverUri ?: (detail as? SearchResultDetail.Person)?.photoUri
    ResultItem(
        result = result,
        title = state.contentText,
        supporting = supporting,
        typeLabel = stringResource(state.typeLabelKey),
        cover = cover,
        icon = state.typeIcon,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun CollectionItem(
    result: SearchResult,
    detail: SearchResultDetail?,
    selected: Boolean,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    val state = result.toUniversalSearchResultUiState(rememberSearchHighlightStyle())
    val zone = TimeZone.currentSystemDefault()
    val (cover, supporting) =
        when (detail) {
            is SearchResultDetail.Rewind ->
                detail.coverUri to
                    stringResource(
                        Res.string.search_date_range,
                        detail.start
                            .toLocalDateTime(zone)
                            .date
                            .toReadableDateShort(),
                        detail.end
                            .toLocalDateTime(zone)
                            .date
                            .toReadableDateShort(),
                    )
            is SearchResultDetail.Postcard ->
                detail.coverUri to
                    detail.modifiedAt
                        .toLocalDateTime(zone)
                        .date
                        .toReadableDateShort()
            else -> null to state.createdReadable
        }
    ResultItem(
        result = result,
        title = state.contentText,
        supporting = supporting,
        typeLabel = stringResource(state.typeLabelKey),
        cover = cover,
        icon = state.typeIcon,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** One result as a Material 3 list item: a tonal cover or icon, the text, and the kind of result. */
@Composable
private fun ResultItem(
    result: SearchResult,
    title: AnnotatedString,
    supporting: String?,
    typeLabel: String,
    cover: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            supporting?.takeIf { it.isNotBlank() }?.let { text ->
                { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        leadingContent = { Cover(result.contentType, cover, icon, typeLabel) },
        trailingContent = { Text(typeLabel, style = MaterialTheme.typography.labelSmall) },
        colors = ListItemDefaults.colors(containerColor = selectedContainer(selected)),
        modifier =
            Modifier
                .focusableWithRing()
                .combinedClickable(onClick = { onClick(result) }, onLongClick = { onLongClick(result) }),
    )
}

/** A tonal square holding a cover image, or the icon of the kind of result when it has none. */
@Composable
internal fun Cover(
    type: SearchContentType,
    uri: String?,
    icon: ImageVector,
    label: String,
    size: Dp = 56.dp,
) {
    val (container, content) = type.tonalColors()
    val journal = type == SearchContentType.JOURNAL
    Surface(
        color = if (journal) MaterialTheme.colorScheme.tertiary else container,
        contentColor = if (journal) MaterialTheme.colorScheme.onTertiary else content,
        shape = if (journal) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp) else MaterialTheme.shapes.medium,
        modifier = if (journal) Modifier.size(width = size * 0.75f, height = size) else Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (uri != null) {
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(imageVector = icon, contentDescription = label)
            }
        }
    }
}

/** The container of a list row: the selected tone when it is the one previewed, otherwise the card behind it. */
@Composable
private fun selectedContainer(selected: Boolean): Color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

private fun SearchSelection?.isResult(result: SearchResult): Boolean = this is SearchSelection.Result && key == result.key

@Composable
private fun SearchContentType.tonalColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SearchContentType.JOURNAL, SearchContentType.REWIND, SearchContentType.PERSON ->
            scheme.primaryContainer to scheme.onPrimaryContainer
        SearchContentType.TRANSCRIPTION, SearchContentType.AMBIENT_SOUND, SearchContentType.PLACE ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        SearchContentType.MEDIA_CAPTION -> scheme.surfaceContainerHigh to scheme.onSurface
        SearchContentType.TEXT_NOTE, SearchContentType.STICKER, SearchContentType.POSTCARD ->
            scheme.secondaryContainer to scheme.onSecondaryContainer
    }
}

/** One day's matches in a tonal card: the date, photo and sticker thumbnails, then a line for each other match. */
@Composable
private fun DayCard(
    day: SearchDayGroup,
    details: Map<SearchResultKey, SearchResultDetail>,
    selected: SearchSelection?,
    onResultClick: (SearchResult) -> Unit,
    onResultLongClick: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
) {
    val highlight = rememberSearchHighlightStyle()
    val thumbnails = day.results.filter { details[it.key].hasArt() }.take(MAX_THUMBNAILS_PER_DAY)
    val lines = day.results.filterNot { details[it.key].hasArt() }
    val daySelected = selected is SearchSelection.Day && selected.date == day.date
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (daySelected && selected.highlight == null) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        modifier = Modifier.fillMaxWidth().testTag("adaptive_search_day_${day.date}"),
    ) {
        ListItem(
            headlineContent = { Text(formatDateLocalized(day.date), style = MaterialTheme.typography.titleMedium) },
            trailingContent = {
                Text(
                    text = pluralStringResource(Res.plurals.search_day_match_count, day.results.size, day.results.size),
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.focusableWithRing().combinedClickable(onClick = { onOpenDay(day.results.first()) }),
        )
        if (thumbnails.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            ) {
                thumbnails.forEach { result -> DayThumbnail(result, details[result.key], onResultClick, onResultLongClick) }
            }
        }
        lines.take(MAX_LINES_PER_DAY).forEach { result ->
            DayLine(
                result,
                details[result.key],
                highlight,
                daySelected && selected.highlight == result.key,
                onResultClick,
                onResultLongClick,
            )
        }
        if (lines.size > MAX_LINES_PER_DAY) {
            TextButton(onClick = { onOpenDay(day.results.first()) }, modifier = Modifier.padding(start = Spacing.sm)) {
                Text(stringResource(Res.string.search_more_in_day, lines.size - MAX_LINES_PER_DAY))
            }
        }
    }
}

@Composable
private fun DayThumbnail(
    result: SearchResult,
    detail: SearchResultDetail?,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    val uri =
        when (detail) {
            is SearchResultDetail.Image -> detail.uri
            is SearchResultDetail.Video -> detail.uri
            is SearchResultDetail.Sticker -> detail.imageUri
            else -> null
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier
                .size(width = 96.dp, height = 72.dp)
                .focusableWithRing()
                .semantics { contentDescription = result.content }
                .combinedClickable(onClick = { onClick(result) }, onLongClick = { onLongClick(result) }),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = if (detail is SearchResultDetail.Sticker) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.size(width = 96.dp, height = 72.dp),
        )
    }
}

@Composable
private fun DayLine(
    result: SearchResult,
    detail: SearchResultDetail?,
    highlight: SpanStyle,
    selected: Boolean,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    val state = result.toUniversalSearchResultUiState(highlight)
    val durationMs = (detail as? SearchResultDetail.Audio)?.durationMs
    ListItem(
        headlineContent = { Text(state.contentText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(imageVector = state.typeIcon, contentDescription = stringResource(state.typeLabelKey)) },
        trailingContent =
            durationMs?.takeIf { it > 0 }?.let { ms ->
                { Text(formatDuration(ms), style = MaterialTheme.typography.labelMedium) }
            },
        colors = ListItemDefaults.colors(containerColor = selectedContainer(selected)),
        modifier =
            Modifier
                .focusableWithRing()
                .combinedClickable(onClick = { onClick(result) }, onLongClick = { onLongClick(result) }),
    )
}

private fun SearchResultDetail?.hasArt(): Boolean =
    this is SearchResultDetail.Image || this is SearchResultDetail.Video || this is SearchResultDetail.Sticker

/** Formats a length in milliseconds as minutes and seconds, such as `0:48`. */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
