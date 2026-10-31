package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.AccountSettingsRoute
import app.logdate.feature.core.settings.navigation.PrivacySettingsRoute
import app.logdate.feature.core.settings.navigation.SETTINGS_CATEGORIES_METADATA_KEY
import app.logdate.feature.core.settings.navigation.SettingsActivityCategories
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.core.settings.ui.PrivacySettingsContent
import app.logdate.navigation.scenes.SettingsActivitySceneStrategy
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsActivitySceneTest {
    @Test
    fun wideSettingsComposesCategoriesBesideDetailWithoutHomeOrOverviewDuplication() = verifyLayout(1280, false)

    @Test
    fun directLinkedDetailHasCategoriesWithoutSyntheticRoot() = verifyLayout(1280, true)

    @Test
    fun compactSettingsKeepsDetailFullscreenAndBackReturnsToRoot() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val root = SettingsRoute()
            val stack = mutableStateListOf<NavKey>(HomeRoute, root, PrivacySettingsRoute)
            setContent {
                LogDateTheme {
                    NavDisplay(
                        backStack = stack,
                        onBack = { stack.removeLastOrNull() },
                        sceneStrategies = listOf(SettingsActivitySceneStrategy<NavKey>({ true })),
                        entryProvider =
                            entryProvider {
                                taggedEntry<HomeRoute> { Box(Modifier.testTag("home")) }
                                taggedEntry<SettingsRoute>(ActivityPresentation.SettingsRoot.asMetadata()) {
                                    Box(Modifier.testTag("overview"))
                                }
                                taggedEntry<PrivacySettingsRoute>(detailMetadata("privacy")) {
                                    Box(Modifier.fillMaxSize().testTag("detail")) {
                                        PrivacySettingsContent(
                                            onBack = { stack.removeLastOrNull() },
                                            onSetBiometricsEnabled = {},
                                            isBiometricsEnabled = true,
                                        )
                                    }
                                }
                            },
                    )
                }
            }
            onAllNodesWithTag("categories").assertCountEquals(0)
            assertEquals(411f, onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot.width)
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            onNodeWithTag("overview").assertExists()
            onAllNodesWithTag("home").assertCountEquals(0)
        }

    private fun verifyLayout(
        width: Int,
        directLink: Boolean,
    ) = runDesktopComposeUiTest(width = width, height = 800) {
        val stack = if (directLink) listOf(AccountSettingsRoute) else listOf(HomeRoute, SettingsRoute(), AccountSettingsRoute)
        setContent {
            LogDateTheme {
                NavDisplay(
                    backStack = stack,
                    onBack = {},
                    sceneStrategies = listOf(SettingsActivitySceneStrategy<NavKey>({ true })),
                    entryProvider =
                        entryProvider {
                            taggedEntry<HomeRoute> { Box(Modifier.testTag("home")) }
                            taggedEntry<SettingsRoute>(ActivityPresentation.SettingsRoot.asMetadata()) {
                                Box(Modifier.testTag("overview"))
                            }
                            taggedEntry<AccountSettingsRoute>(detailMetadata()) {
                                Box(Modifier.fillMaxSize().testTag("detail"))
                            }
                        },
                )
            }
        }
        onNodeWithTag("categories").assertExists()
        onAllNodesWithTag("home").assertCountEquals(0)
        onAllNodesWithTag("overview").assertCountEquals(0)
        val categories = onNodeWithTag("categories").fetchSemanticsNode().boundsInRoot
        val detail = onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot
        assertTrue(categories.right <= detail.left)
        assertTrue(detail.width >= 400f)
    }

    private fun detailMetadata(selectedSection: String = "account"): Map<String, Any> =
        ActivityPresentation.SettingsDetail(selectedSection).asMetadata() +
            (
                SETTINGS_CATEGORIES_METADATA_KEY to
                    SettingsActivityCategories { section ->
                        assertEquals(selectedSection, section)
                        Box(Modifier.fillMaxSize().testTag("categories"))
                    }
            )
}
