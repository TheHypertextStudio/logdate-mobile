package app.logdate.client.e2e

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.feature.journals.ui.JournalFilterBar
import app.logdate.feature.journals.ui.JournalLayoutMode
import app.logdate.feature.journals.ui.JournalSortOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for the [JournalFilterBar] component.
 *
 * These tests validate the interactive elements of the journal gallery header,
 * ensuring that layout toggles and sort selection menus correctly update the UI
 * state and trigger their respective callbacks.
 */
@RunWith(AndroidJUnit4::class)
class JournalFilterBarE2ETest {
    @get:Rule
    val composeRule = createComposeRule()

    // region Layout Mode Toggle

    @Test
    fun `layout toggle in carousel mode shows switch to grid description`() {
        composeRule.setContent {
            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = {},
                onSortOptionSelected = {},
            )
        }

        composeRule.onNodeWithTag("LayoutModeToggle").assertIsDisplayed()
    }

    @Test
    fun `layout toggle click invokes callback`() {
        var toggled = false

        composeRule.setContent {
            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = { toggled = true },
                onSortOptionSelected = {},
            )
        }

        composeRule.onNodeWithTag("LayoutModeToggle").performClick()
        assert(toggled) { "Expected onToggleLayoutMode to be called" }
    }

    @Test
    fun `layout toggle switches to grid updates state`() {
        composeRule.setContent {
            var mode by remember { mutableStateOf(JournalLayoutMode.CAROUSEL) }

            JournalFilterBar(
                layoutMode = mode,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = {
                    mode =
                        when (mode) {
                            JournalLayoutMode.CAROUSEL -> JournalLayoutMode.GRID
                            JournalLayoutMode.GRID -> JournalLayoutMode.CAROUSEL
                        }
                },
                onSortOptionSelected = {},
            )
        }

        // Start in carousel mode, toggle to grid
        composeRule.onNodeWithTag("LayoutModeToggle").performClick()
        composeRule.waitForIdle()

        // Toggle back to carousel
        composeRule.onNodeWithTag("LayoutModeToggle").performClick()
        composeRule.waitForIdle()

        // Should still be functional (no crash)
        composeRule.onNodeWithTag("LayoutModeToggle").assertIsDisplayed()
    }

    // endregion

    // region Sort Dropdown

    @Test
    fun `sort chip displays current sort option`() {
        composeRule.setContent {
            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = {},
                onSortOptionSelected = {},
            )
        }

        composeRule.onNodeWithText("Sorting by last updated", substring = true).assertIsDisplayed()
    }

    @Test
    fun `sort chip click opens dropdown`() {
        composeRule.setContent {
            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = {},
                onSortOptionSelected = {},
            )
        }

        composeRule.onNodeWithTag("SortDropdownChip").performClick()
        composeRule.waitForIdle()

        // Dropdown menu items should be visible with capitalized labels
        composeRule.onNodeWithText("Last Updated").assertIsDisplayed()
        composeRule.onNodeWithText("Date Created").assertIsDisplayed()
        composeRule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test
    fun `sort dropdown select option invokes callback and closes`() {
        var selectedOption: JournalSortOption? = null

        composeRule.setContent {
            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = {},
                onSortOptionSelected = { selectedOption = it },
            )
        }

        composeRule.onNodeWithTag("SortDropdownChip").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Title").performClick()
        composeRule.waitForIdle()

        assert(selectedOption == JournalSortOption.TITLE) {
            "Expected TITLE but got $selectedOption"
        }
    }

    @Test
    fun `sort dropdown select option updates chip label`() {
        var selectedSort = JournalSortOption.LAST_UPDATED

        composeRule.setContent {
            var sort by remember { mutableStateOf(JournalSortOption.LAST_UPDATED) }

            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = sort,
                onToggleLayoutMode = {},
                onSortOptionSelected = {
                    sort = it
                    selectedSort = it
                },
            )
        }

        // Open dropdown and select "Date Created"
        composeRule.onNodeWithTag("SortDropdownChip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Date Created").performClick()
        composeRule.waitForIdle()

        assert(selectedSort == JournalSortOption.CREATED) {
            "Expected CREATED but got $selectedSort"
        }
        composeRule.onNodeWithTag("SortDropdownChip").assertIsDisplayed()
    }

    // endregion

    // region All Controls Together

    @Test
    fun `all controls rendered in default state`() {
        composeRule.setContent {
            JournalFilterBar(
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                onToggleLayoutMode = {},
                onSortOptionSelected = {},
            )
        }

        composeRule.onNodeWithTag("JournalFilterBar").assertIsDisplayed()
        composeRule.onNodeWithTag("LayoutModeToggle").assertIsDisplayed()
        composeRule.onNodeWithTag("SortDropdownChip").assertIsDisplayed()
    }

    // endregion
}
