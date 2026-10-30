package app.logdate.feature.journals.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class JournalFilterBarTest {
    @Test
    fun offersOnlyControlsThatChangeTheJournalList() =
        runDesktopComposeUiTest(width = 600, height = 200) {
            setContent {
                LogDateTheme {
                    JournalFilterBar(
                        layoutMode = JournalLayoutMode.CAROUSEL,
                        sortOption = JournalSortOption.LAST_UPDATED,
                        onToggleLayoutMode = {},
                        onSortOptionSelected = {},
                    )
                }
            }

            onNodeWithTag("LayoutModeToggle").assertIsDisplayed()
            onNodeWithTag("SortDropdownChip").assertIsDisplayed()
            onAllNodesWithText("Owned by me").assertCountEquals(0)
            onAllNodesWithText("Shared").assertCountEquals(0)
        }
}
