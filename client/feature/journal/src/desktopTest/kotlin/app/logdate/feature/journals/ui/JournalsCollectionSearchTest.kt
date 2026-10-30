@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.domain.search.SearchJournalsUseCase
import app.logdate.client.domain.search.SearchQuery
import app.logdate.feature.journals.ui.detail.FakeDetailJournalRepository
import app.logdate.shared.model.Journal
import app.logdate.ui.adaptive.ActivityRouteFrame
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class JournalsCollectionSearchTest {
    @Test
    fun standaloneCollectionHasOneContextualSearchThatFiltersJournals() = verifyContextualSearch(false)

    @Test
    fun relatedCollectionKeepsItsSearchOutsideTheContentPanes() = verifyContextualSearch(true)

    @Test
    fun homeRetainsOneUnifiedSearchInsteadOfASecondCollectionField() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            var unifiedSearches = 0
            setContent {
                LogDateTheme {
                    WorkspaceScaffold(
                        listOf(WorkspaceDestination("journals", "Journals", Icons.Default.Book)),
                        "journals",
                        {},
                        onSearch = { unifiedSearches++ },
                    ) { Overview("", {}) }
                }
            }
            onAllNodesWithTag("workspace_search").assertCountEquals(1)
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            onNodeWithTag("workspace_search").performClick()
            assertEquals(1, unifiedSearches)
        }

    private fun verifyContextualSearch(related: Boolean) =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalWorkspaceEnabled provides true) {
                        var query by remember { mutableStateOf("") }
                        ActivityRouteFrame(composePanels = !related) {
                            if (related) {
                                Row(Modifier.fillMaxSize()) {
                                    Overview(query, { query = it }, Modifier.weight(1f))
                                    Text("Opened journal", Modifier.weight(1f))
                                }
                            } else {
                                Overview(query, { query = it })
                            }
                        }
                    }
                }
            }
            onAllNodesWithTag("workspace_search").assertCountEquals(1)
            onAllNodes(hasSetTextAction()).assertCountEquals(1)
            onAllNodesWithTag("unexpected_home_host").assertCountEquals(0)
            val searchBounds = onNodeWithTag("workspace_search").fetchSemanticsNode().boundsInRoot
            val collectionBounds = onNodeWithTag("journals_collection").fetchSemanticsNode().boundsInRoot
            assertTrue(searchBounds.bottom <= collectionBounds.top)
            onNodeWithTag("workspace_search").performTextInput("Travel")
            onNodeWithText("Travel notes").assertExists()
            onNodeWithText("Work notes").assertDoesNotExist()
        }

    @Composable
    private fun Overview(
        query: String,
        onQuery: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        if (LocalWorkspaceHosted.current) Box(Modifier.testTag("unexpected_home_host"))
        val journals = remember { listOf(Journal(title = "Travel notes"), Journal(title = "Work notes")) }
        val search = remember { SearchJournalsUseCase(FakeDetailJournalRepository(journals)) }
        JournalsOverviewScreenContent(
            journals = search.filterJournals(journals, SearchQuery(query)).map { JournalListItemUiState.ExistingJournal(it) },
            layoutMode = JournalLayoutMode.GRID,
            sortOption = JournalSortOption.TITLE,
            searchQuery = query,
            entryResults = emptyList(),
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onNavigateToDay = {},
            onQueryChange = onQuery,
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
            modifier = modifier.testTag("journals_collection"),
        )
    }
}
