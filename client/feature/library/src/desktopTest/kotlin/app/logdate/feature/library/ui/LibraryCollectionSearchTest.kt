package app.logdate.feature.library.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.adaptive.ActivityRouteFrame
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryCollectionSearchTest {
    @Test
    fun standaloneLibraryRetainsOneUnifiedSearchAction() = verifyCollectionSearch(false)

    @Test
    fun relatedLibraryRetainsSearchAboveItsMediaPanes() = verifyCollectionSearch(true)

    private fun verifyCollectionSearch(related: Boolean) =
        runDesktopComposeUiTest(width = 1000, height = 900) {
            var searches = 0
            setContent {
                LogDateTheme {
                    ActivityRouteFrame(composePanels = !related) {
                        assertFalse(LocalWorkspaceHosted.current)
                        if (related) {
                            Row(Modifier.fillMaxSize()) {
                                LibraryScreenContent(
                                    LibraryUiState.Empty,
                                    {},
                                    onOpenSearch = { searches++ },
                                    modifier = Modifier.weight(1f).testTag("library_collection"),
                                )
                                Text("Opened media", Modifier.weight(1f))
                            }
                        } else {
                            LibraryScreenContent(
                                LibraryUiState.Empty,
                                {},
                                onOpenSearch = { searches++ },
                                modifier = Modifier.testTag("library_collection"),
                            )
                        }
                    }
                }
            }
            onAllNodesWithTag("workspace_search").assertCountEquals(1)
            onAllNodesWithTag("library_search_action").assertCountEquals(0)
            val searchBounds = onNodeWithTag("workspace_search").fetchSemanticsNode().boundsInRoot
            val collectionBounds = onNodeWithTag("library_collection").fetchSemanticsNode().boundsInRoot
            assertTrue(searchBounds.bottom <= collectionBounds.top)
            onNodeWithTag("workspace_search").performClick()
            assertEquals(1, searches)
        }

    @Test
    fun homeLibraryUsesTheExistingUnifiedSearch() =
        runDesktopComposeUiTest(width = 1000, height = 900) {
            var searches = 0
            setContent {
                LogDateTheme {
                    WorkspaceScaffold(
                        listOf(WorkspaceDestination("library", "Library", Icons.Default.PhotoLibrary)),
                        "library",
                        {},
                        onSearch = { searches++ },
                    ) { LibraryScreenContent(LibraryUiState.Empty, {}) }
                }
            }
            onAllNodesWithTag("workspace_search").assertCountEquals(1)
            onAllNodesWithTag("library_search_action").assertCountEquals(0)
            onNodeWithTag("workspace_search").performClick()
            assertEquals(1, searches)
        }
}
