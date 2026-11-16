@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val WINDOW_WIDTH = 411
private const val WINDOW_HEIGHT = 1600
private const val BROWSE = "Browse people"
private const val REVIEW = "Review suggestions"
private const val IMPORT = "Import all contacts"

/**
 * The People page is reachable only after People is turned on in Labs, so it has no switch of its
 * own, it offers the contact import only where contacts can be imported, and it never offers the
 * contact picker that nothing handles.
 */
@OptIn(ExperimentalTestApi::class)
class PeopleSettingsContentTest {
    @Test
    fun `the page has no switch and says the list is built on this device`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PeopleSettings(state(totalPeopleCount = 12, pendingReviewCount = 3)) }

            onAllNodes(isToggleable()).assertCountEquals(0)
            onNodeWithText("Enable People").assertDoesNotExist()
            onNodeWithText("built on this device from names in your entries and voice note transcripts", substring = true)
                .assertIsDisplayed()
            onNodeWithText("isn't synced or included in exports", substring = true).assertIsDisplayed()
            onNodeWithText("12 people in your list").assertIsDisplayed()
            onNodeWithText("3 names to review").assertIsDisplayed()
        }

    @Test
    fun `one person and one name to review are counted in the singular`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PeopleSettings(state(totalPeopleCount = 1, pendingReviewCount = 1)) }

            onNodeWithText("1 person in your list").assertIsDisplayed()
            onNodeWithText("1 name to review").assertIsDisplayed()
        }

    @Test
    fun `the rows open the list and the suggestions`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val opened = mutableListOf<String>()
            setContent {
                PeopleSettings(
                    state(totalPeopleCount = 2, pendingReviewCount = 1),
                    onBrowsePeople = { opened += BROWSE },
                    onOpenReviewInbox = { opened += REVIEW },
                )
            }

            onNodeWithText(BROWSE).performClick()
            onNodeWithText(REVIEW).performClick()

            assertEquals(listOf(BROWSE, REVIEW), opened)
        }

    @Test
    fun `with no one in the list browsing is unavailable and no review is offered`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var browsed = 0
            setContent { PeopleSettings(state(), onBrowsePeople = { browsed++ }) }

            onNodeWithText(BROWSE).assertIsNotEnabled().performClick()

            assertEquals(0, browsed)
            onNodeWithText("No one is in your list yet. Names you add from suggestions appear here.").assertIsDisplayed()
            onNodeWithText(REVIEW).assertDoesNotExist()
        }

    @Test
    fun `where contacts can be imported the empty list names them as a way in`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PeopleSettings(state(contactImportAvailable = true)) }

            onNodeWithText(
                "No one is in your list yet. Names you add from suggestions and contacts you import appear here.",
            ).assertIsDisplayed()
        }

    @Test
    fun `where contacts can be imported the page offers the full import and no contact picker`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var imports = 0
            setContent { PeopleSettings(state(contactImportAvailable = true), onImportAllContacts = { imports++ }) }

            onNodeWithText("Choose contacts").assertDoesNotExist()
            onNodeWithText("a link to their contact photo", substring = true).assertIsDisplayed()
            onNodeWithText(IMPORT).performClick()

            assertEquals(1, imports)
        }

    @Test
    fun `where contacts cannot be imported the page offers no import`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { PeopleSettings(state(contactImportAvailable = false)) }

            onNodeWithText(IMPORT).assertDoesNotExist()
            onNodeWithText("Import contacts").assertDoesNotExist()
            onNodeWithText("Choose contacts").assertDoesNotExist()
        }

    @Test
    fun `while contacts are imported the import cannot be started again`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var imports = 0
            setContent {
                PeopleSettings(
                    state(contactImportAvailable = true, isImporting = true),
                    onImportAllContacts = { imports++ },
                )
            }

            onNodeWithText(IMPORT).assertIsNotEnabled().performClick()

            assertEquals(0, imports)
            onNodeWithText("Importing contacts…").assertIsDisplayed()
        }

    @Test
    fun `a refused contacts request says where to allow access and can be dismissed`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var dismissed = 0
            setContent {
                PeopleSettings(
                    state(contactImportAvailable = true, notice = PeopleNotice.ContactsAccessNeeded),
                    onDismissMessage = { dismissed++ },
                )
            }

            onNodeWithText(
                "LogDate needs permission to read your contacts before it can import them. Allow Contacts for LogDate in " +
                    "your device settings, under Apps, LogDate, Permissions, then import again.",
            ).assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()

            assertEquals(1, dismissed)
        }

    @Test
    fun `an import says how many contacts were added and updated`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent {
                PeopleSettings(state(notice = PeopleNotice.ContactsImported(importedCount = 2, updatedCount = 1)))
            }

            onNodeWithText("Contacts imported: 2. Contacts updated: 1.").assertIsDisplayed()
        }

    @Test
    fun `an import that found nothing says no contacts were imported`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent {
                PeopleSettings(state(notice = PeopleNotice.ContactsImported(importedCount = 0, updatedCount = 0)))
            }

            onNodeWithText("No contacts were imported.").assertIsDisplayed()
        }
}

private fun state(
    contactImportAvailable: Boolean = false,
    totalPeopleCount: Int = 0,
    pendingReviewCount: Int = 0,
    notice: PeopleNotice? = null,
    isImporting: Boolean = false,
): PeopleSettingsViewModel.UiState =
    PeopleSettingsViewModel.UiState(
        contactImportAvailable = contactImportAvailable,
        totalPeopleCount = totalPeopleCount,
        pendingReviewCount = pendingReviewCount,
        notice = notice,
        isImporting = isImporting,
    )

@Composable
private fun PeopleSettings(
    uiState: PeopleSettingsViewModel.UiState,
    onBrowsePeople: () -> Unit = {},
    onOpenReviewInbox: () -> Unit = {},
    onImportAllContacts: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
) {
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        PeopleSettingsContent(
            uiState = uiState,
            onBack = {},
            onBrowsePeople = onBrowsePeople,
            onOpenReviewInbox = onOpenReviewInbox,
            onImportAllContacts = onImportAllContacts,
            onDismissMessage = onDismissMessage,
        )
    }
}
