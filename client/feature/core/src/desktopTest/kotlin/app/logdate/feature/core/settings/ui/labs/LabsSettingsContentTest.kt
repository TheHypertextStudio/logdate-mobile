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
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val INTRO = "These features are unfinished."
private const val SECTION = "Unreleased features"
private const val EMPTY = "No unreleased features to try."
private const val WATCH_TITLE = "Redesigned watch app"
private const val ACTIVITY_PERMISSION_TITLE = "Allow physical activity"
private const val WATCH_DESCRIPTION = "Try the new watch app and the phone screens that go with it."

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
    fun `activity awareness that is on without physical activity asks for it`() =
        runDesktopComposeUiTest {
            var asked = 0
            setContent {
                Labs(activityFlag(enabled = true), activityAllowed = false, onAllowActivity = { asked++ })
            }

            onNodeWithText(ACTIVITY_PERMISSION_TITLE).assertIsDisplayed()
            onNodeWithText("Allow").performClick()

            assertEquals(1, asked)
        }

    @Test
    fun `activity awareness asks nothing once physical activity is allowed or while it is off`() =
        runDesktopComposeUiTest {
            var state by mutableStateOf(activityFlag(enabled = true))
            var allowed by mutableStateOf(true)
            setContent { Labs(state, activityAllowed = allowed) }
            onNodeWithText(ACTIVITY_PERMISSION_TITLE).assertDoesNotExist()

            state = activityFlag(enabled = false)
            allowed = false

            onNodeWithText(ACTIVITY_PERMISSION_TITLE).assertDoesNotExist()
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

@Composable
private fun Labs(
    uiState: LabsUiState,
    activityAllowed: Boolean = true,
    onAllowActivity: () -> Unit = {},
    onSetEnabled: (FeatureFlag, Boolean) -> Unit = { _, _ -> },
) {
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        LabsSettingsContent(
            uiState = uiState,
            developerModeEnabled = true,
            onBack = {},
            onSetEnabled = onSetEnabled,
            activityAllowed = activityAllowed,
            onAllowActivity = onAllowActivity,
        )
    }
}
