package app.logdate.client.e2e

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.LocationSettingsRoute
import app.logdate.feature.core.settings.navigation.PrivacySettingsRoute
import app.logdate.feature.core.settings.navigation.SettingsActivityCategories
import app.logdate.feature.core.settings.navigation.SettingsCategory
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.core.settings.navigation.navigateToSettingsCategory
import app.logdate.feature.core.settings.navigation.settingsDetailMetadata
import app.logdate.feature.core.settings.ui.LocationSettingsContent
import app.logdate.feature.core.settings.ui.PrivacySettingsContent
import app.logdate.feature.core.settings.ui.SettingsCategoryNavigation
import app.logdate.feature.core.settings.ui.SettingsOverviewContent
import app.logdate.feature.core.settings.ui.UserProfile
import app.logdate.navigation.rememberWorkspaceRouteDecorator
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.navigation.scenes.SettingsActivitySceneStrategy
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.WorkspaceAccountAction
import app.logdate.ui.workspace.WorkspaceAccountIndicator
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One real NavDisplay and activity scenes. Run only on emulators or Gradle Managed Devices. */
@RunWith(AndroidJUnit4::class)
class ActivityOwnershipE2ETest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsOwnsItsCategoriesAndBackRestoresHomeDestinationAndScroll() {
        val stack = NavBackStack<NavKey>(HomeRoute)
        val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
        val categories =
            SettingsActivityCategories { selected ->
                SettingsCategoryNavigation(
                    selectedSection = selected,
                    onNavigateTo = stack::navigateToSettingsCategory,
                    onBack = back,
                    onNavigateToProfile = {},
                    isAuthenticated = true,
                )
            }
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            LogDateTheme {
                NavDisplay(
                    backStack = stack,
                    onBack = back,
                    sceneStrategies =
                        listOf(
                            SettingsActivitySceneStrategy<NavKey>({ true }),
                            RelatedContentSceneStrategy<NavKey>({ true }),
                        ),
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberWorkspaceRouteDecorator(),
                        ),
                    entryProvider =
                        entryProvider {
                            taggedEntry<HomeRoute>(ActivityPresentation.Home.asMetadata()) {
                                RetainedHome { stack.add(SettingsRoute()) }
                            }
                            taggedEntry<SettingsRoute>(ActivityPresentation.SettingsRoot.asMetadata()) {
                                Box(Modifier.fillMaxSize().testTag("settings_overview")) {
                                    SettingsOverviewContent(
                                        onBack = back,
                                        onNavigateToProfile = {},
                                        onNavigateToAccount = {},
                                        onNavigateToDevices = {},
                                        onNavigateToReset = {},
                                        onNavigateToLocation = { stack.navigateToSettingsCategory(LocationSettingsRoute) },
                                        onNavigateToPrivacy = { stack.navigateToSettingsCategory(PrivacySettingsRoute) },
                                        onNavigateToMemories = {},
                                        onNavigateToSync = {},
                                        onNavigateToExport = {},
                                        userProfile = UserProfile("Alice", "alice", isAuthenticated = true),
                                    )
                                }
                            }
                            taggedEntry<PrivacySettingsRoute>(settingsDetailMetadata(SettingsCategory.Privacy, categories)) {
                                Box(Modifier.fillMaxSize().testTag("privacy_detail")) {
                                    PrivacySettingsContent(
                                        onBack = back,
                                        onSetBiometricsEnabled = {},
                                        isBiometricsEnabled = true,
                                        onNavigateToLocationSettings = { stack.add(LocationSettingsRoute) },
                                    )
                                }
                            }
                            taggedEntry<LocationSettingsRoute>(settingsDetailMetadata(SettingsCategory.Location, categories)) {
                                Box(Modifier.fillMaxSize().testTag("location_detail")) {
                                    LocationSettingsContent(
                                        settings = LocationTrackingSettings(),
                                        onBack = back,
                                        onToggleBackgroundTracking = {},
                                        onSetCaptureMode = {},
                                        onShowLocationTimeline = {},
                                        onNavigateToTrackingOptions = {},
                                        onNavigateToInterval = {},
                                    )
                                }
                            }
                        },
                )
            }
        }
        compose.onNodeWithTag("workspace_account").assertIsDisplayed()
        compose.onNodeWithText("Places").performClick()
        compose.onNodeWithTag("home_context").performScrollToNode(hasText("Places memory 40"))
        compose.onNodeWithText("Places memory 40").performClick()
        compose.onNodeWithTag("settings_overview").assertIsDisplayed()
        assertHomeAbsent()
        selectSetting("settings_overview", "Privacy & Security")
        compose.onNodeWithTag("privacy_detail").assertIsDisplayed()
        assertHomeAbsent()
        val wide = compose.onAllNodesWithTag("settings_categories").fetchSemanticsNodes().isNotEmpty()
        capture("settings-activity")
        if (wide) {
            val categoryBounds = compose.onNodeWithTag("settings_categories").fetchSemanticsNode().boundsInRoot
            val detailBounds = compose.onNodeWithTag("privacy_detail").fetchSemanticsNode().boundsInRoot
            assertTrue("Settings categories belong beside their section", categoryBounds.right <= detailBounds.left)
            selectSetting("settings_categories", "Location Settings")
            compose.runOnIdle { assertEquals(3, stack.size) }
        } else {
            selectSetting("privacy_detail", "Location Settings")
            compose.runOnIdle { assertEquals(4, stack.size) }
        }
        compose.onNodeWithTag("location_detail").assertIsDisplayed()
        assertHomeAbsent()
        systemBack()
        if (!wide) {
            compose.onNodeWithTag("privacy_detail").assertIsDisplayed()
            systemBack()
        }
        compose.onNodeWithTag("settings_overview").assertIsDisplayed()
        assertHomeAbsent()
        systemBack()
        compose.onNodeWithTag("home_context").assertIsDisplayed()
        compose.onAllNodesWithTag("workspace_search").assertCountEquals(1)
        compose.onNodeWithTag("workspace_account").assertIsDisplayed()
        compose.onNodeWithText("Places memory 40").assertIsDisplayed()
        capture("home-restored")
        compose.runOnIdle { assertEquals(listOf(HomeRoute), stack.toList()) }
    }

    private fun assertHomeAbsent() {
        compose.onAllNodesWithTag("home_context").assertCountEquals(0)
        compose.onAllNodesWithTag("workspace_search").assertCountEquals(0)
        compose.onAllNodesWithTag("workspace_account").assertCountEquals(0)
    }

    private fun selectSetting(
        paneTag: String,
        title: String,
    ) {
        val inPane = hasAnyAncestor(hasTestTag(paneTag))
        val setting = hasText(title) and hasClickAction()
        compose.onNode(hasScrollAction() and inPane).performScrollToNode(setting)
        compose.onNode(setting and inPane).performClick()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val directory = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val output = File(directory, "activity-ownership").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(output, "$name-${device.displayWidth}x${device.displayHeight}.png")))
    }
}

@Composable
private fun RetainedHome(onOpenSettings: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf("timeline") }
    val scroll = rememberLazyListState()
    WorkspaceScaffold(
        destinations =
            listOf(
                WorkspaceDestination("timeline", "Timeline", Icons.Rounded.History),
                WorkspaceDestination("places", "Places", Icons.Rounded.Place),
            ),
        selectedKey = destination,
        onSelect = { destination = it },
        onSearch = {},
        actions = {
            WorkspaceAccountAction(WorkspaceAccountIndicator.None, null) { dismiss ->
                TextButton(onClick = {
                    dismiss()
                    onOpenSettings()
                }) { Text("Settings") }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("home_context"), state = scroll) {
            items((1..60).toList(), key = { it }) { index ->
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("${if (destination == "places") "Places" else "Timeline"} memory $index")
                }
            }
        }
    }
}
