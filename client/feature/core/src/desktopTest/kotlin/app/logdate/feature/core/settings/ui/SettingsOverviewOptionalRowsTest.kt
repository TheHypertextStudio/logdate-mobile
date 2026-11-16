@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val WINDOW_WIDTH = 411
private const val WINDOW_HEIGHT = 1600
private const val PEOPLE = "People"
private const val EVENTS = "Events"
private const val LAST_PERSONAL_ROW = "Your library"

/**
 * The overview lists People only while it is turned on in Labs and Events only once Events has
 * launched. A row for either one otherwise opens a page that closes at once.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsOverviewOptionalRowsTest {
    @Test
    fun `with People off and Events not launched the overview lists neither`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent { Overview(showPeople = false, showEvents = false) }

            onNodeWithText(LAST_PERSONAL_ROW).assertExists()
            onAllNodesWithText(PEOPLE).assertCountEquals(0)
            onAllNodesWithText(EVENTS).assertCountEquals(0)
        }

    @Test
    fun `with People on the overview lists it and the row opens People settings`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var opened = 0
            setContent { Overview(showPeople = true, showEvents = false, onOpenPeople = { opened++ }) }

            onNodeWithText(PEOPLE).performClick()

            assertEquals(1, opened)
            onAllNodesWithText(EVENTS).assertCountEquals(0)
        }

    @Test
    fun `once Events is available the overview lists it and the row opens Events settings`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            var opened = 0
            setContent { Overview(showPeople = false, showEvents = true, onOpenEvents = { opened++ }) }

            onNodeWithText(EVENTS).performClick()

            assertEquals(1, opened)
            onAllNodesWithText(PEOPLE).assertCountEquals(0)
        }

    @Test
    fun `the optional rows keep People out until its stored value is read and then follow it`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val flags = StoredPeopleFlag(initial = null)
            val switch = peopleSwitch(flags)
            setContent {
                ResumedLifecycle {
                    val rows = rememberOptionalSettingsRows(switch)
                    Overview(showPeople = rows.people, showEvents = rows.events)
                }
            }
            onNodeWithText(LAST_PERSONAL_ROW).assertExists()
            onAllNodesWithText(PEOPLE).assertCountEquals(0)

            runOnIdle { flags.set(enabled = true) }
            onNodeWithText(PEOPLE).assertExists()

            runOnIdle { flags.set(enabled = false) }
            onAllNodesWithText(PEOPLE).assertCountEquals(0)
        }

    @Test
    fun `the optional rows list Events only once Events has launched`() =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            val switch = peopleSwitch(StoredPeopleFlag(initial = true))
            setContent {
                ResumedLifecycle {
                    val rows = rememberOptionalSettingsRows(switch)
                    Overview(showPeople = rows.people, showEvents = rows.events)
                }
            }

            onAllNodesWithText(EVENTS).assertCountEquals(if (FeatureFlag.EVENTS.availableForLaunch) 1 else 0)
            onNodeWithText(PEOPLE).assertExists()
        }
}

@Composable
private fun Overview(
    showPeople: Boolean,
    showEvents: Boolean,
    onOpenPeople: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
) {
    provideFoldableLayoutInfo(FoldableLayoutInfo()) {
        LogDateTheme(dynamicColor = false, darkTheme = false) {
            SettingsOverviewContent(
                onBack = {},
                onNavigateToProfile = {},
                onNavigateToAccount = {},
                onNavigateToDevices = {},
                onNavigateToReset = {},
                onNavigateToLocation = {},
                onNavigateToPrivacy = {},
                onNavigateToMemories = {},
                onNavigateToEventsSettings = onOpenEvents,
                onNavigateToPeopleSettings = onOpenPeople,
                onNavigateToSync = {},
                onNavigateToExport = {},
                userProfile = UserProfile(name = "Alex Rivera", username = "alex", isAuthenticated = true),
                showEventsSettings = showEvents,
                showPeopleSettings = showPeople,
            )
        }
    }
}
