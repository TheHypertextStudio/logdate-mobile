@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.logdate.client.repository.search.SearchContentType
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class SearchNarrowingTest {
    private val sam = Uuid.random()
    private val lake = Uuid.random()

    private fun months(vararg counts: Pair<Int, Int>): List<Int> = List(12) { month -> counts.toMap()[month + 1] ?: 0 }

    private val options =
        SearchNarrowingOptions(
            types =
                listOf(
                    NarrowingOption(SearchContentType.TEXT_NOTE, null, 31, false),
                    NarrowingOption(SearchContentType.JOURNAL, null, 17, true),
                ),
            people = listOf(NarrowingOption(sam, "Sam", 23, false)),
            places = listOf(NarrowingOption(lake, "Lake Mead", 29, false)),
            years =
                listOf(
                    NarrowingYear(2024, months(6 to 2), selected = false),
                    NarrowingYear(2025, months(7 to 3, 8 to 1), selected = true),
                ),
        )

    @Test
    fun `the panel shows each kind of option with its count`() =
        runComposeUiTest {
            setContent { LogDateTheme { Narrowing(options, SearchNarrowingLayout.Panel) } }

            onNodeWithText("Sam").assertIsDisplayed()
            onNodeWithText("23").assertIsDisplayed()
            onNodeWithText("Lake Mead").assertIsDisplayed()
            onNodeWithText("29").assertIsDisplayed()
            onNodeWithText("Notes").assertIsDisplayed()
            onNodeWithText("31").assertIsDisplayed()
            onNodeWithTag(SEARCH_TIME_STRIP_TAG).assertIsDisplayed()
        }

    @Test
    fun `choosing options in the panel reports what was chosen`() =
        runComposeUiTest {
            val chosen = mutableListOf<String>()
            setContent {
                LogDateTheme {
                    Narrowing(
                        options,
                        SearchNarrowingLayout.Panel,
                        onPerson = { chosen += "person:$it" },
                        onPlace = { chosen += "place:$it" },
                        onType = { chosen += "type:$it" },
                        onYear = { chosen += "year:$it" },
                    )
                }
            }

            onNodeWithText("Sam").performClick()
            onNodeWithText("Lake Mead").performClick()
            onNodeWithText("Notes").performClick()
            onNodeWithText("2024").performClick()

            assertEquals(listOf("person:$sam", "place:$lake", "type:TEXT_NOTE", "year:2024"), chosen)
        }

    @Test
    fun `chosen options are marked as selected`() =
        runComposeUiTest {
            setContent { LogDateTheme { Narrowing(options, SearchNarrowingLayout.Panel) } }

            onNodeWithText("Journals").assertIsDisplayed()
            onNodeWithText("Notes").assertIsNotSelected()
            onNodeWithText("Journals").assertIsSelected()
        }

    @Test
    fun `clear appears only when something is chosen and clears it`() =
        runComposeUiTest {
            var cleared = false
            setContent { LogDateTheme { Narrowing(options, SearchNarrowingLayout.Panel, onClear = { cleared = true }) } }

            onNodeWithTag(SEARCH_NARROWING_CLEAR_TAG).performClick()

            assertTrue(cleared)
        }

    @Test
    fun `the row shows chips and reports choices`() =
        runComposeUiTest {
            var chosenPlace: Uuid? = null
            setContent {
                LogDateTheme {
                    Narrowing(options, SearchNarrowingLayout.Row, onPlace = { chosenPlace = it })
                }
            }

            onNodeWithText("Lake Mead").performClick()

            assertEquals(lake, chosenPlace)
            onNodeWithTag(SEARCH_TIME_STRIP_TAG).assertIsDisplayed()
        }

    @Test
    fun `nothing to choose draws nothing`() =
        runComposeUiTest {
            setContent { LogDateTheme { Narrowing(SearchNarrowingOptions(), SearchNarrowingLayout.Panel) } }

            onNodeWithTag(SEARCH_NARROWING_TAG).assertDoesNotExist()
        }

    @Composable
    private fun Narrowing(
        options: SearchNarrowingOptions,
        layout: SearchNarrowingLayout,
        onType: (SearchContentType) -> Unit = {},
        onPerson: (Uuid) -> Unit = {},
        onPlace: (Uuid) -> Unit = {},
        onYear: (Int) -> Unit = {},
        onClear: () -> Unit = {},
    ) = SearchNarrowing(options, layout, onType, onPerson, onPlace, onYear, onClear)
}
