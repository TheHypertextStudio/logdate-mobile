package app.logdate.ui.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Text
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkspaceRailJournalsTest {
    private val destinations =
        listOf(
            WorkspaceDestination("day", "Locations", Icons.Default.Place),
            WorkspaceDestination("places", "Journals", Icons.Default.Place),
        )

    private fun journals(
        count: Int,
        onOpen: (String) -> Unit = {},
    ) = WorkspaceRailJournals(List(count) { WorkspaceRailJournal("id$it", "Journal $it") }, onOpen)

    private fun DesktopComposeUiTest.showRail(rail: WorkspaceRailJournals) =
        setContent {
            LogDateTheme {
                WorkspaceScaffold(destinations, "day", {}, journals = rail) { Text("Content") }
            }
        }

    @Test fun anExpandedRailListsJournalsUnderALabel() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            showRail(journals(2))
            onNodeWithText("Journal 0").assertIsDisplayed()
            onNodeWithText("Journal 1").assertIsDisplayed()
        }

    @Test fun selectingAJournalOpensIt() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var opened: String? = null
            showRail(journals(2, onOpen = { opened = it }))
            onNodeWithText("Journal 1").performClick()
            assertEquals("id1", opened)
        }

    @Test fun onlyTheFirstFourJournalsShow() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            showRail(journals(9))
            onNodeWithText("Journal 3").assertIsDisplayed()
            onNodeWithText("Journal 4").assertDoesNotExist()
        }

    @Test fun rowsScrollWhenTheWindowIsTooShort() =
        runDesktopComposeUiTest(width = 1280, height = 500) {
            var opened: String? = null
            showRail(journals(9, onOpen = { opened = it }))
            onNodeWithText("Journal 3").performScrollTo().performClick()
            assertEquals("id3", opened)
        }

    @Test fun aCollapsedRailHasNoJournals() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            showRail(journals(2))
            onNodeWithContentDescription("Collapse navigation").performClick()
            onNodeWithText("Journal 0").assertDoesNotExist()
        }

    @Test fun aModalRailHasNoJournals() =
        runDesktopComposeUiTest(width = 800, height = 900) {
            showRail(journals(2))
            onNodeWithContentDescription("Expand navigation").performClick()
            onNodeWithText("Journal 0").assertDoesNotExist()
        }

    @Test fun noJournalsMeansNoSection() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            showRail(journals(0))
            onNodeWithText("Recent journals").assertDoesNotExist()
        }
}
