@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.journals.JournalSuggestion
import app.logdate.client.repository.search.SearchResult
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.ui.workspace.WorkspaceSearchScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_april
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_august
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_december
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_february
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_january
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_july
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_june
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_march
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_may
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_november
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_october
import logdate.client.feature.journal.generated.resources.journal_suggestion_month_september
import logdate.client.feature.journal.generated.resources.journal_suggestion_reason
import logdate.client.feature.journal.generated.resources.journal_suggestion_title
import logdate.client.feature.journal.generated.resources.search_journals
import logdate.client.feature.journal.generated.resources.suggestion_create_journal
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Journals overview screen. Wires [JournalsOverviewViewModel] state to the stateless content.
 */
@Composable
fun JournalsOverviewScreen(
    onOpenJournal: JournalClickCallback,
    onBrowseJournals: () -> Unit,
    onCreateJournal: () -> Unit,
    onNavigateToDay: (LocalDate) -> Unit = {},
    onNavigationClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: JournalsOverviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entryResults by viewModel.entrySearchResults.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    JournalsOverviewScreenContent(
        journals = state.journals,
        layoutMode = state.layoutMode,
        sortOption = state.sortOption,
        searchQuery = state.searchQuery,
        isEntrySearchInProgress = state.isEntrySearchInProgress,
        entryResults = entryResults,
        suggestions = suggestions,
        onOpenJournal = onOpenJournal,
        onBrowseJournals = onBrowseJournals,
        onCreateJournal = onCreateJournal,
        onNavigateToDay = onNavigateToDay,
        onNavigationClick = onNavigationClick,
        onQueryChange = viewModel::updateSearchQuery,
        onToggleLayoutMode = viewModel::toggleLayoutMode,
        onSortOptionSelected = viewModel::setSortOption,
        modifier = modifier,
    )
}

/**
 * Stateless journals overview layout.
 *
 * Uses a transparent Scaffold so the shell's `surfaceContainer` background shows through
 * between the search toolbar and the [JournalListPanel] surface below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalsOverviewScreenContent(
    journals: List<JournalListItemUiState>,
    layoutMode: JournalLayoutMode,
    sortOption: JournalSortOption,
    searchQuery: String,
    isEntrySearchInProgress: Boolean = false,
    entryResults: List<SearchResult>,
    suggestions: List<JournalSuggestion> = emptyList(),
    onOpenJournal: JournalClickCallback,
    onBrowseJournals: () -> Unit,
    onCreateJournal: () -> Unit,
    onNavigateToDay: (LocalDate) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleLayoutMode: () -> Unit,
    onSortOptionSelected: (JournalSortOption) -> Unit,
    onNavigationClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (LocalWorkspaceEnabled.current) {
        if (!LocalWorkspaceHosted.current) {
            WorkspaceSearchScope(searchQuery, stringResource(Res.string.search_journals), onQueryChange)
        }
        WorkspacePanel(modifier) {
            Column(Modifier.fillMaxSize()) {
                if (suggestions.isNotEmpty()) SuggestionRow(suggestions, onCreateJournal)
                JournalListPanel(
                    journals,
                    layoutMode,
                    sortOption,
                    onOpenJournal,
                    onBrowseJournals,
                    onCreateJournal,
                    onToggleLayoutMode,
                    onSortOptionSelected,
                    Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        return
    }
    FoldableBookLayout(
        modifier = modifier,
        minPaneWidth = 320.dp,
        startPane = {
            Column(modifier = Modifier.fillMaxSize()) {
                JournalSearchToolbar(
                    searchQuery = searchQuery,
                    isEntrySearchInProgress = isEntrySearchInProgress,
                    filteredJournals = journals,
                    entryResults = entryResults,
                    onQueryChange = onQueryChange,
                    onOpenJournal = onOpenJournal,
                    onNavigateToDay = onNavigateToDay,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                )

                if (suggestions.isNotEmpty()) {
                    SuggestionRow(
                        suggestions = suggestions,
                        onCreateJournal = onCreateJournal,
                    )
                }
            }
        },
        endPane = {
            JournalListPanel(
                journals = journals,
                layoutMode = layoutMode,
                sortOption = sortOption,
                onOpenJournal = onOpenJournal,
                onBrowseJournals = onBrowseJournals,
                onCreateJournal = onCreateJournal,
                onToggleLayoutMode = onToggleLayoutMode,
                onSortOptionSelected = onSortOptionSelected,
                showLoading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
        standardContent = {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    JournalSearchToolbar(
                        searchQuery = searchQuery,
                        isEntrySearchInProgress = isEntrySearchInProgress,
                        filteredJournals = journals,
                        entryResults = entryResults,
                        onQueryChange = onQueryChange,
                        onOpenJournal = onOpenJournal,
                        onNavigateToDay = onNavigateToDay,
                        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    )
                },
            ) { paddingValues ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(top = Spacing.sm),
                ) {
                    if (suggestions.isNotEmpty()) {
                        SuggestionRow(
                            suggestions = suggestions,
                            onCreateJournal = onCreateJournal,
                        )
                    }

                    JournalListPanel(
                        journals = journals,
                        layoutMode = layoutMode,
                        sortOption = sortOption,
                        onOpenJournal = onOpenJournal,
                        onBrowseJournals = onBrowseJournals,
                        onCreateJournal = onCreateJournal,
                        onToggleLayoutMode = onToggleLayoutMode,
                        onSortOptionSelected = onSortOptionSelected,
                        showLoading = false,
                        modifier = Modifier.fillMaxSize().weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun SuggestionRow(
    suggestions: List<JournalSuggestion>,
    onCreateJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(suggestions) { suggestion ->
            SuggestionCard(
                suggestion = suggestion,
                onAccept = onCreateJournal,
            )
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: JournalSuggestion,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text =
                    stringResource(
                        Res.string.journal_suggestion_title,
                        stringResource(suggestion.weekOf.month.suggestionName),
                        suggestion.weekOf.day,
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = pluralStringResource(Res.plurals.journal_suggestion_reason, suggestion.noteCount, suggestion.noteCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            OutlinedButton(onClick = onAccept) {
                Text(stringResource(Res.string.suggestion_create_journal))
            }
        }
    }
}

private val Month.suggestionName: StringResource
    get() =
        when (this) {
            Month.JANUARY -> Res.string.journal_suggestion_month_january
            Month.FEBRUARY -> Res.string.journal_suggestion_month_february
            Month.MARCH -> Res.string.journal_suggestion_month_march
            Month.APRIL -> Res.string.journal_suggestion_month_april
            Month.MAY -> Res.string.journal_suggestion_month_may
            Month.JUNE -> Res.string.journal_suggestion_month_june
            Month.JULY -> Res.string.journal_suggestion_month_july
            Month.AUGUST -> Res.string.journal_suggestion_month_august
            Month.SEPTEMBER -> Res.string.journal_suggestion_month_september
            Month.OCTOBER -> Res.string.journal_suggestion_month_october
            Month.NOVEMBER -> Res.string.journal_suggestion_month_november
            Month.DECEMBER -> Res.string.journal_suggestion_month_december
        }
