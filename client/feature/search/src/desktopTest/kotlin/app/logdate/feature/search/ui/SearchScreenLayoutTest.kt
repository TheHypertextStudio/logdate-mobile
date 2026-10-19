@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.key
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class SearchScreenLayoutTest {
    private val note =
        SearchResult(Uuid.random(), "Paddled out on the lake", Instant.parse("2025-07-19T15:00:00Z"), SearchContentType.TEXT_NOTE)

    @Test
    fun `results show under the search field without expanding anything`() =
        runComposeUiTest {
            setContent {
                LogDateTheme {
                    SearchScreenContent(
                        searchState = SearchScreenState.Results("lake", listOf(note)),
                        onQueryChange = {},
                        onCommitSearch = {},
                        onResultClick = {},
                        onResultOpenDay = {},
                        onGoBack = {},
                        queryText = "lake",
                    )
                }
            }

            onNodeWithTag(SEARCH_SCREEN_INPUT_ACCESSIBILITY_TAG).assertIsDisplayed()
            onNodeWithText("Paddled out on the lake", substring = true).assertIsDisplayed()
            val field = onNodeWithTag(SEARCH_SCREEN_INPUT_ACCESSIBILITY_TAG).fetchSemanticsNode().boundsInRoot
            val result = onNodeWithText("Paddled out on the lake", substring = true).fetchSemanticsNode().boundsInRoot
            assertTrue(field.bottom <= result.top, "field $field is not above the result $result")
        }

    @Test
    fun `typing in the field reports the query`() =
        runComposeUiTest {
            val queries = mutableListOf<String>()
            setContent {
                LogDateTheme {
                    SearchScreenContent(
                        searchState = SearchScreenState.Idle(emptyList()),
                        onQueryChange = { queries += it },
                        onCommitSearch = {},
                        onResultClick = {},
                        onResultOpenDay = {},
                        onGoBack = {},
                    )
                }
            }

            onNodeWithTag(SEARCH_SCREEN_INPUT_ACCESSIBILITY_TAG).performTextInput("lake")
            waitForIdle()

            assertTrue("lake" in queries, "queries reported: $queries")
        }

    @Test
    fun `the back button leaves search`() =
        runComposeUiTest {
            var left = false
            setContent {
                LogDateTheme {
                    SearchScreenContent(
                        searchState = SearchScreenState.Idle(emptyList()),
                        onQueryChange = {},
                        onCommitSearch = {},
                        onResultClick = {},
                        onResultOpenDay = {},
                        onGoBack = { left = true },
                    )
                }
            }

            onNodeWithContentDescription("Go back").performClick()

            assertTrue(left)
        }

    @Test
    fun `the clear button empties the field`() =
        runComposeUiTest {
            val queries = mutableListOf<String>()
            setContent {
                LogDateTheme {
                    SearchScreenContent(
                        searchState = SearchScreenState.Idle(emptyList()),
                        onQueryChange = { queries += it },
                        onCommitSearch = {},
                        onResultClick = {},
                        onResultOpenDay = {},
                        onGoBack = {},
                        initialQuery = "lake",
                    )
                }
            }

            onNodeWithContentDescription("Clear search").performClick()
            waitForIdle()

            assertEquals("", queries.last())
        }

    @Test
    fun `journal and person rows show what is known about them`() =
        runComposeUiTest {
            val journal = SearchResult(Uuid.random(), "Lake Days", Instant.parse("2025-07-01T12:00:00Z"), SearchContentType.JOURNAL)
            val person = SearchResult(Uuid.random(), "Sam", Instant.parse("2025-07-02T12:00:00Z"), SearchContentType.PERSON)
            setContent {
                LogDateTheme {
                    SearchScreenContent(
                        searchState = SearchScreenState.Results("s", listOf(journal, person, note)),
                        onQueryChange = {},
                        onCommitSearch = {},
                        onResultClick = {},
                        onResultOpenDay = {},
                        onGoBack = {},
                        resultDetails =
                            mapOf(
                                journal.key to SearchResultDetail.Journal(coverUri = null, entryCount = 14),
                                person.key to SearchResultDetail.Person(photoUri = null, relationshipLabel = "Friend"),
                            ),
                    )
                }
            }

            onNodeWithText("14 entries").assertIsDisplayed()
            onNodeWithText("Friend").assertIsDisplayed()
            onNodeWithText("S").assertIsDisplayed()
        }
}
