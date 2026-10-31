package app.logdate.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkspaceRtlTest {
    @Test fun supportingMapAndHistoryAreFullyVisibleInRtl() =
        verifyBounds { focus, support ->
            WorkspaceSupportingSheet("Your day", focus = focus, supporting = { support() })
        }

    @Test fun browseAndFocusAreFullyVisibleInRtl() =
        verifyBounds { focus, support ->
            AdaptiveWorkspaceLayout(browse = support, focus = focus)
        }

    private fun verifyBounds(layout: @Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit) =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        layout(
                            { Box(Modifier.fillMaxSize().testTag("visual_focus")) },
                            { Box(Modifier.fillMaxSize().testTag("supporting_content")) },
                        )
                    }
                }
            }
            val focus = onNodeWithTag("visual_focus").fetchSemanticsNode().boundsInRoot
            val support = onNodeWithTag("supporting_content").fetchSemanticsNode().boundsInRoot
            assertTrue(focus.width > 600, "Visual focus is clipped: $focus")
            assertTrue(support.width >= 280, "Supporting content is clipped: $support")
            assertTrue(focus.right <= support.left || support.right <= focus.left, "Physical regions overlap in RTL: $focus $support")
        }
}
