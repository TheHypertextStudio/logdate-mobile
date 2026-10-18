package app.logdate.feature.search.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.key
import app.logdate.ui.theme.LogDateTheme
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AdaptiveSearchResultsTest {
    private val journal = result("Lake Days", SearchContentType.JOURNAL, "2025-07-01T12:00:00Z")
    private val rewind = result("Summer 2025", SearchContentType.REWIND, "2025-09-22T12:00:00Z")
    private val note = result("A day on the water", SearchContentType.TEXT_NOTE, "2025-07-19T15:00:00Z")
    private val voice = result("The water was so clear", SearchContentType.TRANSCRIPTION, "2025-07-19T18:00:00Z")

    private val details =
        mapOf(
            journal.key to SearchResultDetail.Journal(coverUri = null, entryCount = 14),
            voice.key to SearchResultDetail.Audio(durationMs = 48_000),
        )

    @Test
    fun `each shape with results gets its own section`() =
        runComposeUiTest {
            setContent {
                LogDateTheme {
                    AdaptiveSearchResults(groupSearchResults(listOf(journal, rewind, note, voice), TimeZone.UTC), details, {}, {}, {})
                }
            }

            onNodeWithTag(ADAPTIVE_SEARCH_TOP_MATCHES_TAG).assertIsDisplayed()
            onNodeWithTag(ADAPTIVE_SEARCH_COLLECTIONS_TAG).assertIsDisplayed()
            onNodeWithTag(ADAPTIVE_SEARCH_DAYS_TAG).assertIsDisplayed()
        }

    @Test
    fun `a shape with no results has no section`() =
        runComposeUiTest {
            setContent {
                LogDateTheme {
                    AdaptiveSearchResults(groupSearchResults(listOf(note), TimeZone.UTC), emptyMap(), {}, {}, {})
                }
            }

            onNodeWithTag(ADAPTIVE_SEARCH_DAYS_TAG).assertIsDisplayed()
            onNodeWithTag(ADAPTIVE_SEARCH_TOP_MATCHES_TAG).assertDoesNotExist()
            onNodeWithTag(ADAPTIVE_SEARCH_COLLECTIONS_TAG).assertDoesNotExist()
        }

    @Test
    fun `choosing a top match passes that result on`() =
        runComposeUiTest {
            var clicked: SearchResult? = null
            setContent {
                LogDateTheme {
                    AdaptiveSearchResults(groupSearchResults(listOf(journal), TimeZone.UTC), details, { clicked = it }, {}, {})
                }
            }

            onNodeWithText("Lake Days").performClick()

            assertEquals(journal, clicked)
        }

    @Test
    fun `choosing a line in a day passes that result on`() =
        runComposeUiTest {
            var clicked: SearchResult? = null
            setContent {
                LogDateTheme {
                    AdaptiveSearchResults(groupSearchResults(listOf(note, voice), TimeZone.UTC), details, { clicked = it }, {}, {})
                }
            }

            onNodeWithText("The water was so clear").performClick()

            assertEquals(voice, clicked)
        }

    @Test
    fun `choosing a day heading opens the day`() =
        runComposeUiTest {
            var opened: SearchResult? = null
            setContent {
                LogDateTheme {
                    AdaptiveSearchResults(groupSearchResults(listOf(note, voice), TimeZone.UTC), details, {}, {}, { opened = it })
                }
            }

            onNodeWithText(formatDateLocalized(LocalDate(2025, 7, 19))).performClick()

            assertEquals(note, opened)
        }

    @Test
    fun `a recording shows its length`() =
        runComposeUiTest {
            setContent {
                LogDateTheme {
                    AdaptiveSearchResults(groupSearchResults(listOf(voice), TimeZone.UTC), details, {}, {}, {})
                }
            }

            onNodeWithText("0:48").assertIsDisplayed()
        }

    @Test
    fun `duration is formatted as minutes and seconds`() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("0:48", formatDuration(48_000))
        assertEquals("2:10", formatDuration(130_000))
        assertEquals("61:01", formatDuration(3_661_000))
    }

    private fun result(
        content: String,
        type: SearchContentType,
        created: String,
    ) = SearchResult(uid = Uuid.random(), content = content, created = Instant.parse(created), contentType = type)
}
