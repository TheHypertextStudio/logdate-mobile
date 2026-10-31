package app.logdate.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkspaceRouteFrameTest {
    @Test
    fun readingCollectionBoundsSurfaceAndAlignsSearchWithContent() = verify(PanelConstraints.ReadingCollection, 560f, 32f)

    @Test
    fun existingReadingRoutesKeepTheirFullWorkingSurface() = verify(PanelConstraints.Reading, 1248f, 16f)

    private fun verify(
        constraints: PanelConstraints,
        expectedWidth: Float,
        searchLeft: Float,
    ) = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent {
            LogDateTheme {
                CompositionLocalProvider(LocalWorkspaceSearchAction provides {}) {
                    WorkspaceRouteFrame(focusConstraints = constraints) {
                        WorkspacePanel(Modifier.testTag("panel")) {
                            Text("Reading content", Modifier.padding(16.dp).testTag("content"))
                        }
                    }
                }
            }
        }
        val panel = onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val search = onNodeWithTag("workspace_search").fetchSemanticsNode().boundsInRoot
        assertEquals(expectedWidth, panel.width)
        assertEquals(searchLeft, search.left)
        onAllNodesWithTag("workspace_search").assertCountEquals(1)
    }
}
