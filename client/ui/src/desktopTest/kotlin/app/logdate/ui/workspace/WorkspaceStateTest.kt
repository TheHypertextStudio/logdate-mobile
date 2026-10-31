package app.logdate.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class WorkspaceStateTest {
    @Test fun supportingSectionHeaderRemainsInsideThePhoneSheet() =
        runDesktopComposeUiTest(width = 320, height = 700) {
            setContent {
                LogDateTheme {
                    WorkspaceSupportingSheet(
                        "Six visits",
                        header = { Text("Choose section") },
                        focus = { Box(Modifier.fillMaxSize().testTag("map")) },
                        supporting = { Text("Mothership Coffee") },
                    )
                }
            }
            val map = onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
            val header = onNodeWithText("Choose section").fetchSemanticsNode().boundsInRoot
            kotlin.test.assertEquals(0f, map.top)
            kotlin.test.assertTrue(header.top > map.height / 2)
        }

    @Test fun standaloneWorkspaceDetailsReceiveAnAdaptiveHost() =
        runDesktopComposeUiTest(width = 411, height = 700) {
            setContent {
                LogDateTheme {
                    WorkspaceRouteFrame {
                        Text(
                            if (LocalWorkspaceHosted.current &&
                                LocalPanelLayoutInfo.current != null
                            ) {
                                "Hosted detail"
                            } else {
                                "Unframed detail"
                            },
                        )
                    }
                }
            }
            onNodeWithText("Hosted detail").assertIsDisplayed()
        }

    @Test fun shortLandscapeStillOffersHistoryAndMapNavigation() =
        runDesktopComposeUiTest(width = 500, height = 170) {
            setContent {
                LogDateTheme {
                    WorkspaceSupportingSheet(
                        "Six visits",
                        focus = { Box(Modifier.fillMaxSize().testTag("map")) },
                        supporting = { Text("Mothership Coffee") },
                    )
                }
            }
            onNodeWithText("Browse").performClick()
            onNodeWithText("Mothership Coffee").assertIsDisplayed()
            onNodeWithText("Show map").performClick()
            onNodeWithTag("map").assertIsDisplayed()
            onNodeWithText("Browse").assertIsDisplayed()
        }

    @Test fun removedDestinationDoesNotCrashBeforeShellReconciliation() =
        runDesktopComposeUiTest(width = 411, height = 700) {
            setContent {
                LogDateTheme {
                    WorkspaceScaffold(
                        listOf(WorkspaceDestination("timeline", "Timeline", Icons.Default.Place)),
                        "library",
                        {},
                    ) { Text("Available content") }
                }
            }
            onNodeWithText("Available content").assertIsDisplayed()
        }

    @Test fun switchingDestinationsRestoresTheirOwnContext() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    var selected by rememberSaveable { mutableStateOf("day") }
                    WorkspaceScaffold(
                        listOf(
                            WorkspaceDestination("day", "Locations", Icons.Default.Place),
                            WorkspaceDestination("places", "Journals", Icons.Default.Place),
                        ),
                        selected,
                        { selected = it },
                    ) {
                        var date by rememberSaveable { mutableStateOf("Today") }
                        WorkspacePanel { TextButton(onClick = { date = "Yesterday" }) { Text(date) } }
                    }
                }
            }
            onNodeWithText("Today").performClick()
            onNodeWithText("Journals").performClick()
            onNodeWithText("Today").assertIsDisplayed()
            onNodeWithText("Locations").performClick()
            onNodeWithText("Yesterday").assertIsDisplayed()
        }

    @Test fun accessibleSheetControlsRevealHistoryWhileTheMapStaysMounted() =
        runDesktopComposeUiTest(width = 411, height = 700) {
            setContent {
                LogDateTheme {
                    WorkspaceSupportingSheet(
                        "Six visits",
                        focus = { Box(Modifier.fillMaxSize().testTag("map")) },
                        supporting = { Text("Mothership Coffee") },
                    )
                }
            }
            onNodeWithText("Six visits").assertIsDisplayed()
            onNodeWithText("Browse").performClick()
            onNodeWithText("Mothership Coffee").assertIsDisplayed()
            onNodeWithTag("map").assertIsDisplayed()
            onNodeWithText("Full view").performClick()
            onNodeWithText("Show map").performClick()
            onNodeWithText("Collapse").performClick()
            onNodeWithText("Six visits").assertIsDisplayed()
        }

    @Test fun restoredExplicitDetailIsVisibleAndDismissalRestoresThePeek() =
        runDesktopComposeUiTest(width = 411, height = 700) {
            setContent {
                LogDateTheme {
                    var detail by rememberSaveable { mutableStateOf<String?>("cafe") }
                    WorkspaceSupportingSheet(
                        "Six visits",
                        supportingContextKey = detail,
                        focus = { Box(Modifier.fillMaxSize()) },
                        supporting = {
                            TextButton(onClick = { detail = null }) { Text("Close coffee detail") }
                        },
                    )
                }
            }
            onNodeWithText("Close coffee detail").assertIsDisplayed()
            onNodeWithText("Close coffee detail").performClick()
            onNodeWithText("Six visits").assertIsDisplayed()
        }
}
