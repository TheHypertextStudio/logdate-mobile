@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Event
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonOrigin
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val WINDOW_WIDTH = 411
private const val WINDOW_HEIGHT = 2400
private const val BOOK_WIDTH = 1440
private const val BOOK_HEIGHT = 500
private const val REMOVE_FROM_PEOPLE = "Remove from people"
private const val CONFIRM_TITLE = "Remove Maya Hassan?"
private const val CONFIRM_MESSAGE =
    "Maya Hassan is removed from your list and won't be suggested or imported again. Your entries stay as they are."
private const val EVENT_TITLE = "Sunday coffee"

private val writtenAt = Instant.parse("2026-03-12T09:30:00Z")
private val maya = Person(name = "Maya Hassan", aliases = listOf("Maya"), origin = PersonOrigin.INFERRED)
private val entries =
    List(7) { index ->
        JournalNote.Text(creationTimestamp = writtenAt, lastUpdated = writtenAt, content = "Entry $index")
    }
private val events = listOf(Event(title = EVENT_TITLE, startTime = writtenAt, created = writtenAt, lastUpdated = writtenAt))

private val bookPosture =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 708.dp,
                        top = 0.dp,
                        right = 732.dp,
                        bottom = BOOK_HEIGHT.dp,
                        width = 24.dp,
                        height = BOOK_HEIGHT.dp,
                    ),
                isSeparating = true,
            ),
    )

/**
 * A person's page lists every linked entry, leaves Events out while Events has not launched, and is
 * the only place a wrongly added name can be removed, which it does only after saying what removal
 * means.
 */
@OptIn(ExperimentalTestApi::class)
class PersonDetailContentTest {
    @Test
    fun `every linked entry is listed with how the person came to be in the list`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(loaded()) }

            entries.forEach { entry -> onNodeWithText(entry.content).assertExists() }
            onNodeWithText("Found in your entries").assertIsDisplayed()
            onNodeWithText("Also called Maya").assertIsDisplayed()
        }

    @Test
    fun `a person with no linked entries says so`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(loaded().copy(linkedEntries = emptyList())) }

            onNodeWithText("No entries are linked to this person yet.").assertIsDisplayed()
        }

    @Test
    fun `events are left out while Events has not launched`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(loaded().copy(showEvents = false)) }

            onNodeWithText("Entries").assertIsDisplayed()
            onNodeWithText("Events").assertDoesNotExist()
            onNodeWithText(EVENT_TITLE).assertDoesNotExist()
        }

    @Test
    fun `events are listed once Events is available`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(loaded().copy(showEvents = true)) }

            onNodeWithText("Events").assertIsDisplayed()
            onNodeWithText(EVENT_TITLE).assertIsDisplayed()
        }

    @Test
    fun `an entry opens when the page can open entries`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val opened = mutableListOf<Uuid>()
            setContent { PersonDetail(loaded(), onOpenEntry = { opened += it }) }

            onNodeWithText(entries[3].content).performClick()

            assertEquals(listOf(entries[3].uid), opened)
        }

    @Test
    fun `an entry has no action when the page cannot open entries`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(loaded(), onOpenEntry = null) }

            onNodeWithText(entries[3].content).assertHasNoClickAction()
        }

    @Test
    fun `removing asks first and says the person will not be suggested or imported again`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var removed = 0
            setContent { PersonDetail(loaded(), onRemove = { removed++ }) }

            onNodeWithText(REMOVE_FROM_PEOPLE).performClick()

            assertEquals(0, removed)
            onNodeWithText(CONFIRM_TITLE).assertIsDisplayed()
            onNodeWithText(CONFIRM_MESSAGE).assertIsDisplayed()

            onNodeWithText("Remove").performClick()

            assertEquals(1, removed)
            onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
        }

    @Test
    fun `cancelling the confirmation removes no one`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var removed = 0
            setContent { PersonDetail(loaded(), onRemove = { removed++ }) }

            onNodeWithText(REMOVE_FROM_PEOPLE).performClick()
            onNodeWithText("Cancel").performClick()

            assertEquals(0, removed)
            onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
            onNodeWithText(REMOVE_FROM_PEOPLE).assertIsDisplayed()
        }

    @Test
    fun `while the person is being removed the action cannot be started again`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(loaded().copy(isRemoving = true)) }

            onNodeWithText(REMOVE_FROM_PEOPLE).assertIsNotEnabled()
        }

    @Test
    fun `a removal that failed says so and can be dismissed`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var dismissed = 0
            setContent {
                PersonDetail(loaded().copy(notice = PeopleNotice.RemovePersonFailed), onDismissNotice = { dismissed++ })
            }

            onNodeWithText("Couldn't remove this person. Try again.").assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()

            assertEquals(1, dismissed)
        }

    @Test
    fun `while the person loads there is nothing to remove`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(PersonDetailViewModel.UiState(isLoading = true)) }

            onNodeWithText("Loading…").assertIsDisplayed()
            onNodeWithText(REMOVE_FROM_PEOPLE).assertDoesNotExist()
        }

    @Test
    fun `a person who could not be loaded says so and offers nothing to remove`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PersonDetail(PersonDetailViewModel.UiState(isLoading = false)) }

            onNodeWithText("This person couldn't be loaded.").assertIsDisplayed()
            onNodeWithText(REMOVE_FROM_PEOPLE).assertDoesNotExist()
        }

    @Test
    fun `in book posture the remove action and the last entry can both be reached`() =
        runDesktopComposeUiTest(width = BOOK_WIDTH, height = BOOK_HEIGHT) {
            var removed = 0
            setContent {
                provideFoldableLayoutInfo(bookPosture) {
                    PersonDetail(loaded(), onRemove = { removed++ })
                }
            }

            onNodeWithText(entries.last().content).performScrollTo().assertIsDisplayed()
            onNodeWithText("Events").assertDoesNotExist()
            onNodeWithText(REMOVE_FROM_PEOPLE).performScrollTo().performClick()
            onNodeWithText("Remove").performClick()

            assertEquals(1, removed)
        }
}

private fun loaded(): PersonDetailViewModel.UiState =
    PersonDetailViewModel.UiState(
        person = maya,
        linkedEntries = entries,
        linkedEvents = events,
        isLoading = false,
    )

@Composable
private fun PersonDetail(
    uiState: PersonDetailViewModel.UiState,
    onRemove: () -> Unit = {},
    onOpenEntry: ((Uuid) -> Unit)? = null,
    onDismissNotice: () -> Unit = {},
) {
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        PersonDetailContent(
            uiState = uiState,
            onBack = {},
            onRemove = onRemove,
            onOpenEntry = onOpenEntry,
            onDismissNotice = onDismissNotice,
        )
    }
}
