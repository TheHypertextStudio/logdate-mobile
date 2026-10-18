@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import logdate.client.feature.search.generated.resources.Res
import logdate.client.feature.search.generated.resources.search_entry_count
import logdate.client.feature.search.generated.resources.search_preview_empty
import logdate.client.feature.search.generated.resources.search_preview_open
import logdate.client.feature.search.generated.resources.search_preview_open_day
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

const val SEARCH_PREVIEW_TAG = "search_preview"
const val SEARCH_PREVIEW_TITLE_TAG = "search_preview_title"
const val SEARCH_PREVIEW_OPEN_TAG = "search_preview_open"

/** What the adaptive search body can do on the person's behalf. */
class AdaptiveSearchActions(
    val onResultClick: (SearchResult) -> Unit,
    val onResultLongClick: (SearchResult) -> Unit,
    val onOpenDay: (SearchResult) -> Unit,
    val onToggleType: (SearchContentType) -> Unit,
    val onTogglePerson: (Uuid) -> Unit,
    val onTogglePlace: (Uuid) -> Unit,
    val onToggleYear: (Int) -> Unit,
    val onClearNarrowing: () -> Unit,
)

private val SelectionSaver =
    Saver<SearchSelection?, String>(
        save = { encodeSearchSelection(it) },
        restore = { decodeSearchSelection(it) },
    )

/**
 * The adaptive presentation of search results: the narrowing options, the results and a preview of
 * the chosen result, placed in the workspace's panels for the space and fold available.
 *
 * Where a preview shows, choosing a result selects it for the preview and the person opens it from
 * there. Where it does not, choosing a result opens it straight away. The selection is kept through
 * resizing, folding and recreation, and falls back to the first result when the selected result is
 * no longer in the results.
 *
 * @param dayPreview What a selected day looks like. When the host has no day content, a summary of
 *   the day's matches is shown.
 */
@Composable
fun AdaptiveSearchBody(
    results: List<SearchResult>,
    details: Map<SearchResultKey, SearchResultDetail>,
    narrowing: SearchNarrowingOptions,
    actions: AdaptiveSearchActions,
    modifier: Modifier = Modifier,
    dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)? = null,
) {
    val zone = TimeZone.currentSystemDefault()
    val groups = remember(results, zone) { groupSearchResults(results, zone) }
    var chosen by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf<SearchSelection?>(null) }
    val selection = chosen?.takeIf { groups.contains(it) } ?: groups.firstSelection(zone)

    SearchWorkspaceLayout(
        modifier = modifier.fillMaxSize(),
        narrowing = { layout ->
            SearchNarrowing(
                options = narrowing,
                layout = layout,
                onToggleType = actions.onToggleType,
                onTogglePerson = actions.onTogglePerson,
                onTogglePlace = actions.onTogglePlace,
                onToggleYear = actions.onToggleYear,
                onClear = actions.onClearNarrowing,
            )
        },
        results = { panels ->
            val previewing = panels != SearchPanels.ResultsOnly
            AdaptiveSearchResults(
                groups = groups,
                details = details,
                onResultClick = { result ->
                    if (previewing) chosen = searchSelectionFor(result, zone) else actions.onResultClick(result)
                },
                onResultLongClick = actions.onResultLongClick,
                onOpenDay = { result ->
                    if (previewing) {
                        chosen = (searchSelectionFor(result, zone) as? SearchSelection.Day)?.copy(highlight = null)
                    } else {
                        actions.onOpenDay(result)
                    }
                },
                selected = selection.takeIf { previewing },
                modifier = Modifier.fillMaxSize(),
            )
        },
        preview = {
            SearchPreview(
                selection = selection,
                groups = groups,
                details = details,
                onOpenResult = actions.onResultClick,
                onOpenDay = actions.onOpenDay,
                dayPreview = dayPreview,
            )
        },
    )
}

/** The preview of the chosen result: the host's day content for a day, otherwise a summary. */
@Composable
private fun SearchPreview(
    selection: SearchSelection?,
    groups: SearchResultGroups,
    details: Map<SearchResultKey, SearchResultDetail>,
    onOpenResult: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
    dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)?,
) {
    Column(Modifier.fillMaxSize().testTag(SEARCH_PREVIEW_TAG)) {
        when (selection) {
            null -> Text(stringResource(Res.string.search_preview_empty), modifier = Modifier.padding(Spacing.lg))
            is SearchSelection.Day ->
                if (dayPreview != null) {
                    dayPreview(selection.date, selection.highlight)
                } else {
                    DaySummary(
                        selection.date,
                        groups.days
                            .firstOrNull { it.date == selection.date }
                            ?.results
                            .orEmpty(),
                        onOpenResult,
                        onOpenDay,
                    )
                }
            is SearchSelection.Result ->
                (groups.topMatches + groups.collections).firstOrNull { it.key == selection.key }?.let { result ->
                    ResultSummary(result, details[result.key], onOpenResult)
                }
        }
    }
}

@Composable
private fun ResultSummary(
    result: SearchResult,
    detail: SearchResultDetail?,
    onOpen: (SearchResult) -> Unit,
) {
    val state = result.toUniversalSearchResultUiState(rememberSearchHighlightStyle())
    val supporting =
        when (detail) {
            is SearchResultDetail.Journal -> pluralStringResource(Res.plurals.search_entry_count, detail.entryCount, detail.entryCount)
            is SearchResultDetail.Person -> detail.relationshipLabel
            else -> state.createdReadable
        }
    val cover =
        when (detail) {
            is SearchResultDetail.Journal -> detail.coverUri
            is SearchResultDetail.Rewind -> detail.coverUri
            is SearchResultDetail.Postcard -> detail.coverUri
            is SearchResultDetail.Person -> detail.photoUri
            else -> null
        }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Cover(result.contentType, cover, state.typeIcon, stringResource(state.typeLabelKey), size = 96.dp)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.weight(1f)) {
                Text(
                    state.contentText,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag(SEARCH_PREVIEW_TITLE_TAG),
                )
                if (!supporting.isNullOrBlank()) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = { onOpen(result) }, modifier = Modifier.testTag(SEARCH_PREVIEW_OPEN_TAG)) {
            Text(stringResource(Res.string.search_preview_open))
        }
    }
}

@Composable
private fun DaySummary(
    date: LocalDate,
    matches: List<SearchResult>,
    onOpenResult: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
) {
    val highlight = rememberSearchHighlightStyle()
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
    ) {
        Text(
            formatDateLocalized(date),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag(SEARCH_PREVIEW_TITLE_TAG),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            matches.forEach { result ->
                val state = result.toUniversalSearchResultUiState(highlight)
                ListItem(
                    headlineContent = { Text(state.contentText) },
                    supportingContent = { Text(stringResource(state.typeLabelKey)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.focusableWithRing().clickable { onOpenResult(result) },
                )
            }
        }
        matches.firstOrNull()?.let { first ->
            Button(onClick = { onOpenDay(first) }, modifier = Modifier.testTag(SEARCH_PREVIEW_OPEN_TAG)) {
                Text(stringResource(Res.string.search_preview_open_day))
            }
        }
    }
}
