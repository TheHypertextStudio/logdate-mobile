@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.logdate.feature.search.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.search.DateRangeFilter
import app.logdate.client.domain.search.SearchFilters
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.handwritingDelegate
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.PlatformSheet
import app.logdate.ui.platform.plainTextClipEntry
import app.logdate.ui.search.SearchBarMaxWidth
import app.logdate.ui.search.UniversalSearchResultItem
import app.logdate.ui.search.UniversalSearchResultUiState
import app.logdate.ui.search.rememberSearchHighlightStyle
import app.logdate.ui.search.searchBarMaxWidth
import app.logdate.ui.search.toUniversalSearchResultUiState
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import logdate.client.feature.search.generated.resources.Res
import logdate.client.feature.search.generated.resources.clear_search
import logdate.client.feature.search.generated.resources.search
import logdate.client.feature.search.generated.resources.search_action_actions_for
import logdate.client.feature.search.generated.resources.search_action_copy_text
import logdate.client.feature.search.generated.resources.search_action_open_day
import logdate.client.feature.search.generated.resources.search_action_share
import logdate.client.feature.search.generated.resources.search_bucket_earlier
import logdate.client.feature.search.generated.resources.search_bucket_this_month
import logdate.client.feature.search.generated.resources.search_bucket_this_week
import logdate.client.feature.search.generated.resources.search_bucket_today
import logdate.client.feature.search.generated.resources.search_bucket_yesterday
import logdate.client.feature.search.generated.resources.search_empty_clear_filters
import logdate.client.feature.search.generated.resources.search_entries
import logdate.client.feature.search.generated.resources.search_filter_clear
import logdate.client.feature.search.generated.resources.search_filter_date_all_time
import logdate.client.feature.search.generated.resources.search_filter_date_this_month
import logdate.client.feature.search.generated.resources.search_filter_date_this_week
import logdate.client.feature.search.generated.resources.search_filter_date_this_year
import logdate.client.feature.search.generated.resources.search_filter_date_today
import logdate.client.feature.search.generated.resources.search_filter_type_journals
import logdate.client.feature.search.generated.resources.search_filter_type_notes
import logdate.client.feature.search.generated.resources.search_filter_type_people
import logdate.client.feature.search.generated.resources.search_filter_type_photos
import logdate.client.feature.search.generated.resources.search_filter_type_places
import logdate.client.feature.search.generated.resources.search_filter_type_postcards
import logdate.client.feature.search.generated.resources.search_filter_type_rewinds
import logdate.client.feature.search.generated.resources.search_filter_type_soundscapes
import logdate.client.feature.search.generated.resources.search_filter_type_stickers
import logdate.client.feature.search.generated.resources.search_filter_type_voice_notes
import logdate.client.feature.search.generated.resources.search_for_entries
import logdate.client.feature.search.generated.resources.search_no_results
import logdate.client.feature.search.generated.resources.search_recent_clear_all
import logdate.client.feature.search.generated.resources.search_recent_remove
import logdate.client.feature.search.generated.resources.searching_entries
import logdate.client.ui.generated.resources.common_go_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

const val SEARCH_SCREEN_INPUT_ACCESSIBILITY_TAG = "search_screen_input"

/**
 * Universal search screen.
 *
 * Searches the content types included in this release. Shows recent searches when idle.
 */
@Composable
fun SearchScreen(
    onResultClick: (SearchResult) -> Unit,
    onResultOpenDay: (SearchResult) -> Unit,
    onGoBack: () -> Unit,
    onShareResult: ((SearchResult) -> Unit)? = null,
    initialQuery: String = "",
    initialTypeFtsValues: List<String> = emptyList(),
    initialDateRangeName: String = "",
    adaptive: Boolean = false,
    showPeople: Boolean = false,
    dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val searchState by viewModel.searchState.collectAsState()
    val resultDetails by viewModel.resultDetails.collectAsState()
    val narrowing by viewModel.narrowing.collectAsState()
    val narrowingOptions = remember(narrowing, showPeople) { buildNarrowingOptions(narrowing, showPeople) }
    val queryText by viewModel.queryText.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val onRemoveRecent: (String) -> Unit = viewModel::removeRecentSearch
    val onClearAllRecents: () -> Unit = viewModel::clearRecentSearches

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && queryText.isBlank()) {
            viewModel.updateQuery(initialQuery)
        }
    }
    LaunchedEffect(initialTypeFtsValues, initialDateRangeName) {
        if (filters == SearchFilters.Default) {
            initialTypeFtsValues
                .mapNotNull { fts -> SearchContentType.entries.firstOrNull { it.ftsValue == fts } }
                .forEach { viewModel.toggleContentType(it) }
            DateRangeFilter.entries
                .firstOrNull { it.name == initialDateRangeName }
                ?.let { viewModel.setDateRange(it) }
        }
    }

    SearchScreenContent(
        searchState = searchState,
        queryText = queryText,
        initialQuery = initialQuery,
        filters = filters,
        onQueryChange = viewModel::updateQuery,
        onCommitSearch = viewModel::commitSearch,
        onToggleType = viewModel::toggleContentType,
        onSetDateRange = viewModel::setDateRange,
        onClearFilters = viewModel::clearFilters,
        onRemoveRecent = onRemoveRecent,
        onClearAllRecents = onClearAllRecents,
        onResultClick = onResultClick,
        onResultOpenDay = onResultOpenDay,
        onShareResult = onShareResult,
        onGoBack = onGoBack,
        adaptive = adaptive,
        resultDetails = resultDetails,
        narrowingOptions = narrowingOptions,
        onTogglePerson = viewModel::togglePerson,
        onTogglePlace = viewModel::togglePlace,
        onToggleYear = viewModel::toggleYear,
        dayPreview = dayPreview,
        loadPreview = viewModel::loadPreview,
        modifier = modifier,
    )
}

/**
 * Stateless content for the universal search screen.
 *
 * Renders an MD3 SearchBar that auto-expands on entry. Shows recent searches
 * when idle, a "no results" message when empty, or a ranked list of
 * [UniversalSearchResultItem]s when results are available.
 */
@Composable
fun SearchScreenContent(
    searchState: SearchScreenState,
    onQueryChange: (String) -> Unit,
    onCommitSearch: () -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onResultOpenDay: (SearchResult) -> Unit,
    onGoBack: () -> Unit,
    initialQuery: String = "",
    queryText: String = initialQuery,
    filters: SearchFilters = SearchFilters.Default,
    onToggleType: (SearchContentType) -> Unit = {},
    onSetDateRange: (DateRangeFilter) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onRemoveRecent: (String) -> Unit = {},
    onClearAllRecents: () -> Unit = {},
    onShareResult: ((SearchResult) -> Unit)? = null,
    adaptive: Boolean = false,
    resultDetails: Map<SearchResultKey, SearchResultDetail> = emptyMap(),
    narrowingOptions: SearchNarrowingOptions = SearchNarrowingOptions(),
    onTogglePerson: (Uuid) -> Unit = {},
    onTogglePlace: (Uuid) -> Unit = {},
    onToggleYear: (Int) -> Unit = {},
    dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)? = null,
    loadPreview: suspend (SearchResultKey) -> SearchResultPreview? = { null },
    modifier: Modifier = Modifier,
) {
    val searchBarState = rememberSearchBarState()
    val textFieldState =
        rememberSaveable(saver = TextFieldState.Saver) {
            TextFieldState(initialText = initialQuery)
        }
    var sheetTarget by remember { mutableStateOf<SearchResult?>(null) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        searchBarState.animateToExpanded()
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collectLatest { onQueryChange(it) }
    }

    LaunchedEffect(queryText) {
        val currentText = textFieldState.text.toString()
        if (queryText != currentText) {
            textFieldState.edit {
                replace(0, length, queryText)
            }
        }
    }

    Box(modifier = modifier) {
        SearchBar(
            state = searchBarState,
            inputField = {
                SearchBarDefaults.InputField(
                    searchBarState = searchBarState,
                    textFieldState = textFieldState,
                    onSearch = { onCommitSearch() },
                    placeholder = { Text(stringResource(Res.string.search_entries)) },
                    leadingIcon = {
                        Icon(
                            painter = PlatformIcons.search(),
                            contentDescription = stringResource(Res.string.search),
                        )
                    },
                )
            },
            modifier = Modifier.searchBarMaxWidth(),
        )

        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = {
                SearchBarDefaults.InputField(
                    searchBarState = searchBarState,
                    textFieldState = textFieldState,
                    onSearch = { onCommitSearch() },
                    modifier =
                        Modifier
                            .handwritingDelegate()
                            .testTag(SEARCH_SCREEN_INPUT_ACCESSIBILITY_TAG)
                            .semantics {
                                contentDescription = SEARCH_SCREEN_INPUT_ACCESSIBILITY_TAG
                            },
                    placeholder = { Text(stringResource(Res.string.search_entries)) },
                    leadingIcon = {
                        IconButton(onClick = onGoBack) {
                            Icon(
                                painter = PlatformIcons.back(),
                                contentDescription = stringResource(UiRes.string.common_go_back),
                            )
                        }
                    },
                    trailingIcon = {
                        if (textFieldState.text.isNotEmpty()) {
                            IconButton(onClick = { textFieldState.clearText() }) {
                                Icon(
                                    painter = PlatformIcons.close(),
                                    contentDescription = stringResource(Res.string.clear_search),
                                )
                            }
                        }
                    },
                )
            },
        ) {
            SearchBodyContent(
                searchState = searchState,
                filters = filters,
                onToggleType = onToggleType,
                onSetDateRange = onSetDateRange,
                onClearFilters = onClearFilters,
                onQueryChange = onQueryChange,
                onCommitSearch = onCommitSearch,
                onRemoveRecent = onRemoveRecent,
                onClearAllRecents = onClearAllRecents,
                onResultClick = onResultClick,
                onLongClickResult = { result -> sheetTarget = result },
                onOpenDay = onResultOpenDay,
                adaptive = adaptive,
                resultDetails = resultDetails,
                narrowingOptions = narrowingOptions,
                onTogglePerson = onTogglePerson,
                onTogglePlace = onTogglePlace,
                onToggleYear = onToggleYear,
                dayPreview = dayPreview,
                loadPreview = loadPreview,
            )
        }
    }

    sheetTarget?.let { target ->
        ResultActionsSheet(
            result = target,
            onDismiss = { sheetTarget = null },
            onOpenDay = {
                onResultOpenDay(target)
                sheetTarget = null
            },
            onCopyText = {
                val text = target.content
                coroutineScope.launch { clipboard.setClipEntry(plainTextClipEntry(text)) }
                sheetTarget = null
            },
            onShare =
                onShareResult?.let {
                    {
                        it(target)
                        sheetTarget = null
                    }
                },
        )
    }
}

/**
 * Renders the body of the search screen below the SearchBar / FilterChipRow.
 */
@Composable
private fun SearchBodyContent(
    searchState: SearchScreenState,
    filters: SearchFilters,
    onToggleType: (SearchContentType) -> Unit,
    onSetDateRange: (DateRangeFilter) -> Unit,
    onClearFilters: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCommitSearch: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearAllRecents: () -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onLongClickResult: (SearchResult) -> Unit,
    onOpenDay: (SearchResult) -> Unit,
    adaptive: Boolean,
    resultDetails: Map<SearchResultKey, SearchResultDetail>,
    narrowingOptions: SearchNarrowingOptions,
    onTogglePerson: (Uuid) -> Unit,
    onTogglePlace: (Uuid) -> Unit,
    onToggleYear: (Int) -> Unit,
    dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)?,
    loadPreview: suspend (SearchResultKey) -> SearchResultPreview?,
) {
    if (adaptive && searchState is SearchScreenState.Results) {
        AdaptiveSearchBody(
            results = searchState.results,
            details = resultDetails,
            narrowing = narrowingOptions,
            actions =
                AdaptiveSearchActions(
                    onResultClick = { result ->
                        onCommitSearch()
                        onResultClick(result)
                    },
                    onResultLongClick = onLongClickResult,
                    onOpenDay = onOpenDay,
                    onToggleType = onToggleType,
                    onTogglePerson = onTogglePerson,
                    onTogglePlace = onTogglePlace,
                    onToggleYear = onToggleYear,
                    onClearNarrowing = onClearFilters,
                ),
            dayPreview = dayPreview,
            loadPreview = loadPreview,
        )
        return
    }
    FoldableBookLayout(
        modifier = Modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            if (adaptive) {
                SearchNarrowing(
                    options = narrowingOptions,
                    layout = SearchNarrowingLayout.Panel,
                    onToggleType = onToggleType,
                    onTogglePerson = onTogglePerson,
                    onTogglePlace = onTogglePlace,
                    onToggleYear = onToggleYear,
                    onClear = onClearFilters,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SearchContextPane(
                    searchState = searchState,
                    filters = filters,
                    onToggleType = onToggleType,
                    onSetDateRange = onSetDateRange,
                    onClearFilters = onClearFilters,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        endPane = {
            SearchStateContent(
                searchState = searchState,
                filters = filters,
                onQueryChange = onQueryChange,
                onCommitSearch = onCommitSearch,
                onClearFilters = onClearFilters,
                onRemoveRecent = onRemoveRecent,
                onClearAllRecents = onClearAllRecents,
                onResultClick = onResultClick,
                onLongClickResult = onLongClickResult,
            )
        },
        standardContent = {
            Column(modifier = Modifier.fillMaxSize()) {
                if (adaptive) {
                    SearchNarrowing(
                        options = narrowingOptions,
                        layout = SearchNarrowingLayout.Row,
                        onToggleType = onToggleType,
                        onTogglePerson = onTogglePerson,
                        onTogglePlace = onTogglePlace,
                        onToggleYear = onToggleYear,
                        onClear = onClearFilters,
                    )
                } else {
                    FilterChipRow(
                        filters = filters,
                        onToggleType = onToggleType,
                        onSetDateRange = onSetDateRange,
                        onClearFilters = onClearFilters,
                    )
                }
                SearchStateContent(
                    searchState = searchState,
                    filters = filters,
                    onQueryChange = onQueryChange,
                    onCommitSearch = onCommitSearch,
                    onClearFilters = onClearFilters,
                    onRemoveRecent = onRemoveRecent,
                    onClearAllRecents = onClearAllRecents,
                    onResultClick = onResultClick,
                    onLongClickResult = onLongClickResult,
                )
            }
        },
    )
}

@Composable
private fun SearchContextPane(
    searchState: SearchScreenState,
    filters: SearchFilters,
    onToggleType: (SearchContentType) -> Unit,
    onSetDateRange: (DateRangeFilter) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FilterChipRow(
            filters = filters,
            onToggleType = onToggleType,
            onSetDateRange = onSetDateRange,
            onClearFilters = onClearFilters,
        )
        SearchContextSummary(
            searchState = searchState,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
    }
}

@Composable
private fun SearchContextSummary(
    searchState: SearchScreenState,
    modifier: Modifier = Modifier,
) {
    val summary =
        when (searchState) {
            is SearchScreenState.Idle -> stringResource(Res.string.search_for_entries)
            is SearchScreenState.Searching -> stringResource(Res.string.searching_entries)
            is SearchScreenState.Empty -> stringResource(Res.string.search_no_results, searchState.query)
            is SearchScreenState.Results -> stringResource(Res.string.search_entries)
        }
    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Renders the body state for the search screen.
 */
@Composable
private fun SearchStateContent(
    searchState: SearchScreenState,
    filters: SearchFilters,
    onQueryChange: (String) -> Unit,
    onCommitSearch: () -> Unit,
    onClearFilters: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearAllRecents: () -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onLongClickResult: (SearchResult) -> Unit,
) {
    when (searchState) {
        is SearchScreenState.Idle -> {
            RecentSearchesList(
                recentSearches = searchState.recentSearches,
                onSelectRecent = { query -> onQueryChange(query) },
                onRemoveRecent = onRemoveRecent,
                onClearAllRecents = onClearAllRecents,
            )
        }

        is SearchScreenState.Searching -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = stringResource(Res.string.searching_entries),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is SearchScreenState.Empty -> {
            val anyFilterActive =
                filters.contentTypes != null || filters.dateRange != DateRangeFilter.AllTime
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
                modifier = Modifier.fillMaxSize().padding(Spacing.lg),
            ) {
                Text(
                    text = stringResource(Res.string.search_no_results, searchState.query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (anyFilterActive) {
                    AssistChip(
                        onClick = onClearFilters,
                        label = { Text(stringResource(Res.string.search_empty_clear_filters)) },
                        leadingIcon = {
                            Icon(
                                painter = PlatformIcons.filterOff(),
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }

        is SearchScreenState.Results -> {
            val highlightStyle = rememberSearchHighlightStyle()
            val groupedRows =
                remember(searchState.results, highlightStyle) {
                    bucketSearchResults(
                        results = searchState.results,
                        now = Clock.System.now(),
                        timeZone = TimeZone.currentSystemDefault(),
                    ).map { (bucket, items) ->
                        bucket to
                            items.map { result ->
                                SearchResultRow(
                                    result = result,
                                    uiState = result.toUniversalSearchResultUiState(highlightStyle),
                                )
                            }
                    }
                }
            // Cap reading width on tablets / desktop / unfolded foldables so result rows stay
            // scannable instead of stretching to a 1200dp line. Phones at compact width fall
            // under the cap and use the full screen.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = SearchBarMaxWidth),
                ) {
                    groupedRows.forEach { (bucket, bucketRows) ->
                        stickyHeader(key = "header_${bucket.name}") {
                            BucketHeader(bucket)
                        }
                        items(
                            items = bucketRows,
                            key = { it.uiState.id },
                        ) { resultRow ->
                            UniversalSearchResultItem(
                                state = resultRow.uiState,
                                onClick = {
                                    onCommitSearch()
                                    onResultClick(resultRow.result)
                                },
                                onLongClick = { onLongClickResult(resultRow.result) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class SearchResultRow(
    val result: SearchResult,
    val uiState: UniversalSearchResultUiState,
)

/**
 * Bottom-sheet menu of secondary actions for a search result, opened by long-press or right-
 * click. Share is gated on the host wiring up [onShare] (Android wires up an `ACTION_SEND`
 * intent in [`MainActivity`]; other platforms omit it).
 */
@Composable
private fun ResultActionsSheet(
    result: SearchResult,
    onDismiss: () -> Unit,
    onOpenDay: () -> Unit,
    onCopyText: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    PlatformSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.lg),
        ) {
            Text(
                text = stringResource(Res.string.search_action_actions_for),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.search_action_open_day)) },
                leadingContent = {
                    Icon(painter = PlatformIcons.calendar(), contentDescription = null)
                },
                modifier = Modifier.clickable { onOpenDay() },
            )
            if (result.content.isNotBlank()) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.search_action_copy_text)) },
                    leadingContent = {
                        Icon(painter = PlatformIcons.copy(), contentDescription = null)
                    },
                    modifier = Modifier.clickable { onCopyText() },
                )
            }
            if (onShare != null) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.search_action_share)) },
                    leadingContent = {
                        Icon(painter = PlatformIcons.share(), contentDescription = null)
                    },
                    modifier = Modifier.clickable { onShare() },
                )
            }
        }
    }
}

/**
 * Sticky section header above each [ResultDateBucket] in the result list.
 *
 * Tonal surface so it remains visible when sticky, and so successive headers don't visually
 * blend into the rows above and below them.
 */
@Composable
private fun BucketHeader(bucket: ResultDateBucket) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(bucket.labelKey()),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

private fun ResultDateBucket.labelKey(): StringResource =
    when (this) {
        ResultDateBucket.Today -> Res.string.search_bucket_today
        ResultDateBucket.Yesterday -> Res.string.search_bucket_yesterday
        ResultDateBucket.ThisWeek -> Res.string.search_bucket_this_week
        ResultDateBucket.ThisMonth -> Res.string.search_bucket_this_month
        ResultDateBucket.Earlier -> Res.string.search_bucket_earlier
    }

/**
 * Sticky filter chip row above the search results.
 *
 * Date-range chips are single-select; type chips are multi-select. A "Clear filters"
 * [AssistChip] only appears when at least one filter is active.
 */
@Composable
private fun FilterChipRow(
    filters: SearchFilters,
    onToggleType: (SearchContentType) -> Unit,
    onSetDateRange: (DateRangeFilter) -> Unit,
    onClearFilters: () -> Unit,
) {
    val anyFilterActive =
        filters.contentTypes != null || filters.dateRange != DateRangeFilter.AllTime
    val selectedColors =
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (anyFilterActive) {
            item(key = "clear") {
                AssistChip(
                    onClick = onClearFilters,
                    label = { Text(stringResource(Res.string.search_filter_clear)) },
                    leadingIcon = {
                        Icon(
                            painter = PlatformIcons.filterOff(),
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        items(items = DateRangeFilter.entries, key = { "date_${it.name}" }) { range ->
            FilterChip(
                selected = filters.dateRange == range,
                onClick = { onSetDateRange(range) },
                label = { Text(stringResource(range.labelKey())) },
                colors = selectedColors,
            )
        }
        items(items = SearchContentType.entries.filter { it in launchSearchContentTypes }, key = { "type_${it.ftsValue}" }) { type ->
            FilterChip(
                selected = filters.contentTypes?.contains(type) == true,
                onClick = { onToggleType(type) },
                label = { Text(stringResource(type.labelKey())) },
                colors = selectedColors,
            )
        }
    }
}

private fun DateRangeFilter.labelKey(): StringResource =
    when (this) {
        DateRangeFilter.AllTime -> Res.string.search_filter_date_all_time
        DateRangeFilter.Today -> Res.string.search_filter_date_today
        DateRangeFilter.ThisWeek -> Res.string.search_filter_date_this_week
        DateRangeFilter.ThisMonth -> Res.string.search_filter_date_this_month
        DateRangeFilter.ThisYear -> Res.string.search_filter_date_this_year
    }

internal fun SearchContentType.labelKey(): StringResource =
    when (this) {
        SearchContentType.TEXT_NOTE -> Res.string.search_filter_type_notes
        SearchContentType.TRANSCRIPTION -> Res.string.search_filter_type_voice_notes
        SearchContentType.JOURNAL -> Res.string.search_filter_type_journals
        SearchContentType.MEDIA_CAPTION -> Res.string.search_filter_type_photos
        SearchContentType.PLACE -> Res.string.search_filter_type_places
        SearchContentType.REWIND -> Res.string.search_filter_type_rewinds
        SearchContentType.STICKER -> Res.string.search_filter_type_stickers
        SearchContentType.POSTCARD -> Res.string.search_filter_type_postcards
        SearchContentType.AMBIENT_SOUND -> Res.string.search_filter_type_soundscapes
        SearchContentType.PERSON -> Res.string.search_filter_type_people
    }

@Composable
private fun RecentSearchesList(
    recentSearches: List<String>,
    onSelectRecent: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearAllRecents: () -> Unit,
) {
    if (recentSearches.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = stringResource(Res.string.search_for_entries),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "recents_clear_all") {
                Box(
                    contentAlignment = Alignment.CenterEnd,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                ) {
                    TextButton(onClick = onClearAllRecents) {
                        Text(stringResource(Res.string.search_recent_clear_all))
                    }
                }
            }
            items(items = recentSearches, key = { "recent_$it" }) { query ->
                ListItem(
                    headlineContent = { Text(query) },
                    leadingContent = {
                        Icon(painter = PlatformIcons.history(), contentDescription = null)
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemoveRecent(query) }) {
                            Icon(
                                painter = PlatformIcons.close(),
                                contentDescription =
                                    stringResource(Res.string.search_recent_remove),
                            )
                        }
                    },
                    modifier = Modifier.clickable { onSelectRecent(query) },
                )
            }
        }
    }
}
