package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalPanelLayoutInfo
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkspaceRouteDecoratorTest {
    @Test
    fun playbackBypassesHomeFraming() = verifyPlayback()

    @Test
    fun playbackReplacesTheHomeSceneInsteadOfBecomingAnotherPanel() = verifyPlayback(HomeRoute)

    private fun verifyPlayback(source: NavKey? = null) =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalWorkspaceSearchAction provides {}) {
                        NavDisplay(
                            backStack = listOfNotNull(source, RewindDetailRoute("00000000-0000-0000-0000-000000000001")),
                            sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>(supportsDualPane = { true })),
                            onBack = {},
                            entryDecorators = listOf(rememberWorkspaceRouteDecorator()),
                            entryProvider =
                                entryProvider {
                                    taggedEntry<HomeRoute> { Box(Modifier.fillMaxSize().testTag("home")) }
                                    taggedEntry<RewindDetailRoute> {
                                        Box(
                                            Modifier.fillMaxSize().testTag(
                                                if (LocalWorkspaceHosted.current ||
                                                    LocalPanelLayoutInfo.current != null
                                                ) {
                                                    "hosted"
                                                } else {
                                                    "playback"
                                                },
                                            ),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
            onAllNodesWithTag("home").assertCountEquals(0)
            onAllNodesWithTag("workspace_search").assertCountEquals(0)
            onAllNodesWithTag("hosted").assertCountEquals(0)
            val playback = onNodeWithTag("playback").fetchSemanticsNode().boundsInRoot
            assertEquals(1280f, playback.width)
            assertEquals(800f, playback.height)
        }
}
