@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.labs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.feature.core.activity.PhoneActivityLink
import app.logdate.feature.core.activity.PhoneActivityLinkState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val INTRO = "These features are unfinished."
private const val SECTION = "Unreleased features"
private const val EMPTY = "No unreleased features to try."
private const val WATCH_TITLE = "Redesigned watch app"
private const val ACTIVITY_LINK_ACTION = "Link phone activity"
private const val WATCH_DESCRIPTION = "Try the new watch app and the phone screens that go with it."
private const val PEOPLE_TITLE = "People"
private const val PEOPLE_DESCRIPTION = "Keeps a list of the people in your entries."

/**
 * The Labs page is the only place a tester can turn an unreleased feature on in a release build, so
 * a tap has to report the opposite of what the switch shows, and a platform that offers nothing has
 * to say so instead of showing an empty section.
 */
@OptIn(ExperimentalTestApi::class)
class LabsSettingsContentTest {
    @Test
    fun `tapping a flag that is off asks to turn it on`() =
        runDesktopComposeUiTest {
            val requests = mutableListOf<Pair<FeatureFlag, Boolean>>()
            setContent { Labs(watchFlag(enabled = false)) { flag, enabled -> requests += flag to enabled } }
            onNode(isToggleable()).assertIsOff()

            onNodeWithText(WATCH_TITLE).performClick()

            assertEquals(listOf(FeatureFlag.WATCH_EXPERIENCE_V2 to true), requests)
        }

    @Test
    fun `tapping a flag that is on asks to turn it off`() =
        runDesktopComposeUiTest {
            val requests = mutableListOf<Pair<FeatureFlag, Boolean>>()
            setContent { Labs(watchFlag(enabled = true)) { flag, enabled -> requests += flag to enabled } }
            onNode(isToggleable()).assertIsOn()

            onNodeWithText(WATCH_TITLE).performClick()

            assertEquals(listOf(FeatureFlag.WATCH_EXPERIENCE_V2 to false), requests)
        }

    @Test
    fun `tapping the switch itself reports the opposite of what it shows`() =
        runDesktopComposeUiTest {
            val requests = mutableListOf<Pair<FeatureFlag, Boolean>>()
            setContent { Labs(watchFlag(enabled = true)) { flag, enabled -> requests += flag to enabled } }

            onNode(isToggleable()).performClick()

            assertEquals(listOf(FeatureFlag.WATCH_EXPERIENCE_V2 to false), requests)
        }

    @Test
    fun `a flag shows its title and description under the unreleased features`() =
        runDesktopComposeUiTest {
            setContent { Labs(watchFlag(enabled = false)) }

            onNodeWithText(SECTION).assertIsDisplayed()
            onNodeWithText(WATCH_TITLE).assertIsDisplayed()
            onNodeWithText(WATCH_DESCRIPTION).assertIsDisplayed()
            onNodeWithText(EMPTY).assertDoesNotExist()
        }

    @Test
    fun `People shows its title and description and a tap asks to turn it on`() =
        runDesktopComposeUiTest {
            val requests = mutableListOf<Pair<FeatureFlag, Boolean>>()
            setContent { Labs(peopleFlag(enabled = false)) { flag, enabled -> requests += flag to enabled } }
            onNodeWithText(PEOPLE_DESCRIPTION).assertIsDisplayed()
            onNode(isToggleable()).assertIsOff()

            onNodeWithText(PEOPLE_TITLE).performClick()

            assertEquals(listOf(FeatureFlag.PEOPLE to true), requests)
        }

    @Test
    fun `People that is on asks to turn off when tapped`() =
        runDesktopComposeUiTest {
            val requests = mutableListOf<Pair<FeatureFlag, Boolean>>()
            setContent { Labs(peopleFlag(enabled = true)) { flag, enabled -> requests += flag to enabled } }
            onNode(isToggleable()).assertIsOn()

            onNodeWithText(PEOPLE_TITLE).performClick()

            assertEquals(listOf(FeatureFlag.PEOPLE to false), requests)
        }

    @Test
    fun `the page lists People above the watch app as Android offers them`() =
        runDesktopComposeUiTest {
            val offered =
                LabsUiState.Ready(
                    listOf(
                        LabsFlag(FeatureFlag.PEOPLE, enabled = false),
                        LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = false),
                    ),
                )
            setContent { Labs(offered) }

            val people = onNodeWithText(PEOPLE_TITLE).fetchSemanticsNode().boundsInRoot
            val watch = onNodeWithText(WATCH_TITLE).fetchSemanticsNode().boundsInRoot

            assertTrue(people.top < watch.top)
            onAllNodes(isToggleable()).assertCountEquals(2)
        }

    @Test
    fun `People keeps its switch beside the phone activity link`() =
        runDesktopComposeUiTest {
            val requests = mutableListOf<Pair<FeatureFlag, Boolean>>()
            var linked = 0
            val offered =
                LabsUiState.Ready(
                    listOf(
                        LabsFlag(FeatureFlag.PEOPLE, enabled = false),
                        LabsFlag(FeatureFlag.ACTIVITY_AWARENESS, enabled = false),
                    ),
                )
            setContent {
                Labs(
                    offered,
                    activityLink = PhoneActivityLink(PhoneActivityLinkState.Off, onLink = { linked++ }),
                    onSetEnabled = { flag, enabled -> requests += flag to enabled },
                )
            }
            onNodeWithText(PEOPLE_DESCRIPTION).assertIsDisplayed()
            onAllNodes(isToggleable()).assertCountEquals(1)

            onNodeWithText(PEOPLE_TITLE).performClick()
            onNodeWithText(ACTIVITY_LINK_ACTION).performClick()

            assertEquals(listOf(FeatureFlag.PEOPLE to true), requests)
            assertEquals(1, linked)
        }

    @Test
    fun `the activity link uses consent rather than immediately flipping the Labs flag`() =
        runDesktopComposeUiTest {
            var linked = 0
            val flags = mutableListOf<Pair<FeatureFlag, Boolean>>()
            setContent {
                Labs(
                    activityFlag(enabled = false),
                    activityLink = PhoneActivityLink(PhoneActivityLinkState.Off, onLink = { linked++ }),
                    onSetEnabled = { flag, enabled -> flags += flag to enabled },
                )
            }

            assertEquals(0, linked)
            onNodeWithText(ACTIVITY_LINK_ACTION).performClick()

            assertEquals(1, linked)
            assertEquals(emptyList(), flags)
        }

    @Test
    fun `a stored activity flag does not display a successful link before provider registration`() =
        runDesktopComposeUiTest {
            var link by mutableStateOf(PhoneActivityLinkState.Connecting)
            setContent { Labs(activityFlag(enabled = true), activityLink = PhoneActivityLink(link)) }
            onNodeWithText("Unlink phone activity").assertDoesNotExist()

            runOnIdle { link = PhoneActivityLinkState.Linked }

            onNodeWithText("Unlink phone activity").assertIsDisplayed()
        }

    @Test
    fun `with no flags the page says nothing is in testing and shows no switch`() =
        runDesktopComposeUiTest {
            setContent { Labs(LabsUiState.Ready(emptyList())) }

            onNodeWithText(EMPTY).assertIsDisplayed()
            onNodeWithText(SECTION).assertDoesNotExist()
            onAllNodes(isToggleable()).assertCountEquals(0)
        }

    @Test
    fun `while the flags load the page shows only its introduction`() =
        runDesktopComposeUiTest {
            setContent { Labs(LabsUiState.Loading) }

            onNodeWithText(INTRO, substring = true).assertIsDisplayed()
            onNodeWithText(EMPTY).assertDoesNotExist()
            onNodeWithText(SECTION).assertDoesNotExist()
            onAllNodes(isToggleable()).assertCountEquals(0)
        }
}

private fun watchFlag(enabled: Boolean): LabsUiState = LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled)))

private fun activityFlag(enabled: Boolean): LabsUiState = LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.ACTIVITY_AWARENESS, enabled)))

private fun peopleFlag(enabled: Boolean): LabsUiState = LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.PEOPLE, enabled)))

@Composable
private fun Labs(
    uiState: LabsUiState,
    activityLink: PhoneActivityLink = PhoneActivityLink(PhoneActivityLinkState.Unavailable),
    onSetEnabled: (FeatureFlag, Boolean) -> Unit = { _, _ -> },
) {
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        LabsSettingsContent(
            uiState = uiState,
            onBack = {},
            onSetEnabled = onSetEnabled,
            activityLink = activityLink,
        )
    }
}
