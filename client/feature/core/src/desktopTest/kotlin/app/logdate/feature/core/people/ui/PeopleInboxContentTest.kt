@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val WINDOW_WIDTH = 411
private const val WINDOW_HEIGHT = 1600
private const val EMPTY = "No names to review. A name is suggested after it appears in at least two entries or transcripts."
private const val ADD = "Add to people"
private const val REJECT = "Don't suggest again"

private val maya =
    PeopleInboxViewModel.SuggestionUiState(
        id = Uuid.parse("00000000-0000-0000-0000-000000000201"),
        nameHint = "Maya",
        evidencePreview = listOf("Lunch with Maya", "Maya called"),
    )

/**
 * Every name found in entries waits here until it is added or rejected. The page says nothing
 * about an empty inbox until the suggestions have been read, and says so when a decision could not
 * be saved.
 */
@OptIn(ExperimentalTestApi::class)
class PeopleInboxContentTest {
    @Test
    fun `a suggested name shows the lines it was found in and can be added`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val added = mutableListOf<Uuid>()
            setContent { Inbox(listOf(maya), onConfirm = { added += it }) }

            onNodeWithText("Maya").assertIsDisplayed()
            onNodeWithText("Lunch with Maya").assertIsDisplayed()
            onNodeWithText("Maya called").assertIsDisplayed()
            onNodeWithText(ADD).performClick()

            assertEquals(listOf(maya.id), added)
        }

    @Test
    fun `a suggested name can be rejected so it is not suggested again`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val rejected = mutableListOf<Uuid>()
            setContent { Inbox(listOf(maya), onDismissSuggestion = { rejected += it }) }

            onNodeWithText(REJECT).performClick()

            assertEquals(listOf(maya.id), rejected)
        }

    @Test
    fun `with nothing to review the page says when a name is suggested`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Inbox(emptyList()) }

            onNodeWithText(EMPTY).assertIsDisplayed()
            onNodeWithText(ADD).assertDoesNotExist()
        }

    @Test
    fun `until the suggestions are read the page does not claim there is nothing to review`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Inbox(suggestions = null) }

            onNodeWithText(EMPTY).assertDoesNotExist()
            onNodeWithText(ADD).assertDoesNotExist()
        }

    @Test
    fun `a name that could not be added says so above the suggestions and can be dismissed`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var dismissed = 0
            setContent {
                Inbox(listOf(maya), notice = PeopleNotice.AddSuggestionFailed, onDismissNotice = { dismissed++ })
            }

            onNodeWithText("Couldn't add this name to people. Try again.").assertIsDisplayed()
            onNodeWithText(ADD).assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()

            assertEquals(1, dismissed)
        }

    @Test
    fun `a name that could not be rejected says so`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Inbox(listOf(maya), notice = PeopleNotice.DismissSuggestionFailed) }

            onNodeWithText("Couldn't dismiss this suggestion. Try again.").assertIsDisplayed()
        }

    @Test
    fun `a failed look for names says the list may be out of date`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Inbox(emptyList(), notice = PeopleNotice.RefreshFailed) }

            onNodeWithText("Couldn't look for names in your entries. This list may be out of date.").assertIsDisplayed()
        }
}

@Composable
private fun Inbox(
    suggestions: List<PeopleInboxViewModel.SuggestionUiState>?,
    notice: PeopleNotice? = null,
    onConfirm: (Uuid) -> Unit = {},
    onDismissSuggestion: (Uuid) -> Unit = {},
    onDismissNotice: () -> Unit = {},
) {
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        PeopleInboxContent(
            suggestions = suggestions,
            notice = notice,
            onBack = {},
            onConfirm = onConfirm,
            onDismissSuggestion = onDismissSuggestion,
            onDismissNotice = onDismissNotice,
        )
    }
}
