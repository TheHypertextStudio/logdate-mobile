@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchEntryActivity
import app.logdate.client.repository.search.SearchPreviewEntry
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.client.repository.search.key
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.search.rememberSearchHighlightStyle
import app.logdate.ui.search.toUniversalSearchResultUiState
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import app.logdate.util.toReadableDateShort
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.search.generated.resources.Res
import logdate.client.feature.search.generated.resources.search_date_range
import logdate.client.feature.search.generated.resources.search_entry_count
import logdate.client.feature.search.generated.resources.search_preview_by_year
import logdate.client.feature.search.generated.resources.search_preview_changed
import logdate.client.feature.search.generated.resources.search_preview_day_count
import logdate.client.feature.search.generated.resources.search_preview_empty
import logdate.client.feature.search.generated.resources.search_preview_first_written
import logdate.client.feature.search.generated.resources.search_preview_highlights
import logdate.client.feature.search.generated.resources.search_preview_last_written
import logdate.client.feature.search.generated.resources.search_preview_latest_entries
import logdate.client.feature.search.generated.resources.search_preview_narrow_person
import logdate.client.feature.search.generated.resources.search_preview_narrow_place
import logdate.client.feature.search.generated.resources.search_preview_open
import logdate.client.feature.search.generated.resources.search_preview_open_day
import logdate.client.feature.search.generated.resources.search_preview_people
import logdate.client.feature.search.generated.resources.search_preview_place
import logdate.client.feature.search.generated.resources.search_preview_recent_entries
import logdate.client.feature.search.generated.resources.search_preview_recent_mentions
import logdate.client.feature.search.generated.resources.search_preview_started
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

const val SEARCH_PREVIEW_TAG = "search_preview"
const val SEARCH_PREVIEW_TITLE_TAG = "search_preview_title"
const val SEARCH_PREVIEW_OPEN_TAG = "search_preview_open"
const val SEARCH_PREVIEW_PERIOD_TAG = "search_preview_period"
const val SEARCH_PREVIEW_ENTRIES_TAG = "search_preview_entries"
const val SEARCH_PREVIEW_ACTIVITY_TAG = "search_preview_activity"
const val SEARCH_PREVIEW_YEARS_TAG = "search_preview_years"
const val SEARCH_PREVIEW_NARROW_TAG = "search_preview_narrow"

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
 * @param placeMap Draws a map of a named place at a latitude and longitude. Where the host has none, a place
 *   previews without a map.
 * @param knownPreviews Previews already read, shown without asking [loadPreview] again.
 * @param loadPreview Reads what the preview of a journal or rewind shows beyond its row. It is only
 *   asked where a preview shows, and a result it cannot read previews as its summary alone.
 */
@Composable
fun AdaptiveSearchBody(
    results: List<SearchResult>,
    details: Map<SearchResultKey, SearchResultDetail>,
    narrowing: SearchNarrowingOptions,
    actions: AdaptiveSearchActions,
    modifier: Modifier = Modifier,
    dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)? = null,
    loadPreview: suspend (SearchResultKey) -> SearchResultPreview? = { null },
    knownPreviews: Map<SearchResultKey, SearchResultPreview> = emptyMap(),
    placeMap: (@Composable (name: String, latitude: Double, longitude: Double, modifier: Modifier) -> Unit)? = null,
) {
    val previews = remember(knownPreviews) { mutableStateMapOf<SearchResultKey, SearchResultPreview>().apply { putAll(knownPreviews) } }
    val zone = TimeZone.currentSystemDefault()
    val groups = remember(results, zone) { groupSearchResults(results, zone) }
    var chosen by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf<SearchSelection?>(null) }
    val selection = chosen?.takeIf { groups.contains(it) } ?: groups.firstSelection(zone)

    SearchTypography {
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
                    loadPreview = loadPreview,
                    previews = previews,
                    actions = actions,
                    placeMap = placeMap,
                )
            },
        )
    }
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
    loadPreview: suspend (SearchResultKey) -> SearchResultPreview?,
    previews: MutableMap<SearchResultKey, SearchResultPreview>,
    actions: AdaptiveSearchActions,
    placeMap: (@Composable (String, Double, Double, Modifier) -> Unit)?,
) {
    Column(Modifier.fillMaxSize().testTag(SEARCH_PREVIEW_TAG)) {
        when (selection) {
            null -> {
                Text(stringResource(Res.string.search_preview_empty), modifier = Modifier.padding(Spacing.lg))
            }

            is SearchSelection.Day -> {
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
            }

            is SearchSelection.Result -> {
                (groups.topMatches + groups.collections).firstOrNull { it.key == selection.key }?.let { result ->
                    LaunchedEffect(result.key) {
                        if (result.key in previews) return@LaunchedEffect
                        try {
                            loadPreview(result.key)?.let { previews[result.key] = it }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Napier.w("Could not load the preview of ${result.key}", error)
                        }
                    }
                    ResultSummary(result, details[result.key], previews[result.key], actions, placeMap)
                }
            }
        }
    }
}

@Composable
private fun ResultSummary(
    result: SearchResult,
    detail: SearchResultDetail?,
    preview: SearchResultPreview?,
    actions: AdaptiveSearchActions,
    placeMap: (@Composable (String, Double, Double, Modifier) -> Unit)?,
) {
    val state = result.toUniversalSearchResultUiState(rememberSearchHighlightStyle())
    val zone = TimeZone.currentSystemDefault()
    val supporting =
        when (detail) {
            is SearchResultDetail.Journal -> pluralStringResource(Res.plurals.search_entry_count, detail.entryCount, detail.entryCount)
            is SearchResultDetail.Person -> detail.relationshipLabel
            is SearchResultDetail.Place -> null
            else -> state.createdReadable
        }
    val period =
        when (detail) {
            is SearchResultDetail.Rewind -> {
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
            }

            is SearchResultDetail.Postcard -> {
                stringResource(
                    Res.string.search_preview_changed,
                    detail.modifiedAt
                        .toLocalDateTime(zone)
                        .date
                        .toReadableDateShort(),
                )
            }

            else -> {
                null
            }
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
                if (period != null) {
                    Text(
                        period,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(SEARCH_PREVIEW_PERIOD_TAG),
                    )
                } else if (!supporting.isNullOrBlank()) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (result.contentType != SearchContentType.PLACE) {
                Button(onClick = { actions.onResultClick(result) }, modifier = Modifier.testTag(SEARCH_PREVIEW_OPEN_TAG)) {
                    Text(stringResource(Res.string.search_preview_open))
                }
            }
            when (result.contentType) {
                SearchContentType.PLACE -> {
                    FilledTonalButton(
                        onClick = { actions.onTogglePlace(result.uid) },
                        modifier = Modifier.testTag(SEARCH_PREVIEW_NARROW_TAG),
                    ) {
                        Text(stringResource(Res.string.search_preview_narrow_place))
                    }
                }

                SearchContentType.PERSON -> {
                    FilledTonalButton(
                        onClick = { actions.onTogglePerson(result.uid) },
                        modifier = Modifier.testTag(SEARCH_PREVIEW_NARROW_TAG),
                    ) {
                        Text(stringResource(Res.string.search_preview_narrow_person))
                    }
                }

                else -> {}
            }
        }
        when (preview) {
            is SearchResultPreview.Journal -> {
                JournalPreviewBody(preview, zone)
            }

            is SearchResultPreview.Rewind -> {
                RewindPreviewBody(preview)
            }

            is SearchResultPreview.Place -> {
                ActivityPreviewBody(
                    activity = preview.activity,
                    entries = preview.recentEntries,
                    entriesTitle = stringResource(Res.string.search_preview_recent_entries),
                    zone = zone,
                    onOpenEntry = { actions.onOpenDay(it.asEntryResult()) },
                    map =
                        (detail as? SearchResultDetail.Place)?.let { place ->
                            placeMap?.let { map -> { map(state.contentText.text, place.latitude, place.longitude, Modifier) } }
                        },
                )
            }

            is SearchResultPreview.Person -> {
                ActivityPreviewBody(
                    activity = preview.activity,
                    entries = preview.recentMentions,
                    entriesTitle = stringResource(Res.string.search_preview_recent_mentions),
                    zone = zone,
                    onOpenEntry = { actions.onOpenDay(it.asEntryResult()) },
                    map = null,
                )
            }

            null -> {}
        }
    }
}

/** A recent entry as the text note a day route can open. */
private fun SearchPreviewEntry.asEntryResult() =
    SearchResult(uid = uid, content = text, created = created, contentType = SearchContentType.TEXT_NOTE)

@Composable
private fun ActivityPreviewBody(
    activity: SearchEntryActivity,
    entries: List<SearchPreviewEntry>,
    entriesTitle: String,
    zone: TimeZone,
    onOpenEntry: (SearchPreviewEntry) -> Unit,
    map: (@Composable () -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (map != null) {
            Box(Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.large)) { map() }
        }
        Column(Modifier.testTag(SEARCH_PREVIEW_ACTIVITY_TAG), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                pluralStringResource(Res.plurals.search_entry_count, activity.entryCount, activity.entryCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(Res.plurals.search_preview_day_count, activity.dayCount, activity.dayCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activity.first?.let {
                Text(
                    stringResource(Res.string.search_preview_first_written, it.toLocalDateTime(zone).date.toReadableDateShort()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            activity.latest?.let {
                Text(
                    stringResource(Res.string.search_preview_last_written, it.toLocalDateTime(zone).date.toReadableDateShort()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (activity.byYear.isNotEmpty()) {
            PreviewSection(stringResource(Res.string.search_preview_by_year)) {
                Column(Modifier.testTag(SEARCH_PREVIEW_YEARS_TAG), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    activity.byYear.entries.sortedByDescending { it.key }.forEach { (year, count) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(year.toString(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            PreviewSection(entriesTitle) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    entries.forEach { entry ->
                        ListItem(
                            headlineContent = { Text(entry.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    entry.created
                                        .toLocalDateTime(zone)
                                        .date
                                        .toReadableDateShort(),
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.focusableWithRing().clickable { onOpenEntry(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalPreviewBody(
    preview: SearchResultPreview.Journal,
    zone: TimeZone,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (preview.description.isNotBlank()) {
            Text(preview.description, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            stringResource(
                Res.string.search_preview_started,
                preview.created
                    .toLocalDateTime(zone)
                    .date
                    .toReadableDateShort(),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preview.latestEntries.isNotEmpty()) {
            PreviewSection(stringResource(Res.string.search_preview_latest_entries)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().testTag(SEARCH_PREVIEW_ENTRIES_TAG),
                ) {
                    preview.latestEntries.forEach { entry ->
                        ListItem(
                            headlineContent = { Text(entry.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    entry.created
                                        .toLocalDateTime(zone)
                                        .date
                                        .toReadableDateShort(),
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RewindPreviewBody(preview: SearchResultPreview.Rewind) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (preview.highlights.isNotEmpty()) {
            PreviewSection(stringResource(Res.string.search_preview_highlights)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    preview.highlights.forEach { highlight ->
                        ListItem(
                            headlineContent = { Text(highlight, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        if (preview.people.isNotEmpty()) {
            PreviewSection(stringResource(Res.string.search_preview_people)) {
                Text(preview.people.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
            }
        }
        preview.place?.let { place ->
            PreviewSection(stringResource(Res.string.search_preview_place)) {
                Text(place, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PreviewSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
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
