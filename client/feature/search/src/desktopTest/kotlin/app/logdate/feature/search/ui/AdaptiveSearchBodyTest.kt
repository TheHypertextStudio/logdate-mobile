@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchPreviewEntry
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.client.repository.search.key
import app.logdate.ui.theme.LogDateTheme
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AdaptiveSearchBodyTest {
    private val journal = result("Lake Days", SearchContentType.JOURNAL, "2025-07-01T12:00:00Z")
    private val rewind = result("Summer 2025", SearchContentType.REWIND, "2025-09-22T12:00:00Z")
    private val note = result("A day on the water", SearchContentType.TEXT_NOTE, "2025-07-19T15:00:00Z")
    private val voice = result("The water was so clear", SearchContentType.TRANSCRIPTION, "2025-07-19T18:00:00Z")
    private val postcard = result("Postcard to Dad", SearchContentType.POSTCARD, "2025-11-21T12:00:00Z")
    private val results = listOf(journal, rewind, postcard, note, voice)
    private val details =
        mapOf(
            journal.key to SearchResultDetail.Journal(coverUri = null, entryCount = 14),
            rewind.key to
                SearchResultDetail.Rewind(
                    start = Instant.parse("2025-06-21T12:00:00Z"),
                    end = Instant.parse("2025-09-22T12:00:00Z"),
                    coverUri = null,
                ),
            postcard.key to SearchResultDetail.Postcard(coverUri = null, modifiedAt = Instant.parse("2025-11-21T12:00:00Z")),
        )

    @Test
    fun `a wide window shows the first result in the preview and keeps the narrowing panel`() =
        runComposeUiTest {
            setContent { LogDateTheme { Body(1180, 740) } }

            onNodeWithTag(SEARCH_PREVIEW_TAG).assertIsDisplayed()
            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertTextEquals("Lake Days")
            onNodeWithTag(SEARCH_NARROWING_TAG).assertExists()
        }

    @Test
    fun `choosing a result beside a preview shows it there instead of leaving search`() =
        runComposeUiTest {
            var opened: SearchResult? = null
            setContent { LogDateTheme { Body(1180, 740, onResultClick = { opened = it }) } }

            onNodeWithTag(ADAPTIVE_SEARCH_COLLECTIONS_TAG).assertExists()
            onNodeWithText("Summer 2025").performClick()

            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertTextEquals("Summer 2025")
            assertNull(opened)
        }

    @Test
    fun `the preview opens the chosen result`() =
        runComposeUiTest {
            var opened: SearchResult? = null
            setContent { LogDateTheme { Body(1180, 740, onResultClick = { opened = it }) } }

            onNodeWithTag(SEARCH_PREVIEW_OPEN_TAG).performClick()

            assertEquals(journal, opened)
        }

    @Test
    fun `choosing an entry beside a preview selects its day`() =
        runComposeUiTest {
            setContent { LogDateTheme { Body(1180, 740) } }

            onNodeWithText("The water was so clear").performClick()

            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertTextEquals(formatDateLocalized(LocalDate(2025, 7, 19)))
        }

    @Test
    fun `a day preview from the host replaces the built in one`() =
        runComposeUiTest {
            setContent {
                LogDateTheme {
                    Body(1180, 740, dayPreview = {
                        date,
                        highlight,
                        ->
                        Text("host day $date ${highlight?.contentType}", Modifier.testTag("host_day"))
                    })
                }
            }

            onNodeWithText("The water was so clear").performClick()

            onNodeWithTag("host_day").assertExists()
            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertDoesNotExist()
        }

    @Test
    fun `a narrow window opens a chosen result straight away and shows no preview`() =
        runComposeUiTest {
            var opened: SearchResult? = null
            setContent { LogDateTheme { Body(400, 800, onResultClick = { opened = it }) } }

            onNodeWithText("Summer 2025").performClick()

            assertEquals(rewind, opened)
            onNodeWithTag(SEARCH_PREVIEW_TAG).assertDoesNotExist()
        }

    @Test
    fun `a journal preview shows its description, start and latest entries`() =
        runComposeUiTest {
            val preview =
                SearchResultPreview.Journal(
                    description = "Summers on the water",
                    created = Instant.parse("2025-07-01T12:00:00Z"),
                    latestEntries =
                        listOf(
                            SearchPreviewEntry(Uuid.random(), "Packed the kayak", Instant.parse("2025-07-19T15:00:00Z")),
                            SearchPreviewEntry(Uuid.random(), "Drove back at dusk", Instant.parse("2025-07-18T15:00:00Z")),
                        ),
                )
            setContent { LogDateTheme { Body(1180, 740, loadPreview = { if (it == journal.key) preview else null }) } }
            waitForIdle()

            onNodeWithText("Summers on the water").assertIsDisplayed()
            onNodeWithText("Packed the kayak").assertIsDisplayed()
            onNodeWithText("Drove back at dusk").assertIsDisplayed()
            onNodeWithTag(SEARCH_PREVIEW_ENTRIES_TAG).assertExists()
        }

    @Test
    fun `a preview that is already known shows without being loaded again`() =
        runComposeUiTest {
            val asked = mutableListOf<SearchResultKey>()
            val known = SearchResultPreview.Journal("Summers on the water", Instant.parse("2025-07-01T12:00:00Z"), emptyList())
            setContent {
                LogDateTheme {
                    Body(1180, 740, loadPreview = {
                        asked += it
                        null
                    }, knownPreviews = mapOf(journal.key to known))
                }
            }
            waitForIdle()

            onNodeWithText("Summers on the water").assertIsDisplayed()
            assertEquals(emptyList(), asked)
        }

    @Test
    fun `a rewind preview shows its period, highlights, people and place`() =
        runComposeUiTest {
            val preview =
                SearchResultPreview.Rewind(
                    highlights = listOf("We stayed until dark"),
                    people = listOf("Sam", "Maya"),
                    place = "Lake Mead",
                )
            setContent { LogDateTheme { Body(1180, 740, loadPreview = { if (it == rewind.key) preview else null }) } }

            onNodeWithText("Summer 2025").performClick()
            waitForIdle()

            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertTextEquals("Summer 2025")
            onNodeWithText("We stayed until dark").assertIsDisplayed()
            onNodeWithText("Sam, Maya").assertIsDisplayed()
            onNode(hasText("Lake Mead") and hasAnyAncestor(hasTestTag(SEARCH_PREVIEW_TAG))).assertIsDisplayed()
            onNodeWithTag(SEARCH_PREVIEW_PERIOD_TAG).assertExists()
        }

    @Test
    fun `a postcard preview shows the postcard and when it was last changed`() =
        runComposeUiTest {
            setContent { LogDateTheme { Body(1180, 740) } }

            onNodeWithText("Postcard to Dad").performClick()

            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertTextEquals("Postcard to Dad")
            onNodeWithTag(SEARCH_PREVIEW_PERIOD_TAG).assertExists()
            onNodeWithTag(SEARCH_PREVIEW_OPEN_TAG).assertExists()
        }

    @Test
    fun `a result whose preview cannot be loaded still shows its summary and can be opened`() =
        runComposeUiTest {
            var opened: SearchResult? = null
            setContent {
                LogDateTheme { Body(1180, 740, onResultClick = { opened = it }, loadPreview = { error("unreadable") }) }
            }
            waitForIdle()

            onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG).assertTextEquals("Lake Days")
            onNodeWithTag(SEARCH_PREVIEW_OPEN_TAG).performClick()
            assertEquals(journal, opened)
        }

    @Test
    fun `no preview is loaded where none is shown`() =
        runComposeUiTest {
            val asked = mutableListOf<SearchResultKey>()
            setContent {
                LogDateTheme {
                    Body(400, 800, loadPreview = {
                        asked += it
                        null
                    })
                }
            }
            waitForIdle()

            assertEquals(emptyList(), asked)
        }

    @Composable
    private fun Body(
        width: Int,
        height: Int,
        onResultClick: (SearchResult) -> Unit = {},
        dayPreview: (@Composable (LocalDate, SearchResultKey?) -> Unit)? = null,
        loadPreview: suspend (SearchResultKey) -> SearchResultPreview? = { null },
        knownPreviews: Map<SearchResultKey, SearchResultPreview> = emptyMap(),
    ) {
        Box(Modifier.requiredSize(width.dp, height.dp)) {
            AdaptiveSearchBody(
                results = results,
                details = details,
                narrowing = SearchNarrowingOptions(places = listOf(NarrowingOption(Uuid.random(), "Lake Mead", 4, false))),
                actions =
                    AdaptiveSearchActions(
                        onResultClick = onResultClick,
                        onResultLongClick = {},
                        onOpenDay = {},
                        onToggleType = {},
                        onTogglePerson = {},
                        onTogglePlace = {},
                        onToggleYear = {},
                        onClearNarrowing = {},
                    ),
                dayPreview = dayPreview,
                loadPreview = loadPreview,
                knownPreviews = knownPreviews,
            )
        }
    }

    private fun result(
        content: String,
        type: SearchContentType,
        created: String,
    ) = SearchResult(uid = Uuid.random(), content = content, created = Instant.parse(created), contentType = type)
}
