@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalFoundationApi::class)

package app.logdate.feature.search.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
 * @param details The media, durations and covers of the results; a result with no entry shows only
 *   its text.
 * @param onResultClick Opens the destination of a single result.
 * @param onResultLongClick Opens the actions for a single result.
 * @param onOpenDay Opens the day a result belongs to.
 */
@Composable
fun AdaptiveSearchResults(
    groups: SearchResultGroups,
    details: Map<SearchResultKey, SearchResultDetail>,
    onResultClick: (SearchResult) -> Unit,
    onResultLongClick: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(ADAPTIVE_SEARCH_RESULTS_TAG),
        contentPadding = PaddingValues(bottom = Spacing.lg),
    ) {
        item(key = "result_count") {
            Text(
                text = pluralStringResource(Res.plurals.search_result_count, groups.total, groups.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
        if (groups.topMatches.isNotEmpty()) {
            item(key = "header_top_matches") {
                SectionHeader(stringResource(Res.string.search_section_top_matches), ADAPTIVE_SEARCH_TOP_MATCHES_TAG)
            }
            items(groups.topMatches, key = { "top_${it.contentType.ftsValue}_${it.uid}" }) { result ->
                EntityRow(result, details[result.key], onResultClick, onResultLongClick)
            }
        }
        if (groups.collections.isNotEmpty()) {
            item(key = "header_collections") {
                SectionHeader(stringResource(Res.string.search_section_collections), ADAPTIVE_SEARCH_COLLECTIONS_TAG)
            }
            items(groups.collections, key = { "collection_${it.contentType.ftsValue}_${it.uid}" }) { result ->
                CollectionRow(result, details[result.key], onResultClick, onResultLongClick)
            }
        }
        if (groups.days.isNotEmpty()) {
            item(key = "header_days") {
                SectionHeader(stringResource(Res.string.search_section_days), ADAPTIVE_SEARCH_DAYS_TAG)
            }
            items(groups.days, key = { "day_${it.date}" }) { day ->
                DayCard(day, details, onResultClick, onResultLongClick, onOpenDay)
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.sm)
                .testTag(tag),
    )
}

@Composable
private fun EntityRow(
    result: SearchResult,
    detail: SearchResultDetail?,
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
    ResultRow(
        result = result,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = {
            val cover = (detail as? SearchResultDetail.Journal)?.coverUri ?: (detail as? SearchResultDetail.Person)?.photoUri
            Cover(
                uri = cover,
                icon = state.typeIcon,
                shape =
                    if (result.contentType ==
                        SearchContentType.JOURNAL
                    ) {
                        RoundedCornerShape(10.dp)
                    } else {
                        CircleShape
                    },
                size = 44.dp,
            )
        },
        title = state.contentText,
        supporting = supporting,
        typeLabel = stringResource(state.typeLabelKey),
    )
}

@Composable
private fun CollectionRow(
    result: SearchResult,
    detail: SearchResultDetail?,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    val state = result.toUniversalSearchResultUiState(rememberSearchHighlightStyle())
    val (cover, supporting) =
        when (detail) {
            is SearchResultDetail.Rewind ->
                detail.coverUri to
                    stringResource(
                        Res.string.search_date_range,
                        detail.start
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                            .toReadableDateShort(),
                        detail.end
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                            .toReadableDateShort(),
                    )
            is SearchResultDetail.Postcard ->
                detail.coverUri to
                    detail.modifiedAt
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .date
                        .toReadableDateShort()
            else -> null to state.createdReadable
        }
    ResultRow(
        result = result,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = { Cover(uri = cover, icon = state.typeIcon, shape = RoundedCornerShape(10.dp), size = 64.dp, width = 92.dp) },
        title = state.contentText,
        supporting = supporting,
        typeLabel = stringResource(state.typeLabelKey),
    )
}

@Composable
private fun ResultRow(
    result: SearchResult,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
    leading: @Composable () -> Unit,
    title: AnnotatedString,
    supporting: String?,
    typeLabel: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm)
                .clip(RoundedCornerShape(16.dp))
                .focusableWithRing()
                .combinedClickable(onClick = { onClick(result) }, onLongClick = { onLongClick(result) })
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Cover(
    uri: String?,
    icon: ImageVector,
    shape: Shape,
    size: Dp,
    width: Dp = size,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(width = width, height = size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = width, height = size),
            )
        } else {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun DayCard(
    day: SearchDayGroup,
    details: Map<SearchResultKey, SearchResultDetail>,
    onResultClick: (SearchResult) -> Unit,
    onResultLongClick: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
) {
    val highlight = rememberSearchHighlightStyle()
    val thumbnails = day.results.filter { details[it.key].hasArt() }.take(MAX_THUMBNAILS_PER_DAY)
    val lines = day.results.filterNot { details[it.key].hasArt() }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm).testTag("adaptive_search_day_${day.date}"),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .focusableWithRing()
                    .combinedClickable(onClick = { onOpenDay(day.results.first()) })
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Text(text = formatDateLocalized(day.date), style = MaterialTheme.typography.titleSmall)
            Text(
                text = pluralStringResource(Res.plurals.search_day_match_count, day.results.size, day.results.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (thumbnails.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(horizontal = Spacing.md),
            ) {
                thumbnails.forEach { result ->
                    DayThumbnail(result, details[result.key], onResultClick, onResultLongClick)
                }
            }
        }
        lines.take(MAX_LINES_PER_DAY).forEach { result ->
            DayLine(result, details[result.key], highlight, onResultClick, onResultLongClick)
        }
        if (lines.size > MAX_LINES_PER_DAY) {
            Text(
                text = stringResource(Res.string.search_more_in_day, lines.size - MAX_LINES_PER_DAY),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
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
    val fit = if (detail is SearchResultDetail.Sticker) ContentScale.Fit else ContentScale.Crop
    Box(
        modifier =
            Modifier
                .size(width = 84.dp, height = 64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .focusableWithRing()
                .semantics { contentDescription = result.content }
                .combinedClickable(onClick = { onClick(result) }, onLongClick = { onLongClick(result) }),
    ) {
        AsyncImage(model = uri, contentDescription = null, contentScale = fit, modifier = Modifier.size(width = 84.dp, height = 64.dp))
    }
}

@Composable
private fun DayLine(
    result: SearchResult,
    detail: SearchResultDetail?,
    highlight: SpanStyle,
    onClick: (SearchResult) -> Unit,
    onLongClick: (SearchResult) -> Unit,
) {
    val state = result.toUniversalSearchResultUiState(highlight)
    val durationMs = (detail as? SearchResultDetail.Audio)?.durationMs
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .focusableWithRing()
                .combinedClickable(onClick = { onClick(result) }, onLongClick = { onLongClick(result) })
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Icon(
            imageVector = state.typeIcon,
            contentDescription = stringResource(state.typeLabelKey),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = state.contentText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (durationMs != null && durationMs > 0) {
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SearchResultDetail?.hasArt(): Boolean =
    this is SearchResultDetail.Image || this is SearchResultDetail.Video || this is SearchResultDetail.Sticker

/** Formats a length in milliseconds as minutes and seconds, such as `0:48`. */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
