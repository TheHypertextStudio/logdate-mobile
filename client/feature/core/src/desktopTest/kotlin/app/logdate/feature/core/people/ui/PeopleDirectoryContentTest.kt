@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.shared.model.PersonOrigin
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val WINDOW_WIDTH = 411
private const val WINDOW_HEIGHT = 1600
private const val EMPTY = "No one is in your list yet. Names you add from suggestions appear here."
private const val EMPTY_WITH_CONTACTS =
    "No one is in your list yet. Names you add from suggestions and contacts you import appear here."

private val ava =
    PeopleDirectoryViewModel.PersonRow(
        id = Uuid.parse("00000000-0000-0000-0000-000000000301"),
        name = "Ava",
        origin = PersonOrigin.CONTACT_FULL,
    )
private val maya =
    PeopleDirectoryViewModel.PersonRow(
        id = Uuid.parse("00000000-0000-0000-0000-000000000302"),
        name = "Maya",
        origin = PersonOrigin.INFERRED,
    )

/**
 * The list of people says where each person came from, which is what tells a contact from a name
 * found in entries, and it does not claim to be empty before it has been read.
 */
@OptIn(ExperimentalTestApi::class)
class PeopleDirectoryContentTest {
    @Test
    fun `each person shows where they came from and opens their page`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val opened = mutableListOf<Uuid>()
            setContent { Directory(listOf(ava, maya), onOpenPerson = { opened += it }) }

            onNodeWithText("From your contacts").assertIsDisplayed()
            onNodeWithText("Found in your entries").assertIsDisplayed()
            onNodeWithText("Open details").assertDoesNotExist()
            onNodeWithText("Maya").performClick()

            assertEquals(listOf(maya.id), opened)
        }

    @Test
    fun `with no one in the list the page says how names get there`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Directory(emptyList()) }

            onNodeWithText(EMPTY).assertIsDisplayed()
        }

    @Test
    fun `where contacts can be imported the empty list names them as a way in`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Directory(emptyList(), contactImportAvailable = true) }

            onNodeWithText(EMPTY_WITH_CONTACTS).assertIsDisplayed()
            onNodeWithText(EMPTY).assertDoesNotExist()
        }

    @Test
    fun `until the list is read the page does not claim it is empty`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Directory(people = null) }

            onNodeWithText(EMPTY).assertDoesNotExist()
        }

    @Test
    fun `a failed look for names says the list may be out of date and can be dismissed`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var dismissed = 0
            setContent { Directory(listOf(ava), notice = PeopleNotice.RefreshFailed, onDismissNotice = { dismissed++ }) }

            onNodeWithText("Couldn't look for names in your entries. This list may be out of date.").assertIsDisplayed()
            onNodeWithText("Ava").assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()

            assertEquals(1, dismissed)
        }
}

@Composable
private fun Directory(
    people: List<PeopleDirectoryViewModel.PersonRow>?,
    notice: PeopleNotice? = null,
    onOpenPerson: (Uuid) -> Unit = {},
    onDismissNotice: () -> Unit = {},
    contactImportAvailable: Boolean = false,
) {
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        PeopleDirectoryContent(
            people = people,
            notice = notice,
            onBack = {},
            onOpenPerson = onOpenPerson,
            onDismissNotice = onDismissNotice,
            contactImportAvailable = contactImportAvailable,
        )
    }
}
