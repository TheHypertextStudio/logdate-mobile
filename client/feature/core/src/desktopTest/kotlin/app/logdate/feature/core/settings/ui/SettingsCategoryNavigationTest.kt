package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavKey
import app.logdate.client.domain.identity.ResolvedUserIdentity
import app.logdate.feature.core.settings.navigation.PeopleSettingsRoute
import app.logdate.feature.core.settings.navigation.PrivacySettingsRoute
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

private val signedInIdentity =
    ResolvedUserIdentity(
        displayName = "Alex Rivera",
        username = "alex",
        profilePhotoUri = null,
        bio = null,
        birthday = null,
        onboardedDate = null,
        isAuthenticated = true,
        cloudAccountId = null,
    )

@OptIn(ExperimentalTestApi::class)
class SettingsCategoryNavigationTest {
    @Test
    fun signedOutCategoriesKeepTheSelectedDirectLinkedAccountReachable() =
        runDesktopComposeUiTest(width = 360, height = 900) {
            setContent {
                LogDateTheme {
                    SettingsCategoryNavigation(
                        selectedSection = "account",
                        onNavigateTo = {},
                        onBack = {},
                        onNavigateToProfile = {},
                        isAuthenticated = false,
                    )
                }
            }
            onNodeWithText("Account").assertIsSelected()
            onAllNodesWithText("Devices").assertCountEquals(0)
        }

    @Test
    fun selectedSectionIsVisibleAndAnotherCategoryNavigates() =
        runDesktopComposeUiTest(width = 360, height = 900) {
            var navigated: NavKey? = null
            setContent {
                LogDateTheme {
                    SettingsCategoryNavigation(
                        selectedSection = "account",
                        onNavigateTo = { navigated = it },
                        onBack = {},
                        onNavigateToProfile = {},
                    )
                }
            }
            onNodeWithText("Account").performScrollTo().assertIsSelected()
            onNodeWithText("Privacy & Security").performScrollTo().assertIsNotSelected().performClick()
            assertEquals(PrivacySettingsRoute, navigated)
        }

    @Test
    fun peopleIsNotListedWhileItIsOffEvenWhenAPeopleRouteIsSelected() =
        runDesktopComposeUiTest(width = 360, height = 900) {
            setContent {
                LogDateTheme {
                    SettingsCategoryNavigation(
                        selectedSection = "people",
                        onNavigateTo = {},
                        onBack = {},
                        onNavigateToProfile = {},
                        showPeople = false,
                    )
                }
            }
            onAllNodesWithText("People").assertCountEquals(0)
        }

    @Test
    fun peopleIsListedAndOpensPeopleSettingsWhileItIsOn() =
        runDesktopComposeUiTest(width = 360, height = 900) {
            var navigated: NavKey? = null
            setContent {
                LogDateTheme {
                    SettingsCategoryNavigation(
                        selectedSection = "account",
                        onNavigateTo = { navigated = it },
                        onBack = {},
                        onNavigateToProfile = {},
                        showPeople = true,
                    )
                }
            }
            onNodeWithText("People").performScrollTo().assertIsNotSelected().performClick()
            assertEquals(PeopleSettingsRoute, navigated)
        }

    @Test
    fun theCategoryListScreenKeepsPeopleOutUntilItsStoredValueIsReadAndThenFollowsIt() =
        runDesktopComposeUiTest(width = 360, height = 900) {
            val flags = StoredPeopleFlag(initial = null)
            val switch = peopleSwitch(flags)
            setContent {
                LogDateTheme {
                    ResumedLifecycle {
                        SettingsCategoryNavigationScreen(
                            selectedSection = "people",
                            onNavigateTo = {},
                            onBack = {},
                            onNavigateToProfile = {},
                            userIdentity = flowOf(signedInIdentity),
                            peopleSwitch = switch,
                        )
                    }
                }
            }
            onAllNodesWithText("People").assertCountEquals(0)

            runOnIdle { flags.set(enabled = true) }
            onNodeWithText("People").assertIsSelected()

            runOnIdle { flags.set(enabled = false) }
            onAllNodesWithText("People").assertCountEquals(0)
        }

    @Test
    fun aCategoryThatAppearsBelowTheSelectionLeavesTheScrollPositionAlone() =
        runDesktopComposeUiTest(width = 360, height = 480) {
            var showPeople by mutableStateOf(false)
            setContent {
                LogDateTheme {
                    SettingsCategoryNavigation(
                        selectedSection = "account",
                        onNavigateTo = {},
                        onBack = {},
                        onNavigateToProfile = {},
                        showPeople = showPeople,
                    )
                }
            }
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("About LogDate"))
            onNodeWithText("About LogDate").assertIsDisplayed()

            runOnIdle { showPeople = true }

            onNodeWithText("About LogDate").assertIsDisplayed()
        }

    @Test
    fun eventsIsNotListedWhileEventsIsDeferred() =
        runDesktopComposeUiTest(width = 360, height = 900) {
            setContent {
                LogDateTheme {
                    SettingsCategoryNavigation(
                        selectedSection = "account",
                        onNavigateTo = {},
                        onBack = {},
                        onNavigateToProfile = {},
                        showPeople = true,
                    )
                }
            }
            onAllNodesWithText("Events").assertCountEquals(0)
        }
}
