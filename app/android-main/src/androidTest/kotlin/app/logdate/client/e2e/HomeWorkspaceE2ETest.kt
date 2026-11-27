package app.logdate.client.e2e

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.text.toUiText
import app.logdate.feature.location.timeline.ui.history.HistoryPlaceUi
import app.logdate.feature.location.timeline.ui.history.HistoryTab
import app.logdate.feature.location.timeline.ui.history.HumanHistoryMap
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryState
import app.logdate.feature.rewind.ui.RewindScreenContent
import app.logdate.feature.rewind.ui.overview.RewindHistoryUiState
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.maps.LocalGoogleMapsAvailabilityOverride
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import app.logdate.ui.workspace.WorkspaceSupportingSheet
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The Gradle Managed Device task owns targets and exports runtime screenshots. */
class HomeWorkspaceE2ETest {
    @Test fun placesUseOneSharedSearchAndKeepTheFilterAcrossSectionChanges() {
        val state =
            mutableStateOf(
                HumanLocationHistoryState(
                    "Tuesday, September 29".toUiText(),
                    tab = HistoryTab.Places,
                    places =
                        listOf(
                            HistoryPlaceUi("cafe", "Mothership Coffee".toUiText(), "Visited today".toUiText()),
                            HistoryPlaceUi("library", "Las Vegas Library".toUiText(), "Visited today".toUiText()),
                        ),
                ),
            )
        compose.setContent {
            LogDateTheme {
                WorkspaceScaffold(
                    listOf(WorkspaceDestination("locations", "Places", Icons.Default.Place)),
                    "locations",
                    {},
                    onSearch = {},
                ) {
                    HumanLocationHistoryContent(
                        state.value,
                        HumanLocationHistoryActions(
                            onTab = { state.value = state.value.copy(tab = it) },
                            onPlacesQuery = { state.value = state.value.copy(placesQuery = it) },
                        ),
                        initialSupportingExtent = app.logdate.ui.workspace.SupportingExtent.Expanded,
                    )
                }
            }
        }
        compose.onAllNodesWithTag("workspace_search").assertCountEquals(1)
        compose.onNodeWithTag("workspace_search").performTextInput("Library")
        compose.onNodeWithText("Las Vegas Library").assertIsDisplayed()
        compose.onNodeWithText("Mothership Coffee").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose section").performClick()
        compose.onNodeWithText("Your day").performClick()
        compose.onNodeWithText("Search your log").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose section").performClick()
        compose.onNodeWithText("Your places").performClick()
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Mothership Coffee").assertDoesNotExist()
    }

    @Test fun rewindStoriesRemainDistinctWhileBrowsing() {
        val current = Uuid.random()
        val previous = Uuid.random()
        var opened: Uuid? = null
        val state =
            RewindOverviewScreenUiState.Ready(
                pastRewinds =
                    listOf(
                        RewindHistoryUiState(
                            previous,
                            "A week close to home",
                            "Last week",
                            LocalDate(2026, 9, 13),
                            LocalDate(2026, 9, 19),
                            "Small things worth keeping",
                            highlightedQuote = "The kitchen was full of conversation.",
                        ),
                    ),
                mostRecentRewind =
                    RewindPreviewUiState(
                        "A little time outside",
                        current,
                        "This week",
                        "Room to wander",
                        LocalDate(2026, 9, 20),
                        LocalDate(2026, 9, 26),
                        rewindAvailable = true,
                        isViewed = false,
                    ),
            )
        compose.setContent {
            LogDateTheme {
                CompositionLocalProvider(LocalReduceMotionOverride provides false) {
                    WorkspaceScaffold(
                        listOf(WorkspaceDestination("rewind", "Rewind", Icons.Default.History)),
                        "rewind",
                        {},
                        onSearch = {},
                    ) { RewindScreenContent(state, { opened = it }) }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("Room to wander"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        capture("workspace-rewind-current")
        compose
            .onNode(hasScrollAction() and hasAnyDescendant(hasText("Room to wander")))
            .performScrollToNode(hasText("A week close to home"))
        compose.onNodeWithText("A week close to home").assertIsDisplayed()
        capture("workspace-rewind-previous")
        compose.onNodeWithText("The kitchen was full of conversation.").performClick()
        compose.runOnIdle { assertTrue(opened == previous) }
    }

    @Test fun accountMenuKeepsBackupAndStreakOutOfTheHeader() {
        var openedBackup = false
        var openedStreak = false
        compose.setContent {
            LogDateTheme {
                WorkspaceScaffold(
                    listOf(WorkspaceDestination("locations", "Places", Icons.Default.Place)),
                    "locations",
                    {},
                    onSearch = {},
                    actions = {
                        app.logdate.feature.core.main.HomeWorkspaceAccountAction(
                            app.logdate.feature.core.sync.SyncPresentation
                                .Pending(2),
                            app.logdate.ui.streak
                                .CampfirePresentation(app.logdate.ui.streak.CampfirePhase.BURNING, runDays = 4),
                            {},
                            { openedStreak = true },
                            { openedBackup = it == app.logdate.feature.core.sync.SyncAction.OpenStatus },
                        )
                    },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithTag("logdate_home_sync_status").assertDoesNotExist()
        compose.onNodeWithText("Journaling streak").assertDoesNotExist()
        capture("workspace-quiet-header")
        compose.onNodeWithTag("workspace_account").performClick()
        compose.onNodeWithText("Backup status").assertIsDisplayed()
        capture("workspace-account-menu")
        compose.onNodeWithText("Backup status").performClick()
        compose.runOnIdle { assertTrue(openedBackup) }
        compose.onNodeWithTag("workspace_account").performClick()
        compose.onNodeWithText("Journaling streak").performClick()
        compose.runOnIdle { assertTrue(openedStreak) }
    }

    @Test fun compactLargeTextRecoveryStillAllowsHistoryAndResume() {
        var resumed = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(density.density, 2f),
            ) {
                LogDateTheme {
                    Box(Modifier.fillMaxSize()) {
                        WorkspaceScaffold(
                            listOf(WorkspaceDestination("locations", "Places", Icons.Default.Place)),
                            "locations",
                            {},
                            modifier = Modifier.size(320.dp, 568.dp),
                            onSearch = {},
                            status = { androidx.compose.material3.Text("Some memories are waiting to sync") },
                        ) {
                            app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent(
                                app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryState(
                                    "Today".toUiText(),
                                    recoveryMessage = "Recording was interrupted".toUiText(),
                                    recoveryActionLabel = "Resume recording".toUiText(),
                                ),
                                app.logdate.feature.location.timeline.ui.history
                                    .HumanLocationHistoryActions(onRecover = { resumed = true }),
                                mapContent = {
                                    androidx.compose.foundation.layout
                                        .Box(it)
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Browse").performClick()
        compose.onNodeWithText("Resume recording").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(resumed) }
        compose.onNodeWithContentDescription("Show map").performClick()
        compose.onNodeWithContentDescription("Browse").assertIsDisplayed()
    }

    @Test fun systemBackClosesTheSelectedPlaceAndRestoresBrowsing() {
        compose.setContent {
            LogDateTheme {
                WorkspaceScaffold(
                    listOf(WorkspaceDestination("locations", "Places", Icons.Default.Place)),
                    "locations",
                    {},
                    onSearch = {},
                ) {
                    var selected by androidx.compose.runtime.saveable
                        .rememberSaveable { mutableStateOf<String?>(null) }
                    app.logdate.ui.common
                        .PlatformBackHandler(enabled = selected != null) { selected = null }
                    WorkspaceSupportingSheet(
                        "Two places",
                        initialExtent = app.logdate.ui.workspace.SupportingExtent.Browsing,
                        supportingContextKey = selected,
                        focus = {
                            androidx.compose.foundation.layout
                                .Box(Modifier.fillMaxSize())
                        },
                        supporting = {
                            if (selected ==
                                null
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = { selected = "cafe" },
                                ) { androidx.compose.material3.Text("Open coffee") }
                            } else {
                                androidx.compose.material3.Text("Coffee memories")
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Open coffee").performClick()
        compose.onNodeWithText("Coffee memories").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Open coffee").assertIsDisplayed()
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun configureSharedSystemBars() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    @Test fun nativeMapRemainsAvailableWhileBrowsingAndReturningFromFocusedContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyId = context.resources.getIdentifier("google_maps_api_key", "string", context.packageName)
        assumeTrue(
            "Native-map acceptance requires the existing debug Maps API configuration",
            keyId != 0 && context.getString(keyId).isNotBlank(),
        )
        val selected = mutableStateOf<String?>(null)
        val time = Instant.parse("2026-09-29T10:00:00Z")
        val cafe = SemanticPlace("cafe", "Mothership Coffee", 36.1662, -115.1431)
        val library = SemanticPlace("library", "Las Vegas Library", 36.1624, -115.1452)
        val visits = listOf(cafe, library).map { PlaceVisit(it.id, time, time, listOf(it.id), it.latitude, it.longitude, true, it) }
        compose.setContent {
            LogDateTheme {
                WorkspaceScaffold(
                    listOf(WorkspaceDestination("locations", "Places", Icons.Default.Place)),
                    "locations",
                    {},
                    onSearch = {},
                ) {
                    WorkspaceSupportingSheet("Two places in your day", focus = {
                        HumanHistoryMap(visits, selected.value, { selected.value = it }, Modifier.fillMaxSize(), {}, "fixture-day")
                    }, supporting = { androidx.compose.material3.Text("Mothership Coffee") })
                }
            }
        }
        awaitNativeMap(context.getString(keyId))
        compose.onNodeWithTag("history-native-map").assertIsDisplayed()
        capture("workspace-native-map-peek")
        // On wider managed devices the supporting panel is already expanded.
        if (compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("Browse"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            compose.onNodeWithText("Browse").performClick()
            compose.onNodeWithText("Mothership Coffee").assertIsDisplayed()
            capture("workspace-native-map-browsing")
            compose.onNodeWithText("Full view").performClick()
            compose.onNodeWithText("Show map").performClick()
        }
        compose.onNodeWithTag("history-native-map").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Map loaded"))
    }

    @Test fun unavailableMapKeepsHistoryBrowsableAndRestoresTheMapContext() {
        compose.setContent {
            LogDateTheme {
                CompositionLocalProvider(LocalGoogleMapsAvailabilityOverride provides false) {
                    WorkspaceScaffold(
                        listOf(WorkspaceDestination("locations", "Places", Icons.Default.Place)),
                        "locations",
                        {},
                        onSearch = {},
                    ) {
                        WorkspaceSupportingSheet("Two places in your day", focus = {
                            HumanHistoryMap(emptyList(), null, {}, Modifier.fillMaxSize(), {}, "offline-day")
                        }, supporting = { androidx.compose.material3.Text("Mothership Coffee") })
                    }
                }
            }
        }
        compose.onNodeWithText("Map unavailable").assertIsDisplayed()
        capture("workspace-map-fallback-peek")
        if (compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("Browse"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            compose.onNodeWithText("Browse").performClick()
            compose.onNodeWithText("Mothership Coffee").assertIsDisplayed()
            capture("workspace-map-fallback-browsing")
            compose.onNodeWithText("Full view").performClick()
            compose.onNodeWithText("Show map").performClick()
        }
        compose.onNodeWithText("Map unavailable").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val directory =
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: error("Use a Gradle Managed Device task for workspace acceptance")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val file = File(directory, "$name-${device.displayWidth}x${device.displayHeight}.png")
        file.parentFile?.mkdirs()
        assertTrue(device.takeScreenshot(file))
    }

    private fun awaitNativeMap(apiKey: String) {
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose
                    .onAllNodes(
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Map loaded"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            val directory =
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: throw failure
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val prefix = "workspace-native-map-failure-${device.displayWidth}x${device.displayHeight}"
            runCatching {
                File(directory).mkdirs()
                device.takeScreenshot(File(directory, "$prefix.png"))
                File(directory, "$prefix-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
                val logs =
                    device
                        .executeShellCommand(
                            "logcat -d -s 'Google Maps Android API' 'MapsInitializer' 'DynamiteModule' 'GooglePlayServicesUtil' '*:S'",
                        ).replace(apiKey, "[REDACTED]")
                        .replace(Regex("AIza[0-9A-Za-z_-]+"), "[REDACTED]")
                File(directory, "$prefix-maps.txt").writeText(logs)
            }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }
}
